package norm.generator

import org.intellij.lang.annotations.Language
import java.sql.Connection
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.util.UUID

/**
 * Loads schema metadata from PostgreSQL's system catalogs via JDBC.
 *
 * Provides a lazy-loaded cache of [functionOverloads] computed once per instance, plus
 * on-demand queries for enums, domains, column comments, and stored procedures.
 *
 * @param connection An open JDBC connection to a PostgreSQL database with the schema applied.
 */
internal class PgCatalogLoader(private val connection: Connection) {

  private val nodeTreeParser = PgNodeTreeParser()

  private val pgMajorVersion = connection.metaData.databaseMajorVersion

  init {
    checkPostgresVersion()
  }

  /**
   * Maps function names to their overload metadata from `pg_proc`.
   *
   * Used to infer parameter names and result nullability for queries that pass parameters to
   * function calls. Functions may be overloaded, so we store a list of overloads per function
   * name, matched by argument count at inference time.
   *
   * Loaded lazily on first use and cached for the lifetime of this loader.
   */
  val functionOverloads: Map<String, List<FunctionOverload>> by lazy(::loadFunctionOverloads)

  /**
   * Maps function OIDs to their strictness flag from `pg_proc.proisstrict`.
   *
   * A strict function returns `null` when any argument is `null` — useful for determining
   * expression nullability from the node tree. Keyed by OID for direct lookup from
   * FUNCEXPR/OPEXPR/WINDOWFUNC nodes. Includes regular functions (`prokind = 'f'`) and
   * window functions (`prokind = 'w'`). Excludes aggregates (`prokind = 'a'`) — those
   * use [aggregateHasNonNullInitialValue] instead.
   */
  val functionStrictnessByOid: Map<Int, Boolean> by lazy(::loadFunctionStrictness)

  /**
   * OIDs of IMMUTABLE, non-set-returning functions (`pg_proc.provolatile = 'i' AND NOT proretset`).
   *
   * Also covers operators, with no separate `pg_operator` lookup needed: [PgNodeExpression.OpExpr]
   * and [PgNodeExpression.ScalarArrayOpExpr] are keyed by `opfuncid`/`oprcode` — the operator's
   * *implementing function* OID — which is itself a `pg_proc` row already captured by this single
   * query, unlike [neverNullForNonNullInputOids], which needs its own `pg_operator` query because it
   * safe-lists specific (symbol, operand types) triples rather than a volatility flag every
   * `pg_proc` row already carries.
   *
   * Used by [NodeTreeNullabilityAnalyzer.isSafeFromGroupingSetNullExtension]'s `foldsToConst` leg —
   * see that method's KDoc for why IMMUTABLE specifically (not STRICT, and not restricted to
   * `pg_catalog`) is the correct test: this mirrors PostgreSQL's own planner rule for constant
   * folding directly, rather than an empirically-swept safe-list, so a user-defined `IMMUTABLE`
   * function is exactly as fold-safe as a built-in one and no namespace restriction is needed.
   */
  val immutableFunctionOids: Set<Int> by lazy(::loadImmutableFunctionOids)

  /**
   * Maps aggregate function OIDs to whether they have a non-null initial transition value.
   *
   * Aggregates with non-null `agginitval` (like COUNT with `agginitval = '0'`) return a
   * non-null value for empty groups. Aggregates with `null` `agginitval` (SUM, AVG, MIN, MAX)
   * return `null` for empty groups.
   *
   * Returns `null` for absent keys — this can occur if the OID belongs to a non-aggregate function.
   */
  val aggregateHasNonNullInitialValue: Map<Int, Boolean> by lazy(::loadAggregateInitialValues)

  /**
   * OIDs of non-strict functions that are guaranteed to never return `null` for ANY combination of
   * argument values passed in the ORDINARY (non-`VARIADIC`) calling form, including when EVERY
   * argument is `null`. Currently `concat` only: verified live `concat(NULL::text, NULL::text)`
   * returns `''` (empty string), never `null`.
   *
   * The `VARIADIC` calling form (`concat(VARIADIC arr)`) is a DIFFERENT case this list's claim does
   * NOT cover: it passes the array argument itself as one value rather than exploding it into
   * elements, and `concat(VARIADIC arr)` IS `null` when `arr` itself is `null` (verified live on
   * PostgreSQL 16, 17, and 18). [PgNodeExpression.FuncExpr.isVariadic] exists specifically so
   * [NodeTreeNullabilityAnalyzer.isNonNull] and
   * [NodeTreeNullabilityAnalyzer.isSafeFromGroupingSetNullExtension] can detect this form and
   * require every argument non-null instead of trusting this list unconditionally — see both
   * methods' KDoc.
   *
   * `concat_ws` is deliberately NOT on this list at all, even for the ordinary calling form, despite
   * also being non-strict: it is non-null only when its FIRST argument (the separator) is non-null
   * — `concat_ws(NULL, 'x', 'y')` returns `null` (verified live on PostgreSQL 16, 17, and 18),
   * because a `null` separator poisons the whole result even though the later arguments are
   * individually null-tolerant. That argument-position-dependent condition does not fit
   * "unconditionally non-null" at all, so it is modeled separately — see
   * [nonNullIffFirstArgumentNonNullFunctionOids] and [NodeTreeNullabilityAnalyzer]'s `concat_ws`
   * handling in `isNonNull`'s `FuncExpr` branch.
   *
   * This distinction matters beyond precision: [NodeTreeNullabilityAnalyzer.isSafeFromGroupingSetNullExtension]
   * treats membership on THIS list (for a non-`VARIADIC` call) as an unconditional safety proof for
   * the grouping-sets null-extension gate specifically because "non-null regardless of input" also
   * means "non-null regardless of which argument grouping-set null-extension replaces with `null`".
   * A function that is only non-null for a PARTICULAR argument (like `concat_ws`'s separator) does
   * not have that property — null-extension could target exactly that argument — so it must never
   * be added here.
   *
   * Restricted to `pronamespace = 'pg_catalog'` at query time — a user-defined function sharing the
   * name `concat` must not ride along onto this list; see the loader.
   */
  val alwaysNonNullFunctionOids: Set<Int> by lazy(::loadAlwaysNonNullFunctions)

  /**
   * OIDs of functions that are non-null if and only if their FIRST argument is non-null, regardless
   * of any other argument's nullability, in the ORDINARY (non-`VARIADIC`) calling form. Currently
   * `concat_ws` only: verified live `concat_ws(',', NULL, NULL)` returns `','`-joined empty string
   * (`''`, non-null) but `concat_ws(NULL, 'x', 'y')` returns `null` — the separator (first argument)
   * alone determines whether the whole call can be `null`.
   *
   * The `VARIADIC` calling form (`concat_ws(',', VARIADIC arr)`) does NOT get this treatment: it
   * passes the array argument itself as one value, and `concat_ws(',', VARIADIC arr)` IS `null`
   * when `arr` itself is `null` even though the literal separator is non-null (verified live on
   * PostgreSQL 16, 17, and 18) — see [PgNodeExpression.FuncExpr.isVariadic]'s KDoc.
   *
   * Used by [NodeTreeNullabilityAnalyzer.isNonNull]'s [PgNodeExpression.FuncExpr] branch (for the
   * non-`VARIADIC` form only). Not used by the grouping-sets safety gate
   * ([NodeTreeNullabilityAnalyzer.isSafeFromGroupingSetNullExtension]) at all, `VARIADIC` or not:
   * unlike [alwaysNonNullFunctionOids], this property depends on WHICH argument is non-null, so a
   * `Var` in the first-argument position is exactly as unsafe under grouping-set null-extension as
   * any other `Var` — the generic aggregate/window-domination rule already handles it correctly
   * without a dedicated leg.
   *
   * Restricted to `pronamespace = 'pg_catalog'` at query time — a user-defined function sharing the
   * name `concat_ws` must not ride along onto this list; see the loader.
   */
  val nonNullIffFirstArgumentNonNullFunctionOids: Set<Int> by lazy(::loadNonNullIffFirstArgumentNonNullFunctionOids)

  /**
   * OIDs of functions, cast functions, and operators (materialized to their implementing function
   * OID via `pg_operator.oprcode`) that are proven TOTAL on non-null input — every combination of
   * non-null arguments produces a non-null result. An ERROR is fine; only a silent `null` return
   * disqualifies a candidate.
   *
   * This is verified only for the ORDINARY, element-wise calling convention (see `SafeListSweepTest`).
   * [NodeTreeNullabilityAnalyzer.isNonNull]'s [PgNodeExpression.FuncExpr] branch never consults
   * this set for a `VARIADIC` call: a non-null array argument says nothing about whether an
   * element inside it is non-null, and no function on this list is variadic today (verified live
   * on PostgreSQL 16, 17, and 18: `provariadic <> 0` intersected with every safe-listed name here
   * is empty) — but this must not silently start trusting the list for that shape the moment one
   * is added. See [NodeTreeNullabilityAnalyzer]'s `isNeverNullForNonNullInput` KDoc.
   *
   * `pg_proc.proisstrict` is NOT sufficient for this on its own. STRICT only guarantees
   * NULL-in => NULL-out; it says nothing about the converse. `substring(text, '(z)')` (regex, no
   * match), `regexp_match(text, pattern)` (no match), and `array_length(ARRAY[]::text[], 1)`
   * (empty array) are all STRICT and all return `null` on fully non-null, well-typed input. Any
   * inference rule built from strictness alone is therefore unsound. This set exists to be an
   * ADDITIONAL conjunct alongside strictness in [NodeTreeNullabilityAnalyzer], never a
   * replacement for it — so an unforeseen non-strict overload of a listed name can never slip
   * through.
   *
   * Functions are safe-listed by `pg_proc.proname` PLUS ARGUMENT TYPE SIGNATURE — see
   * [NEVER_NULL_FUNCTION_SIGNATURES] — restricted to `pronamespace = 'pg_catalog'`. Keying by
   * name alone is NOT safe: `lower(anyrange)`/`upper(anyrange)`/`lower(anymultirange)`/
   * `upper(anymultirange)` share `proname` with the totally-safe `lower(text)`/`upper(text)` but
   * return `null` on a non-null, well-typed, non-empty-but-unbounded range or an empty range —
   * `SELECT upper(int4range '[1,)')` and `SELECT lower(int4range 'empty')` both return `null`.
   * `substring` is the reason a signature-only match still is not always enough on its own:
   * `substring(text, int, int)` is total but `substring(text FROM pattern)` is not, and both
   * would share the SAME two-argument-count shape if only argument count were checked — this is
   * why the match is on the full ordered list of argument TYPE NAMES (via `pg_type.typname`), not
   * just arity. `substring` itself is simply left off the list entirely rather than enumerated,
   * since its regex overloads are non-total.
   *
   * Casts are safe-listed by (source type, target type) PAIR — see [NEVER_NULL_CAST_SIGNATURES] —
   * rather than a class-wide blanket over every `pg_cast.castfunc` in `pg_catalog`. A blanket was
   * tried first and is FALSE: `('null'::jsonb)::int4` (and every other `jsonb` → numeric/`boolean`
   * cast) returns `null` on well-typed, non-null input with no error, because the cast function
   * special-cases the JSON literal `null` rather than raising "cannot convert". A brute-force
   * sweep against live PostgreSQL of every `jsonb`-targeting numeric/`boolean` cast confirmed this
   * for all seven overloads (`int2`, `int4`, `int8`, `numeric`, `float4`, `float8`, `bool`); none
   * of the seven appear in [NEVER_NULL_CAST_SIGNATURES]. The same sweep also found
   * `timestamp`/`timestamptz` → `time`/`timetz` silently returns `null` for the infinite
   * (`'infinity'`/`'-infinity'`) input, rather than erroring the way `'infinity'::interval::time`
   * does — so those three pairs are excluded too. Every other pair the sweep checked (see
   * [SafeListSweepTest] for the corpus and the full case count) proved total, including on `NaN`,
   * `Infinity`, `-Infinity`, min/max integer values, and empty strings.
   *
   * pgcrypto's `digest` and `hmac` are the one extension carve-out, keyed through `pg_depend`
   * (`deptype = 'e'`) to the `pgcrypto` extension itself, so a user-defined `digest` in `public`
   * cannot ride this carve-out. `encode`/`decode` are ordinary `pg_catalog` functions and are
   * safe-listed on the main function list above, not here. All four `digest`/`hmac` overloads
   * (`digest(text, text)`, `digest(bytea, text)`, `hmac(text, text, text)`, `hmac(bytea, bytea,
   * text)`) were verified total on empty non-null input; an unrecognized hash algorithm name
   * errors rather than returning `null`.
   *
   * Operators are safe-listed by (symbol, left operand type, right operand type) TRIPLE — see
   * [NEVER_NULL_OPERATOR_SIGNATURES] — restricted to `oprnamespace = 'pg_catalog'`, and
   * materialized to the OID of the implementing function via `oprcode`, the same OID space
   * [PgNodeExpression.OpExpr] and [PgNodeExpression.ScalarArrayOpExpr] (e.g. `= ANY(...)`) are
   * keyed by, so no separate operator-specific lookup is needed. Symbol alone is NOT safe: `path +
   * path` (`path_add`) shares the `+` symbol with the totally-safe `int4 + int4`, but returns
   * `null`, not an error, when either operand is a CLOSED path (`SELECT ((0,0),(1,1),(2,0)) +
   * ((0,0),(1,1),(2,0))` on two well-typed, non-null closed paths). A brute-force sweep against
   * live PostgreSQL of every symbol-restricted-but-unrestricted-by-type combination found exactly
   * this one bad shape; `path` is entirely absent from [NEVER_NULL_OPERATOR_SIGNATURES] as a
   * result — every triple that remains was independently swept and found total (see
   * [SafeListSweepTest]). A left or right type of `null` in a signature means the operator is
   * unary on that side (no left operand for a prefix operator, no right operand for a postfix
   * operator), mirroring `pg_operator.oprleft`/`oprright` themselves being `0` (no operand) for a
   * unary operator — e.g. unary (prefix) `-` (negation), `+`, and `~` (bitwise complement) are all
   * PREFIX-only overloads of symbols that are ALSO binary elsewhere in this same list (binary `-`
   * is subtraction, binary `~` is regex match); they needed adding here alongside the binary
   * overloads specifically because the earlier symbol-only blanket rule this list replaced made
   * every overload — unary and binary alike — safe together, and losing the unary overloads
   * would have been an unintended narrowing this fix must not introduce.
   *
   * Omitting a signature from this set only WIDENS the result to nullable — it never narrows a
   * truly nullable expression to non-null — so when in doubt about whether a specific signature is
   * total on every non-null, well-typed input (including infinite/empty/unbounded edge values, not
   * just "typical" ones — see [NEVER_NULL_FUNCTION_SIGNATURES] for the `extract`/`date_part`
   * counterexample this note exists to flag), the correct default is to leave it off.
   */
  val neverNullForNonNullInputOids: Set<Int> by lazy(::loadNeverNullForNonNullInputOids)

  /**
   * OIDs of the 3-argument overloads of `lag` and `lead` window functions. These overloads accept
   * `(value, offset, default)` and return a non-null result when both the value expression and the
   * default expression are non-null — the default fills in for rows at window boundaries where
   * 1-arg `lag`/`lead` would return `null`.
   */
  val lagLeadWithDefaultOids: Set<Int> by lazy(::loadLagLeadWithDefaultOids)

  /**
   * Maps `(relid, attnum)` pairs to `pg_attribute.attnotnull`.
   *
   * Used by [NodeTreeNullabilityAnalyzer] to determine whether a source column (referenced
   * by a VAR node) is declared NOT NULL in the schema. Only includes user-visible columns
   * (`attnum > 0` and `NOT attisdropped`).
   *
   * Returns `null` for absent keys — this occurs for columns not present in `pg_attribute`
   * (e.g., virtual columns, system columns with attnum <= 0).
   */
  val columnNotNullByRelidAndAttnum: Map<Pair<Int, Int>, Boolean> by lazy(::loadColumnNotNull)

  /**
   * Maps `(relid, attnum)` pairs to `true` for view columns that are guaranteed NOT NULL.
   *
   * PostgreSQL stores `pg_attribute.attnotnull = false` for all view columns. This map
   * corrects that by tracing each view column back to its source base table column via
   * `pg_depend`, then subtracting columns that are nullable due to outer joins within the
   * view's definition.
   *
   * Covers both regular views (`relkind = 'v'`) and materialized views (`relkind = 'm'`).
   * No schema filter — the node tree can reference views from any schema.
   *
   * Returns `false` for absent keys.
   */
  val viewColumnNotNullByRelidAndAttnum: Map<Pair<Int, Int>, Boolean> by lazy(::loadViewColumnNotNull)

  /**
   * Maps `(relid, attnum)` pairs to `true` for REGULAR (non-materialized) view columns that are
   * nullable because they sit on the nullable side of an outer join (`LEFT`/`RIGHT`/`FULL JOIN`)
   * inside the view's own definition — computed once here and shared by both
   * [viewColumnNotNullByRelidAndAttnum]'s own subtraction step and the public,
   * schema-scoped [loadViewOuterJoinNullableColumns], so the two can never independently drift
   * from each other's answer for the same column the way the pre-existing duplicate queries could.
   *
   * Materialized views are excluded because their data is stored at refresh time; the outer-join
   * structure of the definition does not affect the persisted NOT NULL guarantee of a materialized
   * view's columns (PostgreSQL allows defining NOT NULL constraints on matview columns separately).
   *
   * Returns `false` for absent keys (not on the nullable side of any outer join the analyzer
   * detects, or not a regular view column at all).
   */
  val viewOuterJoinNullableByRelidAndAttnum: Map<Pair<Int, Int>, Boolean> by lazy(
    ::loadViewOuterJoinNullableByRelidAndAttnum,
  )

  /**
   * Shared strictness lookup used by every [buildAnalyzer] call site and by
   * [NodeTreeNullabilityAnalyzer.qualProvenNonNullVars].
   */
  private val isStrictFunction: (Int) -> Boolean = { oid -> functionStrictnessByOid[oid] == true }

  private fun loadColumnNotNull(): Map<Pair<Int, Int>, Boolean> = buildMap {
    connection.createStatement().use { stmt ->
      // No schema filter — relids come from the query's rtable and may reference tables
      // from any schema. Filtering by schema here would miss tables in non-default schemas.
      stmt.executeQuery(
        """
        SELECT attrelid::integer, attnum, attnotnull
        FROM pg_catalog.pg_attribute
        WHERE attnum > 0 AND NOT attisdropped
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          put(rs.getInt("attrelid") to rs.getInt("attnum"), rs.getBoolean("attnotnull"))
        }
      }
    }
  }

  private fun loadViewColumnNotNull(): Map<Pair<Int, Int>, Boolean> {
    // Step 1: build the initial non-null set from pg_depend (source base table column is NOT NULL).
    val candidateNotNull = mutableMapOf<Pair<Int, Int>, Boolean>()
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT
          view_class.oid::integer AS view_relid,
          view_attr.attnum AS view_attnum,
          source_attr.attnotnull AS source_notNull
        FROM pg_catalog.pg_depend d
        JOIN pg_catalog.pg_rewrite rw ON rw.oid = d.objid
        JOIN pg_catalog.pg_class view_class ON view_class.oid = rw.ev_class
        JOIN pg_catalog.pg_attribute source_attr
          ON source_attr.attrelid = d.refobjid AND source_attr.attnum = d.refobjsubid
        JOIN pg_catalog.pg_class source_class ON source_class.oid = d.refobjid
        JOIN pg_catalog.pg_attribute view_attr ON view_attr.attrelid = view_class.oid
        WHERE view_class.relkind IN ('v', 'm')
          AND d.classid = 'pg_rewrite'::regclass
          AND d.refclassid = 'pg_class'::regclass
          AND d.refobjsubid > 0
          AND d.deptype = 'n'
          AND source_class.relkind IN ('r', 'p')
          AND view_attr.attnum > 0
          AND view_attr.attname = source_attr.attname
          AND source_attr.attnum > 0
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          val key = rs.getInt("view_relid") to rs.getInt("view_attnum")
          // Mark as non-null only when source is NOT NULL. If multiple source columns are
          // found for the same view column, keep false (safe default) if any is nullable.
          if (rs.getBoolean("source_notNull")) {
            candidateNotNull.putIfAbsent(key, true)
          } else {
            candidateNotNull[key] = false
          }
        }
      }
    }

    // Step 2: subtract columns that are nullable due to outer joins in the view's definition,
    // using the shared computation also exposed publicly via loadViewOuterJoinNullableColumns.
    for ((key, outerJoinNullable) in viewOuterJoinNullableByRelidAndAttnum) {
      if (outerJoinNullable) {
        candidateNotNull[key] = false
      }
    }

    return candidateNotNull.filterValues { it }
  }

  /**
   * Loader for [viewOuterJoinNullableByRelidAndAttnum]: for each regular (non-materialized) view,
   * runs node tree analysis to find outer-join-nullable columns by position.
   */
  private fun loadViewOuterJoinNullableByRelidAndAttnum(): Map<Pair<Int, Int>, Boolean> = buildMap {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT
          c.oid::integer AS view_relid,
          rw.ev_action::text AS node_tree,
          array_agg(a.attnum ORDER BY a.attnum) AS attnums
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_rewrite rw ON rw.ev_class = c.oid AND rw.ev_type = '1'
        JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
        WHERE c.relkind = 'v'
        GROUP BY c.oid, rw.ev_action
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          val viewRelid = rs.getInt("view_relid")
          val nodeTree = rs.getString("node_tree")

          @Suppress("UNCHECKED_CAST")
          val attnums = (rs.getArray("attnums").array as Array<*>).map { (it as Number).toInt() }
          val outerJoinNullable = NodeTreeNullabilityAnalyzer.extractOuterJoinNullability(nodeTree)
          for ((index, nullable) in outerJoinNullable.withIndex()) {
            if (nullable && index < attnums.size) {
              put(viewRelid to attnums[index], true)
            }
          }
        }
      }
    }
  }

  private fun loadFunctionOverloads(): Map<String, List<FunctionOverload>> =
    buildMap<String, MutableList<FunctionOverload>> {
      connection.createStatement().use { stmt ->
        stmt.executeQuery(
          """
      SELECT p.proname, p.proargnames, p.pronargs, p.proisstrict
      FROM pg_catalog.pg_proc p
      JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
      WHERE p.prokind IN ('f', 'p')
        AND p.pronargs > 0
          """.trimIndent(),
        ).use { rs ->
          while (rs.next()) {
            val name = rs.getString("proname")
            val argNamesArray = rs.getArray("proargnames")
            val argNames = if (argNamesArray != null) {
              @Suppress("UNCHECKED_CAST")
              (argNamesArray.array as Array<String>).toList()
            } else {
              emptyList()
            }
            val isStrict = rs.getBoolean("proisstrict")
            computeIfAbsent(name) { mutableListOf() }.add(FunctionOverload(argNames, isStrict))
          }
        }
      }
    }

  private fun loadFunctionStrictness(): Map<Int, Boolean> = buildMap {
    connection.createStatement().use { stmt ->
      // Include regular functions ('f') and window functions ('w') — both appear in node tree expressions.
      // Excludes procedures ('p') and aggregates ('a') — aggregates use agginitval, not strictness.
      stmt.executeQuery(
        "SELECT oid::integer, proisstrict FROM pg_catalog.pg_proc WHERE prokind IN ('f', 'w')",
      ).use { rs ->
        while (rs.next()) {
          put(rs.getInt("oid"), rs.getBoolean("proisstrict"))
        }
      }
    }
  }

  private fun loadImmutableFunctionOids(): Set<Int> = buildSet {
    connection.createStatement().use { stmt ->
      // Regular functions and window functions, matching loadFunctionStrictness's scope; excludes
      // procedures ('p') and aggregates ('a'), which are never foldable subexpressions here.
      //
      // No separate pg_operator/oprcode query is needed: an operator's implementing function
      // (oprcode) is itself a row in pg_proc, so this single query already covers operators too —
      // PgNodeExpression.OpExpr/ScalarArrayOpExpr are keyed by that same function OID, not by any
      // pg_operator-specific ID. Measured empirically: zero operator oprcode OIDs satisfying the
      // volatility/proretset filter fall outside this query's result.
      stmt.executeQuery(
        "SELECT oid::integer FROM pg_catalog.pg_proc WHERE provolatile = 'i' AND NOT proretset AND prokind IN ('f', 'w')",
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
      }
    }
  }

  private fun loadAggregateInitialValues(): Map<Int, Boolean> = buildMap {
    connection.createStatement().use { stmt ->
      // An aggregate is non-null for empty groups only when it has a non-null initial transition value
      // AND no final function. Aggregates with a finalfunc (AVG, STDDEV, etc.) can return null even
      // with a non-null agginitval because the finalfunc may produce null (e.g., AVG divides by zero
      // count).
      stmt.executeQuery(
        "SELECT aggfnoid::integer, (agginitval IS NOT NULL AND aggfinalfn = 0) AS has_initial_value FROM pg_catalog.pg_aggregate",
      ).use { rs ->
        while (rs.next()) {
          put(rs.getInt("aggfnoid"), rs.getBoolean("has_initial_value"))
        }
      }
    }
  }

  private fun loadAlwaysNonNullFunctions(): Set<Int> = buildSet {
    connection.createStatement().use { stmt ->
      // pronamespace restricted to pg_catalog: a user-defined function named `concat` must not
      // ride onto this list just by sharing the name (verified live: CREATE FUNCTION
      // us.concat(...) with different null behavior gets picked up without this restriction).
      stmt.executeQuery(
        """
        SELECT p.oid::integer AS oid
        FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'concat' AND NOT p.proisstrict AND n.nspname = 'pg_catalog'
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
      }
    }
  }

  private fun loadNonNullIffFirstArgumentNonNullFunctionOids(): Set<Int> = buildSet {
    connection.createStatement().use { stmt ->
      // pronamespace restricted to pg_catalog — see loadAlwaysNonNullFunctions's identical guard.
      stmt.executeQuery(
        """
        SELECT p.oid::integer AS oid
        FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'concat_ws' AND NOT p.proisstrict AND n.nspname = 'pg_catalog'
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
      }
    }
  }

  private fun loadNeverNullForNonNullInputOids(): Set<Int> = buildSet {
    connection.createStatement().use { stmt ->
      val safeSignatureValues = NEVER_NULL_FUNCTION_SIGNATURES.joinToString(", ") { signature ->
        val nameLiteral = "'${signature.name}'"
        val argumentTypesLiteral = if (signature.argumentTypeNames.isEmpty()) {
          "ARRAY[]::text[]"
        } else {
          "ARRAY[${signature.argumentTypeNames.joinToString(", ") { typeName -> "'$typeName'" }}]"
        }
        "($nameLiteral, $argumentTypesLiteral)"
      }
      stmt.executeQuery(
        """
        WITH safe_signature(proname, argument_types) AS (
          VALUES $safeSignatureValues
        )
        SELECT p.oid::integer AS oid
        FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        JOIN safe_signature s ON s.proname = p.proname
        WHERE n.nspname = 'pg_catalog'
          AND s.argument_types = COALESCE(
            (
              SELECT array_agg(t.typname::text ORDER BY u.ordinality)
              FROM unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
              JOIN pg_catalog.pg_type t ON t.oid = u.type_oid
            ),
            ARRAY[]::text[]
          )
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
      }
    }
    connection.createStatement().use { stmt ->
      val safeCastValues = NEVER_NULL_CAST_SIGNATURES.joinToString(", ") { signature ->
        "('${signature.sourceTypeName}', '${signature.targetTypeName}')"
      }
      stmt.executeQuery(
        """
        WITH safe_cast(source_type, target_type) AS (
          VALUES $safeCastValues
        )
        SELECT c.castfunc::integer AS oid
        FROM pg_catalog.pg_cast c
        JOIN pg_catalog.pg_type st ON st.oid = c.castsource
        JOIN pg_catalog.pg_type tt ON tt.oid = c.casttarget
        JOIN pg_catalog.pg_proc p ON p.oid = c.castfunc
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        JOIN safe_cast s ON s.source_type = st.typname AND s.target_type = tt.typname
        WHERE c.castfunc != 0 AND n.nspname = 'pg_catalog'
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
      }
    }
    connection.createStatement().use { stmt ->
      val safeOperatorValues = NEVER_NULL_OPERATOR_SIGNATURES.joinToString(", ") { signature ->
        val leftLiteral = signature.leftTypeName?.let { "'$it'" } ?: "NULL::text"
        val rightLiteral = signature.rightTypeName?.let { "'$it'" } ?: "NULL::text"
        "('${signature.symbol}', $leftLiteral, $rightLiteral)"
      }
      stmt.executeQuery(
        """
        WITH safe_operator(symbol, left_type, right_type) AS (
          VALUES $safeOperatorValues
        )
        SELECT o.oprcode::integer AS oid
        FROM pg_catalog.pg_operator o
        JOIN pg_catalog.pg_namespace n ON n.oid = o.oprnamespace
        LEFT JOIN pg_catalog.pg_type lt ON lt.oid = o.oprleft
        LEFT JOIN pg_catalog.pg_type rt ON rt.oid = o.oprright
        JOIN safe_operator s ON s.symbol = o.oprname
          AND s.left_type IS NOT DISTINCT FROM lt.typname
          AND s.right_type IS NOT DISTINCT FROM rt.typname
        WHERE n.nspname = 'pg_catalog' AND o.oprcode != 0
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
      }
    }
    connection.createStatement().use { stmt ->
      // pgcrypto extension carve-out, keyed through pg_depend so a user-defined `digest` or
      // `hmac` outside the extension cannot ride along.
      stmt.executeQuery(
        """
        SELECT p.oid::integer AS oid FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_depend d
          ON d.objid = p.oid AND d.classid = 'pg_catalog.pg_proc'::regclass AND d.deptype = 'e'
        JOIN pg_catalog.pg_extension e ON e.oid = d.refobjid
        WHERE e.extname = 'pgcrypto' AND p.proname IN ('digest', 'hmac')
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
      }
    }
  }

  private fun loadLagLeadWithDefaultOids(): Set<Int> = buildSet {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        "SELECT oid::integer FROM pg_catalog.pg_proc WHERE proname IN ('lag', 'lead') AND pronargs = 3 AND prokind = 'w'",
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
      }
    }
  }

  /**
   * Returns NOT NULL column information for views and materialized views in [schemaName], for
   * [JdbcAnalyzer]'s catalog construction.
   *
   * A thin name-resolution adapter over [viewColumnNotNullByRelidAndAttnum] — the single,
   * relid-keyed source of truth for view-column nullability (source-column tracing via
   * `pg_depend`, with any-nullable-source-wins reduction, already net of outer-join subtraction —
   * see the property's own KDoc). This function does no computation of its own: it resolves
   * each `(relid, attnum)` the shared map already judged NOT NULL back to a `"viewName.columnName"`
   * string, scoped to [schemaName]. Kept relid-keyed and computed once, rather than re-querying
   * `pg_depend`/`pg_rewrite` by name per schema, so this and every other caller of the shared map
   * can never independently drift from each other's answer for the same column — exactly the
   * divergence that previously made this function skip the any-nullable-source-wins reduction the
   * relid-keyed map already applied.
   *
   * @param schemaName The schema to check.
   * @return A set of `"viewName.columnName"` strings for view/matview columns that are non-nullable.
   */
  fun loadViewColumnNullability(schemaName: String): Set<String> = loadViewColumnNamesByRelidAndAttnum(schemaName)
    .filterKeys { key -> viewColumnNotNullByRelidAndAttnum[key] == true }
    .values.toSet()

  /**
   * Returns view columns that are nullable due to outer joins in the view's own definition.
   *
   * A thin name-resolution adapter over [viewOuterJoinNullableByRelidAndAttnum] — the single,
   * relid-keyed source of truth for this, also consulted by [viewColumnNotNullByRelidAndAttnum]'s
   * own subtraction step. This function does no computation of its own: it resolves each
   * `(relid, attnum)` the shared map already judged outer-join-nullable back to a
   * `"viewName.columnName"` string, scoped to [schemaName].
   *
   * @param schemaName The schema to inspect.
   * @return A set of `"viewName.columnName"` strings for view columns that are nullable from outer joins.
   */
  fun loadViewOuterJoinNullableColumns(schemaName: String): Set<String> =
    loadViewColumnNamesByRelidAndAttnum(schemaName)
      .filterKeys { key -> viewOuterJoinNullableByRelidAndAttnum[key] == true }
      .values.toSet()

  /**
   * Maps `(relid, attnum)` to `"viewName.columnName"` for every view/matview column in
   * [schemaName] — the name-resolution half of [loadViewColumnNullability]'s adapter over
   * [viewColumnNotNullByRelidAndAttnum].
   */
  private fun loadViewColumnNamesByRelidAndAttnum(schemaName: String): Map<Pair<Int, Int>, String> = buildMap {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT c.oid::integer AS relid, a.attnum, c.relname AS view_name, a.attname AS column_name
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
        WHERE n.nspname = '$schemaName'
          AND c.relkind IN ('v', 'm')
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          put(
            rs.getInt("relid") to rs.getInt("attnum"),
            "${rs.getString("view_name")}.${rs.getString("column_name")}",
          )
        }
      }
    }
  }

  /**
   * Returns the names of partition children in [schemaName].
   *
   * Partition children (e.g., `event_2026 PARTITION OF event`) are implementation details of partitioned
   * tables. They appear as regular `"TABLE"` entries in JDBC metadata but should be excluded from the catalog
   * because users query the parent table, not individual partitions.
   *
   * @param schemaName The schema to check for partitions.
   * @return A set of table names that are partition children and should be excluded from the catalog.
   */
  fun loadPartitionChildren(schemaName: String): Set<String> = buildSet {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT c.relname
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = '$schemaName'
          AND c.relispartition = true
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          add(rs.getString("relname"))
        }
      }
    }
  }

  /**
   * Returns comments for all table-like relations in [schemaName], keyed by relation name.
   *
   * Includes tables, partitioned tables, views, and materialized views.
   * Comments are set with `COMMENT ON TABLE|VIEW|MATERIALIZED VIEW name IS '...'` in DDL.
   *
   * @param schemaName The schema to load comments for.
   * @return A map from relation name to the comment text. Relations without comments are absent.
   */
  fun loadTableComments(schemaName: String): Map<String, String> = buildMap {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT c.relname AS table_name, d.description
        FROM pg_catalog.pg_description d
        JOIN pg_catalog.pg_class c ON c.oid = d.objoid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = '$schemaName'
          AND d.objsubid = 0
          AND c.relkind IN ('r', 'p', 'v', 'm')
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          put(rs.getString("table_name"), rs.getString("description"))
        }
      }
    }
  }

  /**
   * Returns column comments for all tables in [schemaName], keyed by `"tableName.columnName"`.
   *
   * Comments are set with `COMMENT ON COLUMN table.col IS '...'` in DDL.
   *
   * @param schemaName The schema to load comments for.
   * @return A map from `"table.column"` to the comment text. Columns without comments are absent.
   */
  fun loadColumnComments(schemaName: String): Map<String, String> = buildMap {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT c.relname AS table_name, a.attname AS column_name, d.description
        FROM pg_catalog.pg_description d
        JOIN pg_catalog.pg_class c ON c.oid = d.objoid
        JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.objsubid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = '$schemaName'
          AND d.objsubid > 0
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          put("${rs.getString("table_name")}.${rs.getString("column_name")}", rs.getString("description"))
        }
      }
    }
  }

  /**
   * Returns all enum types defined in [schemaName], with their labels in declaration order.
   *
   * Comments are set with `COMMENT ON TYPE enumname IS '...'` in DDL.
   *
   * @param schemaName The schema to introspect.
   * @return One [Enum] per type, with [Enum.vals] ordered by `enumsortorder` and [Enum.comment] if present.
   */
  fun introspectEnums(schemaName: String): List<Enum> {
    val enumsByName = buildMap<String, MutableList<String>> {
      connection.createStatement().use { stmt ->
        stmt.executeQuery(
          """
            SELECT t.typname, e.enumlabel
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_enum e ON t.oid = e.enumtypid
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = '$schemaName'
            ORDER BY t.typname, e.enumsortorder
          """.trimIndent(),
        ).use { rs ->
          while (rs.next()) {
            computeIfAbsent(rs.getString("typname")) { mutableListOf() }.add(rs.getString("enumlabel"))
          }
        }
      }
    }

    val enumComments = buildMap<String, String> {
      connection.createStatement().use { stmt ->
        stmt.executeQuery(
          """
            SELECT t.typname, d.description
            FROM pg_catalog.pg_description d
            JOIN pg_catalog.pg_type t ON t.oid = d.objoid
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = '$schemaName'
              AND t.typtype = 'e'
          """.trimIndent(),
        ).use { rs ->
          while (rs.next()) {
            put(rs.getString("typname"), rs.getString("description"))
          }
        }
      }
    }

    return enumsByName.map { (name, values) ->
      Enum(
        name = name,
        vals = values,
        comment = enumComments[name].orEmpty(),
      )
    }
  }

  /**
   * Returns all domain types defined in [schemaName], with their base types and optional comments.
   *
   * PostgreSQL domains (e.g., `CREATE DOMAIN email AS TEXT`) are user-defined types that wrap a
   * base type with optional constraints. Comments are set with `COMMENT ON DOMAIN name IS '...'`.
   *
   * @param schemaName The schema to introspect.
   * @return One [Domain] per domain type, with [Domain.baseType] as the Postgres base type name
   *   and [Domain.comment] if present.
   */
  fun introspectDomains(schemaName: String): List<Domain> = buildList {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT t.typname AS domain_name, bt.typname AS baseType, d.description
        FROM pg_catalog.pg_type t
        JOIN pg_catalog.pg_type bt ON t.typbasetype = bt.oid
        JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
        LEFT JOIN pg_catalog.pg_description d ON d.objoid = t.oid AND d.objsubid = 0
        WHERE n.nspname = '$schemaName'
          AND t.typtype = 'd'
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          add(
            Domain(
              name = rs.getString("domain_name"),
              baseType = rs.getString("baseType"),
              comment = rs.getString("description").orEmpty(),
            ),
          )
        }
      }
    }
  }

  /**
   * Determines which result columns of a SQL query can be NULL, using full expression evaluation.
   *
   * Creates a temporary view wrapping the query, reads the analyzed query tree from
   * `pg_rewrite.ev_action`, and evaluates each output column's expression for nullability.
   * This covers outer-join-induced nullability, aggregate return values, strict-function
   * propagation, and more.
   *
   * Parameter placeholders (`?`) are replaced with typed non-null sentinel values before view
   * creation (views cannot contain parameters). The sentinel type is determined via
   * `PreparedStatement.getParameterMetaData()`. Using non-null sentinels instead of `NULL`
   * ensures that strict functions wrapping parameters (e.g., `digest(?, ?)`) correctly evaluate
   * as non-null in the node tree. If parameter metadata is unavailable, `NULL` is used as a
   * safe fallback (the column becomes conservatively nullable).
   *
   * When the SQL contains data-modifying statements (INSERT/UPDATE/DELETE/MERGE in CTEs or as
   * the outer statement), PostgreSQL rejects view creation. In that case, the SQL is transformed
   * into an equivalent SELECT that preserves the join structure — see [transformForViewCreation]
   * for the full picture, summarized here:
   * - A data-modifying CTE body is, WHEREVER POSSIBLE, converted to an equivalent
   *   join-preserving `SELECT` (the same conversion `UPDATE ... FROM`/`DELETE ... USING`/`MERGE
   *   ... RETURNING` outer DML already uses — see [convertDmlCteBodyToSelect]) and validated
   *   against the original body's own column metadata before being trusted; only when that
   *   fails is the body replaced with a `SELECT NULL::<type> AS <name>, ... WHERE FALSE` stub
   *   built from `PreparedStatement.getMetaData()` on the original body.
   * - `UPDATE ... FROM ... RETURNING`, `DELETE ... USING ... RETURNING`, and `MERGE ...
   *   RETURNING` (outer DML, not inside a CTE) are converted to equivalent SELECT statements
   *   that preserve the join structure.
   * - For DML shapes [transformForViewCreation] cannot convert at all — plain `INSERT`, `UPDATE`
   *   without `FROM`, `DELETE` without `USING`, `MERGE` without `RETURNING`, or a top-level
   *   conversion this file could not validate — this function returns
   *   [analyzeUnconvertibleDml]'s result: real `ResultSetMetaData.isNullable` for every column,
   *   OR'd with the same [forcedNullabilityPredicate] safety net the CTE-body stub path applies
   *   (an outer join, a `RETURNING OLD`/`NEW` reference, or a reference to a dangerous CTE the
   *   statement itself declares). See [analyzeUnconvertibleDml]'s KDoc for exactly what it can and
   *   cannot see — in particular, it is still blind to a null-extending construct this file's
   *   scanners don't recognize (see [hasNullExtendingConstruct]'s KDoc).
   *
   * @param sql The SQL query or DML statement to analyze.
   * @return A list of booleans, one per result column in SELECT order. `true` means the
   *   column can be NULL; `false` means it is guaranteed non-null.
   */
  fun queryColumnNullability(@Language("PostgreSQL") sql: String): List<Boolean> {
    val viewSql = buildViewSqlWithSentinels(sql) ?: replaceParameterPlaceholders(sql)

    // Fast path: try creating a view directly (works for all SELECT-only SQL).
    return try {
      analyzeViaTemporaryView(viewSql)
    } catch (_: SQLException) {
      // View creation failed — SQL contains data-modifying statements.
      // Transform the SQL to remove DML while preserving join structure.
      val transformedSql = transformForViewCreation(viewSql)
        // No join structure was found (or convertible) — fall back to a metadata-based probe: see
        // analyzeUnconvertibleDml's KDoc for what it can and cannot see.
        ?: return analyzeUnconvertibleDml(viewSql, sql)
      // transformForViewCreation validates every conversion it makes against the original SQL's
      // own column signature before returning it (see its KDoc), so this SHOULD always prepare.
      // Caught anyway, not re-thrown, as defense in depth: an `SQLException` escaping from here
      // would propagate out of queryColumnNullability entirely — nothing else on the call stack
      // catches it — turning a nullability-analysis gap into a build failure on SQL PostgreSQL
      // itself accepts fine. A future conversion bug this file's validation doesn't catch must
      // degrade to the same fallback as "no join structure found" above, never an abort.
      try {
        // applyQualNarrowing = false: transformedSql came from a DML-to-SELECT conversion (see
        // analyzeViaTemporaryView's KDoc on this parameter), which drops SET/MERGE-action clauses
        // while keeping the original WHERE/ON predicate.
        analyzeViaTemporaryView(transformedSql, applyQualNarrowing = false)
      } catch (_: SQLException) {
        analyzeUnconvertibleDml(viewSql, sql)
      }
    }
  }

  /**
   * Replaces `?` parameter placeholders in [sql] with typed non-null sentinel values.
   *
   * Uses `PreparedStatement.getParameterMetaData()` to determine the PostgreSQL type of each
   * parameter, then builds a non-null literal of that type (e.g., `0::int4`, `''::text`).
   *
   * @return The SQL with `?` replaced by typed sentinels, or `null` if parameter metadata
   *   cannot be obtained (caller should fall back to NULL replacement).
   */
  private fun buildViewSqlWithSentinels(sql: String): String? {
    if ('?' !in sql) return sql
    return try {
      val sentinels = connection.prepareStatement(sql).use { preparedStatement ->
        val parameterMetaData = preparedStatement.parameterMetaData
        (1..parameterMetaData.parameterCount).map { index ->
          nonNullSentinel(parameterMetaData.getParameterTypeName(index))
        }
      }
      replaceParameterPlaceholdersWithSentinels(sql, sentinels)
    } catch (_: SQLException) {
      null
    }
  }

  /**
   * Creates a temporary view from [viewSql], reads its node tree from `pg_rewrite`,
   * and returns per-column nullability using full expression analysis.
   *
   * @param applyQualNarrowing When `false`, disables `WHERE`-clause qual narrowing
   *   ([NodeTreeNullabilityAnalyzer.qualProvenNonNullVars]) for this entire call — the top-level
   *   query AND every nested CTE body and subquery reached from it. Must be `false` whenever
   *   [viewSql] passed through a DML-to-SELECT conversion (`transformForViewCreation`): that
   *   conversion drops the `SET`/`MERGE ... WHEN` clause but keeps the original `WHERE`/`ON`
   *   predicate, so a qual that looks like it proves a `RETURNING` column non-null may in fact be
   *   testing a value the statement is about to overwrite. Suppressing narrowing for the whole
   *   call is conservative by construction — it can only WIDEN a result to nullable, never narrow
   *   one incorrectly — and loses nothing relative to before qual narrowing existed at all.
   */
  private fun analyzeViaTemporaryView(viewSql: String, applyQualNarrowing: Boolean = true): List<Boolean> {
    val viewName = "norm_nullability_${UUID.randomUUID().toString().replace("-", "")}"
    try {
      try {
        connection.createStatement().use { stmt ->
          stmt.execute("CREATE TEMPORARY VIEW $viewName AS $viewSql")
        }
      } catch (originalError: SQLException) {
        // Queries with duplicate column names (e.g., SELECT d.id, e.id FROM d JOIN e ...)
        // fail view creation. Retry with explicit column aliases to make names unique.
        val columnCount = try {
          connection.prepareStatement(viewSql).use { it.metaData?.columnCount ?: 0 }
        } catch (_: SQLException) {
          0
        }
        if (columnCount > 0) {
          val columnList = (1..columnCount).joinToString(", ") { "c$it" }
          try {
            connection.createStatement().use { stmt ->
              stmt.execute("CREATE TEMPORARY VIEW $viewName($columnList) AS $viewSql")
            }
          } catch (_: SQLException) {
            throw originalError
          }
        } else {
          throw originalError
        }
      }
      val nodeTree = connection.createStatement().use { stmt ->
        stmt.executeQuery(
          "SELECT rw.ev_action::text FROM pg_rewrite rw " +
            "JOIN pg_class c ON c.oid = rw.ev_class " +
            "WHERE c.relname = '$viewName' AND c.relkind = 'v' AND rw.ev_type = '1'",
        ).use { rs ->
          check(rs.next()) { "No rewrite rule found for temporary view $viewName" }
          rs.getString(1)
        }
      }
      val rangeTable = nodeTreeParser.parseRangeTable(nodeTree) // varno → relid (base tables only)
      // GROUP BY queries use an *GROUP* RTE (rtekind 9) whose target list VARs reference the group
      // entry varno instead of the base table varno directly. Resolve these back to their base table
      // column so isSourceColumnNotNull can check pg_attribute.attnotnull correctly.
      //
      // EXCEPTION: When GROUPING SETS, CUBE, or ROLLUP is used, GROUP BY columns can receive NULL
      // for rows where the column is not part of the current grouping set. PostgreSQL 18 enforces
      // this via a *GROUP* RTE (rtekind 9): target list VARs reference the GROUP RTE instead of the
      // base table, so parseRangeTable() can't find them and they fall back to nullable on their
      // own. PostgreSQL 16/17 have no GROUP RTE, so this exception skips GROUP RTE resolution
      // entirely; the actual nullability override for grouping keys (including EXPRESSION keys
      // such as `ROLLUP(lower(a))`, which never produce a bare {VAR } target-list entry to remap
      // here) is applied by NodeTreeNullabilityAnalyzer.extractColumnNullability via the
      // hasGroupingSets flag passed to buildAnalyzer below.
      val hasGroupingSets = nodeTreeParser.hasGroupingSets(nodeTree)
      val groupRteMap = if (hasGroupingSets) {
        emptyMap()
      } else {
        nodeTreeParser.parseGroupRteMap(nodeTree) // (groupVarno, attrPos) → (baseVarno, baseVarattno)
      }
      // For subquery RTEs (rtekind 1), the outer VAR's varno is not in rangeTable.
      // Resolve their nullability by recursively analyzing each subquery's target list.
      // The map is keyed by (varno, varattno) for direct lookup in isSourceColumnNotNull.
      val subqueryColumnNotNull = buildSubqueryColumnNotNull(nodeTree, applyQualNarrowing)
      val cteColumnNotNull = buildCteColumnNotNull(nodeTree, applyQualNarrowing)
      // GROUPING SETS/CUBE/ROLLUP null-extend grouping keys AFTER the WHERE clause has already
      // filtered rows, so a qual can never prove a grouped result column non-null. This matters
      // even for a NOT NULL base column, because null-extension overrides the base column's own
      // constraint. Suppress qual narrowing for the entire block rather than trying to map a
      // (possibly expression) grouping key back to its null-extended leaf Vars — that is more
      // machinery than this warrants, and a subtle mistake there would reintroduce an unsound
      // narrowing. This is conservative by construction: every non-key output column of a
      // grouping-sets query is an aggregate, so suppressing narrowing here costs nothing real.
      val qualNotNullVars = if (applyQualNarrowing && !hasGroupingSets) {
        NodeTreeNullabilityAnalyzer.qualProvenNonNullVars(nodeTree, isStrictFunction)
      } else {
        emptySet()
      }
      val analyzer = buildAnalyzer(hasGroupingSets = hasGroupingSets) { varno, varattno ->
        if (isProvenByQuals(qualNotNullVars, groupRteMap, varno, varattno)) {
          true
        } else {
          val relid = rangeTable[varno]
          if (relid != null) {
            isColumnNotNull(relid to varattno)
          } else {
            val baseVar = groupRteMap[varno to varattno]
            if (baseVar != null) {
              val baseRelid = rangeTable[baseVar.first]
              baseRelid != null && isColumnNotNull(baseRelid to baseVar.second)
            } else {
              subqueryColumnNotNull[varno to varattno] == true ||
                cteColumnNotNull[varno to varattno] == true
            }
          }
        }
      }
      return analyzer.extractColumnNullability(nodeTree)
    } finally {
      connection.createStatement().use { stmt ->
        stmt.execute("DROP VIEW IF EXISTS $viewName")
      }
    }
  }

  private fun isColumnNotNull(key: Pair<Int, Int>): Boolean =
    columnNotNullByRelidAndAttnum[key] == true || viewColumnNotNullByRelidAndAttnum[key] == true

  /**
   * Creates a [NodeTreeNullabilityAnalyzer] pre-configured with this loader's catalog lookups.
   *
   * All constructor arguments except [isSourceColumnNotNull] are identical across every call site
   * in this class. This method captures the common configuration so callers only need to supply
   * the source-column resolution strategy, which varies by context (outer query, CTE body,
   * subquery).
   */
  private fun buildAnalyzer(
    hasGroupingSets: Boolean = false,
    isSourceColumnNotNull: (varno: Int, varattno: Int) -> Boolean,
  ): NodeTreeNullabilityAnalyzer = NodeTreeNullabilityAnalyzer(
    isStrict = isStrictFunction,
    hasNonNullInitialValue = { oid -> aggregateHasNonNullInitialValue[oid] == true },
    isSourceColumnNotNull = isSourceColumnNotNull,
    isOuterJoinNullable = { nullingRelations -> nullingRelations.isNotEmpty() },
    isAlwaysNonNull = { oid -> oid in alwaysNonNullFunctionOids },
    isNeverNullForNonNullInput = { oid -> oid in neverNullForNonNullInputOids },
    isLagLeadWithDefault = { oid -> oid in lagLeadWithDefaultOids },
    isFoldableToConst = { oid -> oid in immutableFunctionOids },
    isNonNullIffFirstArgumentNonNull = { oid -> oid in nonNullIffFirstArgumentNonNullFunctionOids },
    hasGroupingSets = hasGroupingSets,
  )

  /**
   * Returns `true` when `(varno, varattno)` is proven non-null by the query's `WHERE` clause,
   * either directly or through the GROUP RTE remap (target-list `Var`s point at the GROUP RTE
   * when `hasGroupRTE`, while `WHERE`-clause `Var`s use base relation varnos).
   */
  private fun isProvenByQuals(
    qualNotNullVars: Set<Pair<Int, Int>>,
    groupRteMap: Map<Pair<Int, Int>, Pair<Int, Int>>,
    varno: Int,
    varattno: Int,
  ): Boolean = qualNotNullVars.contains(varno to varattno) ||
    groupRteMap[varno to varattno]?.let { qualNotNullVars.contains(it) } == true

  /**
   * Builds a map from `(varno, varattno)` to `true` for CTE result columns that are guaranteed
   * non-null. Analyzes each CTE body with a sub-analyzer using the CTE's own range table.
   *
   * @param applyQualNarrowing See [analyzeViaTemporaryView]'s parameter of the same name. Threaded
   *   through unchanged to every CTE body analyzed here.
   */
  private fun buildCteColumnNotNull(
    nodeTree: String,
    applyQualNarrowing: Boolean = true,
  ): Map<Pair<Int, Int>, Boolean> {
    val cteDefinitions = nodeTreeParser.parseCteList(nodeTree)
    if (cteDefinitions.isEmpty()) return emptyMap()
    val cteRteMap = nodeTreeParser.parseCteRangeTableEntries(nodeTree)
    if (cteRteMap.isEmpty()) return emptyMap()

    val resolvedCtes = mutableMapOf<String, List<Boolean>>()
    for (cte in cteDefinitions) {
      val nullabilities = analyzeCteBodyNullability(cte, resolvedCtes, applyQualNarrowing) ?: continue
      resolvedCtes[cte.name] = nullabilities
    }

    return buildMap {
      for ((varno, cteName) in cteRteMap) {
        val nullabilities = resolvedCtes[cteName] ?: continue
        nullabilities.forEachIndexed { columnIndex, nullable ->
          put(varno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  private fun analyzeCteBodyNullability(
    cte: NodeTreeCteDefinition,
    previouslyResolved: Map<String, List<Boolean>>,
    applyQualNarrowing: Boolean = true,
  ): List<Boolean>? {
    if (nodeTreeParser.hasSetOperations(cte.queryBlock)) {
      return analyzeSetOperationBranches(cte.queryBlock, previouslyResolved, cte.name, applyQualNarrowing)
    }
    val analyzer = buildCteBodyAnalyzer(cte.queryBlock, previouslyResolved, applyQualNarrowing)
    val result = analyzer.extractColumnNullability(cte.queryBlock)
    if (result.isNotEmpty()) return result
    // DML CTEs store RETURNING columns in :returningList, not :targetList.
    val returningEntries = nodeTreeParser.parseReturningList(cte.queryBlock)
    if (returningEntries.isEmpty()) return null
    return returningEntries
      .filter { !it.isJunk }
      .sortedBy { it.resultNumber }
      .map { entry -> !analyzer.isNonNull(entry.expression) }
  }

  /**
   * Analyzes a `UNION`/`UNION ALL`/`INTERSECT`/`EXCEPT` [queryBlock] branch-by-branch and combines
   * each branch's per-column nullability with OR: a column is nullable in the combined result if
   * ANY branch can produce `null` for it.
   *
   * A `WITH RECURSIVE` CTE's recursive term(s) reference the CTE by name ([cteName]) — resolved by
   * [buildCteBodyAnalyzer] via `previouslyResolved` — creating a genuine fixpoint problem: the
   * recursive term's own nullability depends on the CTE's OWN combined nullability, which this
   * function is what computes. PostgreSQL requires the FIRST branch (the seed/non-recursive term)
   * to never reference the CTE itself, so it alone is computed once, outside the loop, as a known
   * starting point. Every subsequent branch (there is always exactly one recursive term for a
   * `WITH RECURSIVE` CTE, but this handles a plain multi-branch `UNION` identically) is then
   * re-analyzed, feeding back the CURRENT combined result as [cteName]'s own nullability, and the
   * combined result is recomputed — repeated until a pass changes nothing.
   *
   * This converges because the per-column nullability lattice (`false` = NOT NULL, `true` =
   * nullable, ordered `false < true`) is monotone under this loop's own update rule: OR-combining
   * MORE branch results (now including a possibly-wider self-reference) can only ever ADD `true`
   * bits, never remove one. Starting from the seed's own (fixed, correct) nullability — the
   * narrowest value the CTE's self-reference could possibly have — and iterating a
   * monotone-widening step over a `columnCount`-bit lattice reaches its fixpoint in at most
   * `columnCount + 1` passes (each pass either flips at least one more bit `false → true`, or
   * changes nothing and the loop stops), which the `while` condition below checks directly rather
   * than trusting an iteration-count bound alone.
   *
   * @return `null` if [queryBlock] has no analyzable subquery branches at all, or if the seed
   *   branch itself could not be analyzed (an empty result — see [extractColumnNullability]'s
   *   contract). Otherwise, one nullability value per output column, in `SELECT` order.
   */
  private fun analyzeSetOperationBranches(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
    cteName: String,
    applyQualNarrowing: Boolean = true,
  ): List<Boolean>? {
    val subqueryBranches = nodeTreeParser.parseSubqueryRangeTable(queryBlock).values.toList()
    if (subqueryBranches.isEmpty()) return null

    // The seed (first) branch of a recursive CTE structurally cannot reference the CTE itself —
    // PostgreSQL rejects a self-reference in the non-recursive term — so its nullability never
    // depends on the fixpoint loop below and is computed exactly once.
    val seedBlock = subqueryBranches.first()
    val seedAnalyzer = buildCteBodyAnalyzer(seedBlock, previouslyResolved, applyQualNarrowing)
    val seedResult = seedAnalyzer.extractColumnNullability(seedBlock)
    if (seedResult.isEmpty()) return null

    val otherBranches = subqueryBranches.drop(1)
    if (otherBranches.isEmpty()) return seedResult

    // Bounded at columnCount + 1 passes — the maximum this monotone-widening loop can take to
    // reach its fixpoint (see this function's own KDoc for the termination argument) — rather
    // than trusting that argument alone to keep this loop from ever running forever.
    var combined = seedResult
    var previous: List<Boolean>?
    var remainingPasses = seedResult.size + 1
    do {
      previous = combined
      val resolvedWithSelfReference = previouslyResolved + (cteName to combined)
      val branchResults = mutableListOf(seedResult)
      for (branchBlock in otherBranches) {
        val branchAnalyzer = buildCteBodyAnalyzer(branchBlock, resolvedWithSelfReference, applyQualNarrowing)
        val result = branchAnalyzer.extractColumnNullability(branchBlock)
        if (result.isNotEmpty()) branchResults.add(result)
      }
      val columnCount = branchResults.maxOf { it.size }
      combined = (0 until columnCount).map { col -> branchResults.any { it.getOrElse(col) { true } } }
      remainingPasses--
    } while (combined != previous && remainingPasses > 0)
    return combined
  }

  /**
   * Builds a map from `(varno, varattno)` to `true` for CTE RTE columns that are non-null,
   * based on previously resolved CTE nullabilities.
   */
  private fun buildInnerCteNotNull(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
  ): Map<Pair<Int, Int>, Boolean> {
    val innerCteRtes = nodeTreeParser.parseCteRangeTableEntries(queryBlock)
    return buildMap {
      for ((varno, cteName) in innerCteRtes) {
        val nullabilities = previouslyResolved[cteName] ?: continue
        nullabilities.forEachIndexed { columnIndex, nullable ->
          put(varno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  /**
   * @param applyQualNarrowing See [analyzeViaTemporaryView]'s parameter of the same name.
   */
  private fun buildCteBodyAnalyzer(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
    applyQualNarrowing: Boolean = true,
  ): NodeTreeNullabilityAnalyzer {
    val cteRangeTable = nodeTreeParser.parseRangeTable(queryBlock)
    // See analyzeViaTemporaryView's identical guard: GROUPING SETS/CUBE/ROLLUP can null-extend a
    // grouping key even when the underlying base table column is NOT NULL, and even when the
    // WHERE clause proved it non-null before aggregation. The actual override (including
    // EXPRESSION grouping keys) is applied by extractColumnNullability via hasGroupingSets below.
    val hasGroupingSets = nodeTreeParser.hasGroupingSets(queryBlock)
    val groupRteMap = if (hasGroupingSets) {
      emptyMap()
    } else {
      nodeTreeParser.parseGroupRteMap(queryBlock)
    }
    val innerCteNotNull = buildInnerCteNotNull(queryBlock, previouslyResolved)
    val subqueryColumnNotNull = buildSubqueryColumnNotNull(queryBlock, applyQualNarrowing)
    // See analyzeViaTemporaryView's identical guard for why qual narrowing is suppressed whenever
    // hasGroupingSets: a grouping key is exactly the thing a GROUPING SETS/CUBE/ROLLUP query
    // null-extends after WHERE has already run.
    val qualNotNullVars = if (applyQualNarrowing && !hasGroupingSets) {
      NodeTreeNullabilityAnalyzer.qualProvenNonNullVars(queryBlock, isStrictFunction)
    } else {
      emptySet()
    }
    return buildAnalyzer(hasGroupingSets = hasGroupingSets) { varno, varattno ->
      if (isProvenByQuals(qualNotNullVars, groupRteMap, varno, varattno)) {
        true
      } else {
        val relid = cteRangeTable[varno]
        if (relid != null) {
          isColumnNotNull(relid to varattno)
        } else {
          val baseVar = groupRteMap[varno to varattno]
          if (baseVar != null) {
            val baseRelid = cteRangeTable[baseVar.first]
            baseRelid != null && isColumnNotNull(baseRelid to baseVar.second)
          } else {
            subqueryColumnNotNull[varno to varattno] == true ||
              innerCteNotNull[varno to varattno] == true
          }
        }
      }
    }
  }

  /**
   * Builds a map from `(varno, varattno)` to `true` for columns of subquery RTEs that are
   * guaranteed non-null.
   *
   * For each `rtekind 1` (subquery) entry in the outer query's `:rtable`, extracts the embedded
   * `:subquery {QUERY ...}` block, recursively analyzes it with a fresh [NodeTreeNullabilityAnalyzer]
   * using the **subquery's own range table**, and maps each output column position to its nullability.
   *
   * This allows the outer analyzer's [NodeTreeNullabilityAnalyzer] to correctly evaluate VARs that
   * reference a subquery derived table (`SELECT s.col FROM (...) s`) rather than a base table.
   * Without this, `isSourceColumnNotNull` for a subquery VAR would always return `false` (nullable)
   * because the subquery RTE has no `relid` in the outer range table.
   *
   * @param nodeTree the `pg_rewrite.ev_action` text of the outer query's temporary view
   * @param applyQualNarrowing See [analyzeViaTemporaryView]'s parameter of the same name.
   * @return A map from `(varno, varattno)` pairs to `true` when the subquery column is non-null
   */
  private fun buildSubqueryColumnNotNull(
    nodeTree: String,
    applyQualNarrowing: Boolean = true,
  ): Map<Pair<Int, Int>, Boolean> {
    // Set-operation queries (UNION ALL, INTERSECT, EXCEPT) store their branches as rtekind=1
    // subquery RTEs. Tracing through them would incorrectly report the first branch's nullability
    // as the result's nullability — the true result is the union across ALL branches, some of which
    // may introduce nulls (e.g., a branch with LEFT JOIN). Return empty so the analyzer conservatively
    // treats set-operation output columns as nullable (the correct safe default).
    if (nodeTreeParser.hasSetOperations(nodeTree)) return emptyMap()
    val subqueryRangeTable = nodeTreeParser.parseSubqueryRangeTable(nodeTree)
    if (subqueryRangeTable.isEmpty()) return emptyMap()
    return buildMap {
      for ((outerVarno, subqueryBlock) in subqueryRangeTable) {
        // Parse the subquery's own base-table range table for isSourceColumnNotNull.
        val subRangeTable = nodeTreeParser.parseRangeTable(subqueryBlock)
        // See analyzeViaTemporaryView's identical guard: GROUPING SETS/CUBE/ROLLUP can
        // null-extend a grouping key even when the base table column is NOT NULL. The actual
        // override (including EXPRESSION grouping keys) is applied by extractColumnNullability
        // via hasGroupingSets below.
        val hasGroupingSets = nodeTreeParser.hasGroupingSets(subqueryBlock)
        val groupRteMap = if (hasGroupingSets) {
          emptyMap()
        } else {
          nodeTreeParser.parseGroupRteMap(subqueryBlock)
        }
        // See analyzeViaTemporaryView's identical guard: suppress qual narrowing whenever
        // hasGroupingSets, because those are exactly what GROUPING SETS/CUBE/ROLLUP null-extends
        // after WHERE has already filtered rows.
        val subQualNotNullVars = if (applyQualNarrowing && !hasGroupingSets) {
          NodeTreeNullabilityAnalyzer.qualProvenNonNullVars(subqueryBlock, isStrictFunction)
        } else {
          emptySet()
        }
        val subAnalyzer = buildAnalyzer(hasGroupingSets = hasGroupingSets) { subVarno, subVarattno ->
          if (isProvenByQuals(subQualNotNullVars, groupRteMap, subVarno, subVarattno)) {
            true
          } else {
            val relid = subRangeTable[subVarno] ?: return@buildAnalyzer false
            isColumnNotNull(relid to subVarattno)
          }
        }
        val subNullabilities = subAnalyzer.extractColumnNullability(subqueryBlock)
        subNullabilities.forEachIndexed { columnIndex, nullable ->
          // columnIndex is 0-based; varattno is 1-based
          put(outerVarno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  /**
   * Transforms SQL containing data-modifying statements into an equivalent SELECT
   * that preserves the join structure for outer join nullability analysis.
   *
   * Two transformations are applied:
   * 1. **Data-modifying CTE bodies** (a leading `INSERT`, `UPDATE`, `DELETE`, `MERGE`, or a
   *    nested `WITH`) are converted to an equivalent join-preserving `SELECT` via
   *    [convertDmlCteBodyToSelect] wherever possible — the same transformation "Outer DML"
   *    (transformation 2, below) already uses for TOP-LEVEL DML — so the real FROM/USING/MERGE
   *    join structure sits in front of the node-tree analyzer that already computes outer-join
   *    nullability correctly, rather than approximating it. The conversion is then validated
   *    against the original body's own column metadata via [validatedConversion] before it is
   *    trusted (see its KDoc for why this check is necessary independently of how well
   *    [convertDmlCteBodyToSelect] itself is written). Only when conversion or validation fails
   *    does this fall back to a metadata PROBE — and there is more than one distinct reason that
   *    can happen, not just the shapes [convertDmlCteBodyToSelect] itself returns `null` for:
   *    - An intentional `null` from [convertDmlCteBodyToSelect]: a plain `INSERT` (whose
   *      `RETURNING` sees only the just-inserted row and has no join to preserve),
   *      `UPDATE`/`DELETE` with no `FROM`/`USING` clause, or `MERGE` with no `RETURNING` clause.
   *    - [validatedConversion] REJECTING a syntactically-built conversion that fails to prepare
   *      or doesn't reproduce the original's column signature — most notably a `RETURNING` that
   *      reads PostgreSQL 18's `OLD`/`NEW` pseudo-relations (not valid range variables outside
   *      `RETURNING`, so the converted plain `SELECT` fails to prepare) or a `MERGE`'s
   *      `merge_action()` (not valid outside `MERGE`'s own `RETURNING`, same failure mode).
   *    - A lexer limitation the scanners in `SqlUtils.kt` haven't yet been taught to handle.
   *    The stub is a `SELECT NULL::<type> AS <name> ... WHERE FALSE` built from
   *    `PreparedStatement.getMetaData()` on the original body. That probe reports base-table
   *    `attnotnull`, NOT real join-aware nullability — see [buildSelectStub]'s KDoc for exactly
   *    what it can and cannot see, and for the SAFETY NET (the `forceNullableColumn` predicate
   *    threaded through it) that now covers every danger sign this file can detect regardless of
   *    WHY conversion was rejected: a detectable outer join in an `UPDATE`/`DELETE`/`MERGE`
   *    body — but never `INSERT`, whose `RETURNING` cannot be null-extended by anything in its
   *    own source, see [isInsertBody]'s KDoc; a `RETURNING` that reads `OLD.`/`NEW.`, which
   *    applies regardless of statement kind, `INSERT` included (e.g. `INSERT ... ON CONFLICT DO
   *    UPDATE ... RETURNING OLD.col`, where `OLD` is `NULL` exactly when there was no conflict);
   *    a reference, by name, to a sibling CTE that is itself dangerous; or an `OLD`/`NEW`
   *    item-to-column mapping this file cannot confirm is reliable.
   *    **Non-data-modifying CTE bodies** (a leading `SELECT`, `TABLE`, or `VALUES`) are kept
   *    verbatim: they resolve their own references (including outer joins nested inside the
   *    body, whose induced nullability a stub built from base-table `attnotnull` cannot
   *    reproduce), and PostgreSQL only allows data-modifying statements directly under a
   *    top-level `WITH`, so a `SELECT`/`TABLE`/`VALUES` body cannot hide DML.
   * 2. **Outer DML** (`UPDATE ... FROM`, `DELETE ... USING`, `MERGE ... RETURNING`) is converted
   *    to an equivalent SELECT that preserves the join structure, via [convertDmlToSelect].
   *
   * A data-modifying CTE with no `RETURNING` clause (e.g. `INSERT ... ON CONFLICT DO NOTHING`)
   * has no result columns. PostgreSQL still rejects such a statement in `CREATE VIEW`, so its
   * body is replaced with a one-column stub (`SELECT NULL::int4 AS norm_stub WHERE FALSE`)
   * rather than being dropped entirely. PostgreSQL guarantees nothing outside the CTE can
   * reference a no-RETURNING data-modifying CTE, so the fabricated column is never read.
   *
   * @return The transformed SQL, or `null` if the DML has no join structure (or Phase 2's
   *   conversion could not be validated) — see [queryColumnNullability]'s KDoc for what the
   *   caller actually does with `null`: [analyzeUnconvertibleDml]'s result (real
   *   `ResultSetMetaData.isNullable` OR'd with [forcedNullabilityPredicate]), not an empty
   *   nullability list.
   */
  private fun transformForViewCreation(sql: String): String? {
    // Phase 1: Convert data-modifying CTE bodies to join-preserving SELECTs where possible,
    // falling back to a metadata probe/stub otherwise; keep SELECT/TABLE/VALUES bodies verbatim,
    // since their internal join structure (e.g. a LEFT JOIN inside the CTE) affects the
    // nullability of the CTE's own output columns and neither approach reproduces it as well as
    // just leaving the body alone.
    val cteClause = parseCteClause(sql)
    val result: String
    val mainQueryStart: Int
    if (cteClause != null) {
      // Build the result by concatenating segments: original text between CTE bodies stays,
      // CTE bodies are replaced with their conversion or a stub. This avoids index invalidation
      // from length changes. The full WITH clause (through the last CTE's closing ')') is one
      // candidate probe prefix for the stub fallback: it is the original statement with only the
      // main query swapped out, so every name that resolves in the original resolves in the
      // probe. Whether it is tried before or after the preceding-definitions-only prefix depends
      // on cteClause.isRecursive — see buildSelectStub's KDoc for why.
      val fullWithClausePrefix = sql.substring(0, cteClause.definitions.last().bodyCloseParenthesis + 1)
      val dangerousSiblingNames = computeDangerousSiblingNames(sql, cteClause.definitions)
      result = buildString {
        var lastEnd = 0
        for ((index, cte) in cteClause.definitions.withIndex()) {
          append(sql, lastEnd, cte.bodyOpenParenthesis + 1) // Include the opening '('
          val body = sql.substring(cte.bodyOpenParenthesis + 1, cte.bodyCloseParenthesis)
          if (isNonDataModifyingCteBody(body)) {
            append(body) // Keep verbatim — resolves its own references, cannot hide DML.
          } else {
            // Use the ORIGINAL sql text (not the partially-stubbed builder content) as the
            // prefix, so preceding CTEs are prepared exactly as the user wrote them.
            val precedingDefinitionsPrefix = if (index > 0) {
              sql.substring(0, cteClause.definitions[index - 1].bodyCloseParenthesis + 1)
            } else {
              ""
            }
            // Order matters: it must mirror PostgreSQL's own name-visibility rules, or a probe
            // could resolve a reference to the wrong thing (e.g. a later CTE shadowing a base
            // table) and fabricate incorrect nullability instead of merely failing to prepare.
            // No bare-body ("") candidate here: for index 0, precedingDefinitionsPrefix IS the
            // empty prefix (correct — there is nothing preceding), but for index > 0 a bare-body
            // probe has known-wrong scope, so it is never tried; buildSelectStub's own
            // true-scope fallback covers the case where both precision prefixes fail.
            val prefixes = if (cteClause.isRecursive) {
              listOf(fullWithClausePrefix, precedingDefinitionsPrefix)
            } else {
              listOf(precedingDefinitionsPrefix, fullWithClausePrefix)
            }.distinct()
            // Try the structural conversion FIRST, but never trust it blindly: validate it
            // against the original body's own column metadata (count, names, types) before
            // using it, since a converted SELECT that doesn't match is worse than no conversion
            // at all — it would reach CREATE VIEW as plausible-looking but wrong SQL. See
            // validatedConversion's KDoc for why this check exists independently of correctness
            // fixes made elsewhere (e.g. the lexer fix in SqlUtils.kt, or the MERGE column-order
            // fix): defense in depth against future or undiscovered conversion bugs.
            val convertedBody = convertDmlCteBodyToSelect(body)?.let {
              validatedConversion(prefixes, body, it, fullWithClausePrefix, cte.rawName)
            }
            val stubOrBody = convertedBody ?: run {
              // Last-resort safety net for the stub fallback: force specific columns (or, for the
              // whole-body triggers, every column) nullable rather than trust base-table
              // attnotnull (which cannot see any of these dangers). See buildSelectStub's and
              // tryPrepareStub's KDoc:
              // - A RETURNING item that reads OLD./NEW. (PostgreSQL 18, including via a
              //   RETURNING WITH (OLD AS alias, ...) declared alias) has its own conditional
              //   existence no join structure is needed to explain — this applies regardless of
              //   statement kind, INSERT included (e.g. INSERT ... ON CONFLICT DO UPDATE
              //   ... RETURNING OLD.col, where OLD is NULL exactly when there was no conflict).
              //   Forced PER COLUMN — see oldOrNewReturningColumns's KDoc — but ONLY once its
              //   assumed item count is cross-checked against the real column count from this
              //   probe's own metadata: a star item (`*`, `tbl.*`) this text-only analysis failed
              //   to recognize expands to more columns than items, silently shifting every
              //   later item's real index — trusting an index without this check was itself a
              //   regression (`RETURNING (tgt.*), OLD.tval AS oldv` forced the wrong column, the
              //   second half of `tgt.*`'s expansion, leaving the real OLD-referencing column on
              //   unchecked metadata). A mismatch forces every column, same as a RECOGNIZED star.
              // - A null-extending construct in body's own text (an outer join, a MERGE with WHEN
              //   NOT MATCHED BY SOURCE, or a grouping-set construct's supertotal row — see
              //   hasNullExtendingConstruct's KDoc) — but NEVER for a plain INSERT, whose
              //   RETURNING sees only the just-inserted row (confirmed against real PostgreSQL: a
              //   LEFT JOIN in an INSERT's own SELECT source does not make its RETURNING columns
              //   nullable), so INSERT bodies are excluded from this trigger specifically.
              // - body referencing, BY NAME, a sibling CTE that is dangerous — either directly (its
              //   own body has one of the same danger signs) or TRANSITIVELY (it in turn
              //   references a further sibling that is dangerous, computed as a fixpoint over the
              //   whole WITH clause by computeDangerousSiblingNames, once per statement, not once
              //   per CTE): that sibling's danger is invisible to any scan of body's own text (see
              //   transformForViewCreation's KDoc). A quoted reference to the sibling's name (e.g.
              //   USING "pre") is matched too — see referencesAnyName's KDoc. Scoped to siblings
              //   that are THEMSELVES dangerous — not every sibling reference — so an ordinary
              //   forward/backward reference to a plain, join-free sibling (e.g. `later AS
              //   (SELECT 'q'::TEXT AS lbl)`) does not lose its real NOT NULL columns to this
              //   safety net.
              val forceNullableColumn = forcedNullabilityPredicate(body, dangerousSiblingNames - cte.name)
              buildSelectStub(prefixes, body, fullWithClausePrefix, cte.rawName, forceNullableColumn) ?: body
            }
            append(stubOrBody)
          }
          lastEnd = cte.bodyCloseParenthesis // Will include the closing ')' next iteration
        }
        append(sql, lastEnd, sql.length)
      }
      // mainQueryStart is adjusted by the total length change from all replacements.
      mainQueryStart = cteClause.mainQueryStart + (result.length - sql.length)
    } else {
      result = sql
      mainQueryStart = 0
    }

    // Phase 2: If the outer statement is DML, convert to an equivalent SELECT — but, exactly like
    // a CTE body's conversion (Phase 1), only once validated against the ORIGINAL mainQuery's own
    // column signature. Without this gate, a conversion that embeds something invalid outside its
    // origin (MERGE's own RETURNING, e.g. `merge_action()`, or PostgreSQL 18's `OLD`/`NEW`) would
    // reach CREATE VIEW as plausible-looking but unpreparable SQL — and unlike a CTE body's own
    // rejection (which still has the stub/probe fallback to try), a rejected TOP-LEVEL conversion
    // simply falls through to the same "no join structure" `null` this function already returns
    // for INSERT/no-FROM/no-RETURNING shapes, which the caller turns into the all-non-nullable
    // fallback (see [queryColumnNullability]'s KDoc — that fallback ASSERTS every column NOT
    // NULL, the unsafe direction, not a safe deferral) — never a crash.
    val mainQuery = result.substring(mainQueryStart)
    val topLevelPrefix = result.substring(0, mainQueryStart)
    val selectEquivalent = convertDmlToSelect(mainQuery)?.let { converted ->
      val originalProbe = if (topLevelPrefix.isEmpty()) mainQuery else "$topLevelPrefix $mainQuery"
      tryGetColumnSignature(originalProbe)?.let { originalSignature ->
        acceptIfSignatureMatches(topLevelPrefix, converted, originalSignature)
      }
    }

    return if (selectEquivalent != null) {
      result.substring(0, mainQueryStart) + selectEquivalent
    } else if (cteClause != null && cteClause.definitions.isNotEmpty()) {
      // CTEs were replaced but outer statement is SELECT (or non-transformable DML).
      // If it's SELECT, the transformed SQL should work as a view.
      // If it's INSERT/MERGE (no join structure in RETURNING), return null.
      val afterCte = skipWhitespaceAndComments(mainQuery, 0)
      if (mainQuery.regionMatches(afterCte, "SELECT", 0, 6, ignoreCase = true) ||
        mainQuery.regionMatches(afterCte, "TABLE", 0, 5, ignoreCase = true) ||
        mainQuery.regionMatches(afterCte, "VALUES", 0, 6, ignoreCase = true)
      ) {
        result
      } else {
        null // Outer DML with no join structure (INSERT, MERGE)
      }
    } else {
      null // No CTEs, outer DML with no join structure
    }
  }

  /**
   * Computes the full, TRANSITIVE set of CTE names in [definitions] that are dangerous to another
   * sibling's stub-path safety net (see [buildSelectStub]'s KDoc on the sibling-CTE trigger) —
   * computed ONCE per `WITH` clause, not once per CTE, since the result is the same for every
   * sibling in the clause.
   *
   * Seeded with definitions whose OWN body is dangerous, from either of two INDEPENDENT reasons:
   * - It references `OLD`/`NEW` at all (via [oldOrNewReturningColumns], which already understands
   *   the `RETURNING WITH (OLD AS alias, ...)` prologue — not a bare [referencesOldOrNew] call,
   *   which would miss an aliased reference). This applies REGARDLESS of statement kind, `INSERT`
   *   included: an `INSERT`'s ordinary target-column `RETURNING` is authoritative against base
   *   table `attnotnull` (see [isInsertBody]'s KDoc), but `RETURNING OLD.col` is a DIFFERENT
   *   claim — `OLD`'s own conditional existence (`NULL` for a freshly inserted row with no prior
   *   conflict) is not something `attnotnull` can see, `INSERT` or not. Confirmed against real
   *   PostgreSQL (issue #208 P1 follow-up): `WITH ins AS (INSERT INTO it2(id, val) SELECT a.id,
   *   'v' FROM a LEFT JOIN b ON b.id = a.id ON CONFLICT (id) DO UPDATE SET val = 'w' RETURNING id,
   *   OLD.val AS oldval), m AS (MERGE INTO tgt USING ins ON ins.id = tgt.id WHEN MATCHED THEN
   *   UPDATE SET tval = 'z' RETURNING merge_action() AS act, ins.oldval AS ov, tgt.id) SELECT *
   *   FROM m` returns `ov = NULL` for a fresh insert (no prior conflict row) — an earlier version
   *   of this seed, which excluded EVERY `INSERT` body regardless of why it was dangerous, missed
   *   this and fabricated `ov` NOT NULL.
   * - [hasNullExtendingConstruct] finds a detectable outer join, `WHEN NOT MATCHED BY SOURCE`, or
   *   grouping-set construct in its OWN body — but ONLY for a non-`INSERT` body: an `INSERT`'s
   *   `RETURNING` of an ordinary target-table column can only ever see the just-inserted row
   *   (see [isInsertBody]'s KDoc), so a join in the `INSERT`'s own `SELECT` source cannot
   *   null-extend it. Seeding on that join anyway would propagate a false alarm down every
   *   sibling that references the `INSERT`.
   *
   * Propagation (below) does NOT apply the `INSERT` exclusion from the second bullet — this is a
   * deliberate over-approximation, mirroring the own-body trigger's own unconditional
   * sibling-reference arm (`referencesAnyName(body, dangerousSiblingNames - cte.name)`, which
   * fires for an `INSERT` body exactly like any other statement kind), not a claim that every
   * `INSERT` referencing a dangerous sibling is genuinely exposed to its null-extension.
   * Confirmed against real PostgreSQL that it is NOT always exposed: `WITH j AS (SELECT a.id,
   * b.bval FROM a LEFT JOIN b ON b.id = a.id), ins AS (INSERT INTO ins_target(id, val) SELECT
   * j.id, 'v' FROM j RETURNING id, val), m AS (MERGE INTO tgt USING ins ON ins.id = tgt.id WHEN
   * MATCHED THEN UPDATE SET tval = 'z' RETURNING merge_action(), ins.val AS mv, tgt.id) SELECT *
   * FROM m` returns `mv = 'v'` — a literal, never touched by `j`'s join — yet this is reported
   * nullable, because `ins` joins the dangerous set via propagation (it references `j`) and `m`
   * then references `ins`. The cost is specifically that the `INSERT` precision guard (seeding on
   * `!isInsertBody`, see the seed bullets above) is NOT recovered once the dangerous source is a
   * referenced CTE rather than a join inline in the `INSERT`'s own text — same safe-direction
   * tradeoff every other over-approximation in this file makes.
   *
   * Then propagated to a FIXPOINT: any definition (of any statement kind) that
   * [referencesAnyName] a name already in the dangerous set joins it, and this repeats until a
   * full pass over [definitions] adds nothing. This is what makes a multi-hop chain like `j` (has
   * the join) -> `mid` (references only `j`, no join of its own) -> `m` (references only `mid`,
   * never `j` directly) detected: `mid` joins the dangerous set on the first pass (it references
   * `j`), then `m` joins on the second pass (it references `mid`, now dangerous) — a one-level
   * check stops after the first hop and never reaches `m`. Confirmed against real PostgreSQL
   * (issue #208, Gap 3): `m`'s `bval`, passed through `mid` from `j`'s own `LEFT JOIN`, is
   * genuinely `NULL` for the row whose join found no match.
   *
   * The fixpoint is inherently cycle-safe without a separate visited set: adding a name is
   * monotonic (the set only grows) and a pass that adds nothing terminates the loop, so a cycle
   * (`x` references `y`, `y` references `x`) can add at most every name in [definitions] before a
   * pass finds nothing left to add.
   */
  private fun computeDangerousSiblingNames(sql: String, definitions: List<CteDefinition>): Set<String> {
    fun bodyOf(definition: CteDefinition) =
      sql.substring(definition.bodyOpenParenthesis + 1, definition.bodyCloseParenthesis)

    // null forcedColumns means a recognized star item coincides with an OLD/NEW reference (danger
    // regardless of index); a non-null, non-empty set means specific items reference OLD/NEW; an
    // empty set means no OLD/NEW reference at all. Either of the first two counts as "references
    // OLD/NEW" for seeding purposes here — which SPECIFIC column doesn't matter yet, only whether
    // the body has this danger sign at all.
    fun referencesOldOrNewAnywhere(body: String) =
      oldOrNewReturningColumns(body).forcedColumns.let { it == null || it.isNotEmpty() }

    val dangerous = definitions
      .filter { definition ->
        val body = bodyOf(definition)
        referencesOldOrNewAnywhere(body) || (!isInsertBody(body) && hasNullExtendingConstruct(body))
      }
      .mapTo(mutableSetOf()) { it.name }

    var addedDuringLastPass = true
    while (addedDuringLastPass) {
      addedDuringLastPass = false
      for (definition in definitions) {
        if (definition.name in dangerous) continue
        if (referencesAnyName(bodyOf(definition), dangerous)) {
          dangerous.add(definition.name)
          addedDuringLastPass = true
        }
      }
    }
    return dangerous
  }

  /**
   * Checks whether a CTE [body] is non-data-modifying — i.e. it starts with `SELECT`, `TABLE`,
   * or `VALUES` (skipping leading whitespace, comments, and any leading `(` — a body may be
   * parenthesized, e.g. `((SELECT ...))` or `(SELECT ...) UNION ALL (SELECT ...)`). Such bodies
   * must be kept verbatim rather than replaced with a stub, since PostgreSQL only allows
   * data-modifying statements directly under a top-level `WITH` — never parenthesized — so a
   * leading `(` can only wrap a `SELECT`, and a `SELECT`/`TABLE`/`VALUES` body cannot itself
   * contain `INSERT`/`UPDATE`/`DELETE`/`MERGE`. Skipping leading parens therefore cannot
   * misclassify a data-modifying body as non-data-modifying.
   *
   * A body may instead start with its OWN nested `WITH` clause (e.g. `WITH inner_cte AS (SELECT
   * 1) SELECT ...`). Text-only keyword matching cannot see past that nested clause, so this
   * strips it (via [stripLeadingNestedWithClause]) and classifies the nested clause's own main
   * statement instead — looping, since that main statement can itself start with a further
   * nested `WITH`. This is safe because PostgreSQL only permits a data-modifying statement
   * directly under a TOP-level `WITH`; a nested `WITH`'s main statement is never itself
   * data-modifying, so whatever it resolves to (`SELECT`/`TABLE`/`VALUES`, or — for a body like
   * `WITH helper AS (...) UPDATE ... RETURNING ...` — the `UPDATE`/`DELETE`/`MERGE` itself)
   * correctly determines the whole body's classification. The loop terminates when stripping
   * makes no further progress (no leading `WITH` left to strip), at which point a remaining
   * `SELECT`/`TABLE`/`VALUES` classifies as non-data-modifying and anything else (a
   * data-modifying statement, or unparseable text) classifies as data-modifying.
   *
   * @param body A SQL statement (the CTE body text, without surrounding parentheses).
   */
  private fun isNonDataModifyingCteBody(body: String): Boolean {
    var remainder = body
    while (true) {
      var position = skipWhitespaceAndComments(remainder, 0)
      while (position < remainder.length && remainder[position] == '(') {
        position = skipWhitespaceAndComments(remainder, position + 1)
      }
      if (remainder.regionMatches(position, "SELECT", 0, 6, ignoreCase = true) ||
        remainder.regionMatches(position, "TABLE", 0, 5, ignoreCase = true) ||
        remainder.regionMatches(position, "VALUES", 0, 6, ignoreCase = true)
      ) {
        return true
      }
      if (!remainder.regionMatches(position, "WITH", 0, 4, ignoreCase = true)) {
        return false
      }
      val nestedMainStatement = remainder.substring(position)
      val stripped = stripLeadingNestedWithClause(nestedMainStatement)
      if (stripped == nestedMainStatement) {
        return false
      }
      remainder = stripped
    }
  }

  /**
   * Converts a data-modifying CTE [body] into an equivalent join-preserving `SELECT`, via
   * [convertDmlToSelect] — reusing the exact transformation that already gives correct
   * outer-join nullability for TOP-LEVEL `UPDATE ... FROM`/`DELETE ... USING`/`MERGE ...
   * RETURNING` (see [transformForViewCreation]). Converting the body keeps its true join
   * structure in front of the node-tree analyzer used elsewhere in this file, instead of asking
   * `PreparedStatement.getMetaData()` a question it structurally cannot answer: base-table
   * `attnotnull` says nothing about whether an outer join null-extends that column at runtime.
   * [buildSelectStub]'s probe/stub fallback (used when this returns `null`) has exactly that
   * blind spot — see its KDoc.
   *
   * If [body] carries its OWN leading nested `WITH` clause (e.g. `WITH helper AS (...) UPDATE
   * ...`), [convertDmlToSelect] is applied to the DML statement AFTER that nested clause, and
   * the nested clause is reattached verbatim in front of the converted `SELECT`.
   * `convertDmlToSelect` only recognizes a statement starting with `UPDATE`/`DELETE`/`MERGE`, so
   * without this a nested `WITH` would make every such body look unrecognized and silently fall
   * back to the probe/stub chain instead — the exact shape the true-scope probe in
   * [buildSelectStub] exists to cover, which resolves NAMES correctly for that shape but not
   * outer-join NULLABILITY.
   *
   * @return The converted SELECT (with any nested `WITH` clause reattached), or `null` if
   *   [body] has no join structure [convertDmlToSelect] can convert — a plain `INSERT` (whose
   *   `RETURNING` sees only the just-inserted row: no join can null-extend it),
   *   `UPDATE`/`DELETE` without a `FROM`/`USING` clause, or `MERGE` without a `RETURNING`
   *   clause — or if the nested `WITH` clause itself fails to parse.
   */
  private fun convertDmlCteBodyToSelect(body: String): String? {
    val nestedCteClause = parseCteClause(body) ?: return convertDmlToSelect(body)
    val nestedWithClausePrefix = body.substring(0, nestedCteClause.definitions.last().bodyCloseParenthesis + 1)
    val innerDml = body.substring(nestedCteClause.mainQueryStart)
    return convertDmlToSelect(innerDml)?.let { "$nestedWithClausePrefix $it" }
  }

  /**
   * A CTE body or converted SELECT's result-column shape, for comparing a structural conversion
   * against the DML statement it claims to replace. Deliberately excludes nullability: the whole
   * point of [validatedConversion] is to check the conversion BEFORE trusting its nullability —
   * comparing nullability here would be circular.
   */
  private data class ColumnSignature(val names: List<String>, val typeNames: List<String>)

  /**
   * Prepares [probeSql] and, if it succeeds, captures its result columns' names and type names
   * (in order) via `PreparedStatement.getMetaData()`.
   *
   * @return The signature, or `null` if [probeSql] could not be prepared.
   */
  private fun tryGetColumnSignature(probeSql: String): ColumnSignature? = try {
    connection.prepareStatement(probeSql).use { preparedStatement ->
      val metadata = preparedStatement.metaData
      if (metadata == null) {
        ColumnSignature(emptyList(), emptyList())
      } else {
        ColumnSignature(
          names = (1..metadata.columnCount).map { metadata.getColumnName(it) },
          typeNames = (1..metadata.columnCount).map { metadata.getColumnTypeName(it) },
        )
      }
    }
  } catch (_: SQLException) {
    null
  }

  /**
   * Confirms that [convertedBody] — [convertDmlCteBodyToSelect]'s output for [originalBody] —
   * has the SAME result columns, in the SAME order, as [originalBody] itself, before trusting it
   * enough to replace [originalBody] in the SQL sent to `CREATE VIEW`.
   *
   * This check exists independently of any particular conversion bug: string-based SQL
   * transformation (this file, and [convertDmlToSelect] in `SqlUtils.kt`) can never be proven
   * exhaustively correct for every input shape, so a converted SELECT that doesn't reproduce the
   * original's real column set — wrong count, wrong names, or wrong types — must be discarded
   * rather than reaching `CREATE VIEW` as plausible-looking but wrong SQL (silently fabricating
   * nullability, or — worse — aborting generation on SQL that PostgreSQL itself accepts fine, a
   * real regression this check specifically guards against). It is NOT sufficient on its own to
   * catch every conversion bug: if the target and source relations happen to share column names
   * and types in a way that makes a transposed order indistinguishable (e.g. both sides project
   * `id, val`), this check cannot tell a correct conversion from one with the columns in the
   * wrong order — the conversion itself must independently get column order right (see
   * `convertMergeToSelect` in `SqlUtils.kt` for exactly this case with MERGE's source/target
   * column ordering).
   *
   * [originalBody]'s signature is resolved via the same prefix chain [buildSelectStub] uses:
   * [precisionPrefixes] each as `<prefix> <originalBody>`, then — if [originalBody] carries its
   * own nested `WITH` clause, making every precision-prefix candidate a syntax error — a
   * true-scope probe (`<fullWithClausePrefix> SELECT * FROM <rawName>`). [convertedBody]'s
   * signature is resolved using THAT SAME successful prefix, wrapping it as a derived table
   * (`<prefix> SELECT * FROM (<convertedBody>) AS ...`) so it can be probed regardless of
   * whether [convertedBody] itself starts with a reattached nested `WITH` (a parenthesized
   * subquery may legally start with `WITH`; a bare statement may not have two).
   *
   * A `RETURNING OLD.col`/`NEW.col` reference (PostgreSQL 18) is one shape this catches
   * incidentally: [convertDmlToSelect] splices it into a plain `SELECT`, where `OLD`/`NEW` are
   * not valid range variables, so the converted probe fails to prepare and the conversion is
   * discarded here — falling back to the probe/stub path, which has the same known blind spot
   * to `OLD`/`NEW` (see [transformForViewCreation]'s KDoc) but at least doesn't abort outright.
   *
   * @return [convertedBody] if validated, or `null` if it could not be validated (discard the
   *   conversion and use the probe/stub fallback instead).
   */
  private fun validatedConversion(
    precisionPrefixes: List<String>,
    originalBody: String,
    convertedBody: String,
    fullWithClausePrefix: String,
    rawName: String,
  ): String? {
    for (prefix in precisionPrefixes) {
      val originalProbe = if (prefix.isEmpty()) originalBody else "$prefix $originalBody"
      val originalSignature = tryGetColumnSignature(originalProbe) ?: continue
      return acceptIfSignatureMatches(prefix, convertedBody, originalSignature)
    }
    // Every precision prefix failed to prepare the ORIGINAL body — the only way that happens is
    // a body with its own nested WITH clause (see buildSelectStub's KDoc). Resolve its real
    // signature via the same true-scope probe buildSelectStub falls back to in that case.
    val trueScopeSignature = tryGetColumnSignature("$fullWithClausePrefix SELECT * FROM $rawName") ?: return null
    return acceptIfSignatureMatches(fullWithClausePrefix, convertedBody, trueScopeSignature)
  }

  /**
   * Prepares [convertedBody] as a derived table under [prefix] and returns [convertedBody] if
   * its column signature matches [originalSignature] exactly (same names, same types, same
   * order) — or `null` if it fails to prepare, or its signature doesn't match.
   */
  private fun acceptIfSignatureMatches(
    prefix: String,
    convertedBody: String,
    originalSignature: ColumnSignature,
  ): String? {
    val convertedProbe = buildString {
      if (prefix.isNotEmpty()) append(prefix).append(' ')
      append("SELECT * FROM (").append(convertedBody).append(") AS norm_conversion_check")
    }
    val convertedSignature = tryGetColumnSignature(convertedProbe)
    return if (convertedSignature == originalSignature) convertedBody else null
  }

  /**
   * Builds a SELECT stub that produces the same result columns as the given SQL body, using
   * PostgreSQL's own parser to determine column metadata via `PreparedStatement.getMetaData()`.
   *
   * NOT NULL columns use non-null sentinels (e.g., `0::int4`) so that CTE body analysis correctly
   * identifies them as non-null. Nullable columns use `NULL::type` so they evaluate as nullable.
   * The `WHERE FALSE` ensures no rows are returned (the stub is for type/nullability metadata only).
   *
   * FUNDAMENTAL LIMITATION — read before adding a new caller: `ResultSetMetaData.isNullable` is
   * PostgreSQL's ORIGIN tracking for the projected column (ultimately, base-table
   * `pg_attribute.attnotnull`). It has no idea whether an outer join upstream of that column can
   * null-extend it at runtime, nor whether the column is one of PostgreSQL 18's `OLD`/`NEW`
   * pseudo-relations, whose own existence is conditional. A column that is genuinely `NOT NULL`
   * in its source table, but is read through a `LEFT JOIN` that produced no matching row, is
   * reported here as NOT NULL — this is unsafe, a real fabricated-NOT-NULL defect, not a
   * theoretical one (confirmed against running PostgreSQL: `UPDATE t SET ... FROM a LEFT JOIN b
   * ON ... RETURNING b.val` inside a CTE reported `NOT NULL` for a column real PostgreSQL
   * returns `NULL` for). This function is intended to be reached ONLY when `body` provably has
   * no danger of either kind: [transformForViewCreation] tries [convertDmlCteBodyToSelect] first
   * (which converts every body that HAS a `FROM`/`USING`/`MERGE` join into a real `SELECT`
   * handled by the join-aware node-tree analyzer instead) and validates that conversion via
   * [validatedConversion] before trusting it — see [transformForViewCreation]'s KDoc for the
   * full enumeration of reasons conversion or validation can fail (INSERT/no-FROM/no-RETURNING
   * shapes, a rejected `OLD`/`NEW`/`merge_action()` conversion, or a lexer limitation). That
   * intent is enforced by the CALLERS' current logic, not by anything provable about arbitrary
   * input — a future conversion bug, or a body shape neither the converter nor the validator
   * recognizes, could still land a genuinely dangerous body here.
   *
   * As a LAST-RESORT SAFETY NET for exactly that "could still land here" case, the caller builds
   * [forceNullableColumn] (see [tryPrepareStub]'s parameter KDoc) to force specific columns
   * nullable — or, for the whole-body danger signs below, every column — whenever a danger sign
   * is detected in `body`. Whole-body over-approximation is deliberate for these: identifying
   * precisely which columns a join affects isn't possible from metadata alone (see
   * [tryPrepareStub]'s KDoc):
   * - A detectable null-extending construct in `body`'s own text ([hasNullExtendingConstruct]: a
   *   `LEFT`/`RIGHT`/`FULL JOIN` at ANY nesting depth, a `MERGE` with `WHEN NOT MATCHED BY
   *   SOURCE`, or a grouping-set construct — `ROLLUP`, `CUBE`, `GROUPING SETS` — whose supertotal
   *   row makes a grouped column `NULL` by definition) — but NEVER for a plain `INSERT`: confirmed
   *   against running PostgreSQL, `INSERT INTO b(id, bval) SELECT a.id, 'v' FROM a LEFT JOIN b2 ON
   *   b2.id = a.id RETURNING id, bval` returns non-null values for both columns despite the `LEFT
   *   JOIN` in its own source, because an `INSERT`'s `RETURNING` can only ever see the row that
   *   was (or wasn't) actually inserted — see [isInsertBody]'s KDoc. Forcing nullability here for
   *   an `INSERT` was ITSELF a real over-nullability regression this exclusion fixes: it broke
   *   generated code that legitimately relied on a non-null type.
   * - `body` referencing, BY NAME, another CTE in the same `WITH` clause (a sibling) that is
   *   dangerous — either directly (its own body has one of the constructs above) or TRANSITIVELY
   *   (it in turn references a further sibling that is dangerous, any number of hops away): that
   *   danger can null-extend a column this body's `RETURNING` merely passes through, and no scan
   *   of `body`'s own text can see it — the null-extension lives entirely outside it. The
   *   transitive set is computed once per `WITH` clause as a fixpoint — see
   *   `computeDangerousSiblingNames`'s KDoc — so a chain like `j` (has the join) -> `mid`
   *   (references only `j`) -> `m` (references only `mid`) is followed all the way through, not
   *   just one hop. The reference itself may be UNQUOTED or double-quoted (`USING "pre"` matches
   *   exactly like `USING pre` — see [referencesAnyName]'s KDoc). Confirmed against running
   *   PostgreSQL: `WITH pre AS (SELECT a.id, b.bval FROM a LEFT JOIN b ON b.id = a.id), m AS
   *   (MERGE INTO tgt USING pre ON pre.id = tgt.id WHEN MATCHED THEN UPDATE SET tval = 'z'
   *   RETURNING merge_action(), pre.bval, tgt.id) SELECT * FROM m` returns `bval = NULL` for a
   *   target row whose matching `pre` row came from `b`'s nullable side of the join — invisible to
   *   `m`'s own text, which has no join at all. Scoping this to siblings that are themselves
   *   dangerous — rather than firing for ANY sibling reference at all — matters: an ordinary
   *   forward reference to a plain, join-free sibling (e.g. `later AS (SELECT 'q'::TEXT AS lbl)`,
   *   used purely to supply a literal value) must not lose its own real NOT NULL columns to this
   *   safety net.
   *
   * A `RETURNING` that reads `OLD.`/`NEW.` (PostgreSQL 18) — this one DOES apply to `INSERT` too
   * (e.g. `INSERT ... ON CONFLICT DO UPDATE ... RETURNING OLD.col`, where `OLD` is `NULL` exactly
   * when there was no conflict), independently of whether any join is present — is instead forced
   * PER COLUMN: see [oldOrNewReturningColumns]'s KDoc for why whole-body forcing here was itself
   * a regression (`RETURNING OLD.name, t.id` fabricated `t.id` nullable despite `t.id` never
   * being touched by `OLD`/`NEW` at all).
   *
   * RESIDUAL IMPRECISION, accepted rather than solved: for `MERGE`, the whole-body triggers still
   * demote TARGET columns that are actually always present (e.g. the target's primary key)
   * whenever a `MERGE`'s conversion is rejected AND one of them fires — the stub has no way to
   * isolate which returned columns come from the join's/sibling's null-extended side from which
   * come from the always-present target row. Per-column isolation was considered and rejected
   * (twice) for the outer-join case specifically: `ResultSetMetaData.getTableName` reports the
   * base RELATION, not the alias used in the query, so a self-join makes the join side and the
   * target side indistinguishable by name alone anyway. Over-nullable is the safe direction, so
   * this is left as-is.
   *
   * NOT CLAIMED: a body whose null-extension originates entirely outside it — in a base table's
   * own view definition, a function it calls, or anything else this file cannot see — is not
   * caught by any of the above and may still be reported NOT NULL. Nothing in this file claims to
   * enumerate every way nullability can leak into a body from outside its own text — only the
   * shapes verified against real PostgreSQL and listed here. In particular,
   * [hasNullExtendingConstruct] only recognizes an outer join, `WHEN NOT MATCHED BY SOURCE`, and a
   * grouping-set construct as reasons a `MERGE`'s matched source row can be conditionally
   * absent-valued — this is the RESIDUAL part of issue #208's Gap 2 that remains unfixed: a
   * `MERGE` whose matched source row is conditionally absent-valued for some OTHER reason (an
   * arbitrary `ON` condition — such as `ON tgt.id = COALESCE(s.id, 2)` — sitting over some other
   * NULL-producing source shape that isn't an outer join, `WHEN NOT MATCHED BY SOURCE`, or a
   * grouping set) is still not recognized, and its `RETURNING` columns may still be fabricated NOT
   * NULL.
   *
   * EVERYTHING ABOVE IN THIS KDOC WAS ONCE SCOPED ONLY TO THIS FUNCTION'S CALLERS — the CTE-body
   * path (Phase 1 of `transformForViewCreation`) — with the TOP-LEVEL (non-CTE) DML path (Phase 2)
   * having no equivalent safety net at all: `queryColumnNullability`'s fallback for a Phase-2
   * conversion that failed or was rejected used to assert EVERY column NOT NULL, unconditionally,
   * discarding the real `ResultSetMetaData.isNullable` it had already read. Issue #207 fixed this:
   * `analyzeUnconvertibleDml` now applies the SAME [forcedNullabilityPredicate] this function
   * builds for a CTE body — built instead from the top-level statement's own main-query text and
   * whatever dangerous CTE names its own `WITH` clause declares — OR'd with real
   * `ResultSetMetaData.isNullable`, rather than discarding both. [hasWhenNotMatchedBySourceClause]
   * is DIFFERENT from this shared safety net: it IS consulted for a top-level `MERGE`, via
   * `convertMergeToSelect` (called from `convertDmlToSelect`, which Phase 2 calls directly) — not
   * as a fallback trigger, but as part of the conversion itself, to choose `RIGHT JOIN` over a
   * plain `JOIN` when modeling the `MERGE`'s shape. When that conversion succeeds and validates, a
   * top-level `WHEN NOT MATCHED BY SOURCE` `MERGE` is analyzed ACCURATELY by the join-aware
   * node-tree analyzer, not approximated.
   *
   * Five shapes confirmed against real PostgreSQL to have fabricated NOT NULL before issue #207's
   * fix, all now correctly reported nullable via `analyzeUnconvertibleDml`'s shared safety net
   * (see `QueryAnalysisTest.DmlReturning` for the regression tests):
   * - `MERGE INTO tgt USING a ON a.id = tgt.id WHEN NOT MATCHED THEN INSERT (id, tval) VALUES
   *   (a.id, a.aval) RETURNING OLD.tval AS oldv` — a freshly `INSERT`ed row via `MERGE` has no
   *   prior row, so `OLD.tval` is genuinely `NULL`; now forced nullable by the `OLD`/`NEW`
   *   per-column trigger, regardless of statement kind.
   * - `MERGE INTO tgt USING a ON a.id = tgt.id WHEN MATCHED THEN DELETE RETURNING NEW.tval` — a
   *   deleted row has no resulting row, so `NEW.tval` is genuinely `NULL`; same trigger.
   * - `INSERT INTO tgt VALUES (99, 'x') ON CONFLICT (id) DO UPDATE SET tval = 'y' RETURNING
   *   OLD.tval` (top-level, no CTE) — a fresh insert (no conflict) has no prior row; same trigger.
   * - `MERGE INTO tgt USING (SELECT a.id, b.bval FROM a LEFT JOIN b ON b.id = a.id) s ON s.id =
   *   tgt.id WHEN MATCHED THEN UPDATE SET tval = 'z' RETURNING merge_action() AS act, s.bval` —
   *   `b`'s `LEFT JOIN` genuinely null-extends `s.bval`; now forced nullable by the
   *   null-extending-construct trigger (which — same as the CTE-body case — forces EVERY column,
   *   `act` included, not just `bval`; see this KDoc's own over-approximation notes above).
   * - The everyday, no-`OLD`/`NEW`/`MERGE` case: a plain `DELETE FROM t WHERE id = ? RETURNING id,
   *   name, note` or `UPDATE ... RETURNING ..., docn`, where `note`/`docn` is a genuinely nullable
   *   column — a plain `DELETE`/`UPDATE` with no `FROM`/`USING` clause has no join structure for
   *   [convertDmlToSelect] to convert in the first place (see its KDoc), so it goes straight to
   *   `analyzeUnconvertibleDml`, which now consults `ResultSetMetaData.isNullable` instead of
   *   discarding it. This is the single most likely of these five shapes to affect a real user: it
   *   requires no `MERGE`, no PostgreSQL 18, and no CTE avoidance — just an ordinary top-level
   *   `DELETE`/`UPDATE ... RETURNING` of a nullable column.
   *
   * The residual gap noted above ([hasNullExtendingConstruct] missing an arbitrary `ON` condition
   * over some other NULL-producing source shape) applies equally to `analyzeUnconvertibleDml`'s
   * top-level use of the same predicate — this fix closes the "no safety net at all" gap, not
   * every gap in what the safety net itself can detect.
   *
   * [precisionPrefixes] are tried first, in order, each as `prefix + " " + body` (or `body`
   * alone for an empty prefix — correct precisely when `body` is the first CTE, since then there
   * is nothing preceding to include). This is needed because `body` may reference another CTE in
   * the same `WITH` clause by name — including, under `WITH RECURSIVE`, one declared LATER in
   * the clause — and a prefix that omits that CTE fails to prepare. A prefix that fails to
   * prepare (e.g. it references a name not yet in scope) throws `SQLException`, which is caught
   * so the next candidate is tried; a prefix that prepares successfully but yields no result
   * columns is a SUCCESS (see below), not a failure.
   *
   * Order among [precisionPrefixes] is significant, not just a performance tuning choice: it
   * must mirror which names PostgreSQL actually makes visible to `body` in the real
   * (untransformed) statement, or a probe could silently resolve a reference to the wrong thing.
   * The full `WITH` clause prefix resolves every name visible under `WITH RECURSIVE`, including
   * forward references — but under a plain `WITH`, it also makes visible CTEs declared AFTER
   * `body` that the real statement does NOT see. If one of those later CTEs happens to share a
   * name with a base table or outer reference `body` uses, trying the full clause first would
   * resolve that reference to the later CTE instead — preparing successfully, but against the
   * wrong table, fabricating incorrect nullability rather than merely failing. Callers therefore
   * order [precisionPrefixes] to match `cteClause.isRecursive`: preceding-definitions-only first
   * for a plain `WITH`, full clause first for `WITH RECURSIVE` (where it is the only prefix that
   * can resolve a genuine forward reference).
   *
   * If `body` itself carries a nested `WITH` (e.g. `ins AS (WITH helper AS (...) INSERT ...
   * RETURNING ...)`), EVERY [precisionPrefixes] candidate is a syntax error — `<prefix> <body>`
   * becomes `WITH ... WITH helper AS (...) INSERT ...`, and PostgreSQL does not allow a second
   * top-level `WITH`. Splicing `body`'s nested `WITH` into the prefix instead of probing it as a
   * body was considered and rejected: promoting a body's own `WITH RECURSIVE` into the prefix
   * would change a plain sibling's semantics (e.g. a sibling `src AS (SELECT ... FROM src)`
   * referencing the base table `src` would become a recursive self-reference). Instead, once
   * every [precisionPrefixes] candidate fails, this falls back to a TRUE-SCOPE probe: [rawName]
   * is looked up through the unmodified [fullWithClausePrefix] — `<fullWithClausePrefix> SELECT
   * * FROM <rawName>` — keeping `body`'s own nested `WITH` exactly where PostgreSQL expects it.
   * Data-modifying CTEs are legal there (only `CREATE VIEW` rejects them), `getMetaData()` is
   * Describe-only so nothing executes (the same reliance the precision prefixes already have on
   * unmodified sibling DML bodies), and name resolution is byte-identical to the real statement
   * — including nested `WITH`, `RECURSIVE`, shadowing, and quoted names ([rawName] preserves the
   * user's exact quoting, unlike the quote-stripped `CteDefinition.name`). PostgreSQL's
   * target-list origin tracking traces a bare column reference back through `SELECT * FROM
   * <cte>` to its ultimate source column (so `ResultSetMetaData.isNullable`/`getTableName` is
   * often exact even here, and the tracing itself can also lose the thread when an expression
   * breaks the chain — PostgreSQL then reports nullability as "unknown", which the check below
   * treats as nullable). Neither of those is the real risk of this fallback, though: see the
   * FUNDAMENTAL LIMITATION note at the top of this KDoc — origin tracking is blind to
   * outer-join null extension regardless of whether it successfully traces to a source column,
   * so this probe is INTENDED to be reached only for `body` shapes with no join to be blind to
   * — a guarantee enforced by the callers' current logic, not one this function can verify about
   * its own input.
   *
   * If `body` has no `RETURNING` clause at all, `SELECT * FROM <rawName>` itself fails to
   * prepare (PostgreSQL: no `RETURNING` clause on that CTE), which is indistinguishable here
   * from the `WITH` clause being unsound for some other reason — so a further probe,
   * `<fullWithClausePrefix> SELECT 1`, checks the clause alone (with a trivial main query that
   * doesn't reference [rawName] at all). If that prepares, the clause is fine and `body` is
   * simply unreferenceable, so the standard no-`RETURNING` stub (see below) is returned; if it
   * doesn't, every avenue has failed and `null` is returned as before.
   *
   * If preparing succeeds but the statement has no result columns (a data-modifying CTE with no
   * `RETURNING` clause, e.g. `INSERT ... ON CONFLICT DO NOTHING`), a single fabricated column
   * (`SELECT NULL::int4 AS norm_stub WHERE FALSE` — nullable, since the literal is `NULL`) is
   * returned instead of `null`. PostgreSQL rejects any reference to a no-`RETURNING`
   * data-modifying CTE, so this column is provably unreferenced by the rest of the query — it
   * exists only so the CTE body is a valid, DML-free `SELECT` that `CREATE VIEW` will accept.
   *
   * FORCED NULLABILITY: [forceNullableColumn] is consulted for every 1-based result column index
   * (together with the probe's total column count); wherever it returns `true`, that column is
   * built as `NULL::<type>` regardless of what `ResultSetMetaData.isNullable` reports — see
   * [tryPrepareStub]'s parameter KDoc for why callers force specific columns (an `OLD`/`NEW`
   * reference — see [oldOrNewReturningColumns]) or every column (an outer join, a reference to a
   * sibling CTE, or an unreliable `OLD`/`NEW` item-to-column mapping) this way.
   *
   * @param precisionPrefixes Candidate prefixes to try first, in order, as original SQL text
   *   ending in `)`.
   * @param body A SQL statement (the CTE body text, without surrounding parentheses).
   * @param fullWithClausePrefix The entire original `WITH` clause (through the last CTE's
   *   closing `)`), used for the true-scope fallback probes.
   * @param rawName The CTE's name exactly as written in the original SQL (quotes included, if
   *   any), used verbatim in the true-scope fallback probe's `FROM` clause.
   * @param forceNullableColumn See FORCED NULLABILITY above and [tryPrepareStub]'s KDoc.
   * @return The SELECT stub, or `null` if the statement could not be prepared by any means.
   */
  private fun buildSelectStub(
    precisionPrefixes: List<String>,
    body: String,
    fullWithClausePrefix: String,
    rawName: String,
    forceNullableColumn: (columnIndex: Int, totalColumnCount: Int) -> Boolean,
  ): String? {
    for (prefix in precisionPrefixes) {
      val probeSql = if (prefix.isEmpty()) body else "$prefix $body"
      val stub = tryPrepareStub(probeSql, forceNullableColumn)
      if (stub != null) return stub
    }
    tryPrepareStub("$fullWithClausePrefix SELECT * FROM $rawName", forceNullableColumn)?.let { return it }
    val withClauseAloneIsSound = try {
      connection.prepareStatement("$fullWithClausePrefix SELECT 1").use { it.metaData }
      true
    } catch (_: SQLException) {
      false
    }
    return if (withClauseAloneIsSound) "SELECT NULL::int4 AS norm_stub WHERE FALSE" else null
  }

  /**
   * Prepares [probeSql] and, if it succeeds, builds a SELECT stub from its result column
   * metadata (or the standard no-`RETURNING` stub if it has no result columns).
   *
   * @param forceNullableColumn Given a 1-based result column index and the probe's total column
   *   count, `true` if that column should be built as `NULL::<type>` regardless of
   *   `ResultSetMetaData.isNullable`. The caller has determined that `body` might contain an
   *   outer join (`LEFT`/`RIGHT`/`FULL JOIN`, or a `MERGE` with `WHEN NOT MATCHED BY SOURCE`),
   *   reference a sibling CTE by name, have a `RETURNING` item that reads `OLD`/`NEW`, or have an
   *   `OLD`/`NEW` item-to-column mapping this file cannot confirm is reliable (an unrecognized
   *   star item shifting every later item's real index — see [oldOrNewReturningColumns]'s KDoc,
   *   which is why the total column count is passed here at all: callers cross-check it against
   *   their own assumed item count) — and base-table `attnotnull` (which is all `isNullable` can
   *   ever report — see this function's caller's KDoc, "FUNDAMENTAL LIMITATION") cannot see any
   *   of those. For the outer-join, sibling-CTE, and unreliable-mapping triggers, callers pass a
   *   lambda returning `true` for every column rather than trying to identify which specific ones
   *   are affected: `ResultSetMetaData.getTableName` reports the base RELATION, not the alias used
   *   in the query, so a self-join (`FROM t LEFT JOIN t2 ...` where `t2` is another instance of
   *   the SAME table as the target) makes the join side and the always-present target side
   *   indistinguishable by relation name alone. This also confines the over-nullability to
   *   exactly the bodies that already reached this fallback (conversion unavailable or
   *   rejected) — a body that converts successfully still gets exact nullability from the
   *   join-aware node-tree analyzer, never this approximation.
   * @return The stub, or `null` if [probeSql] could not be prepared.
   */
  private fun tryPrepareStub(
    probeSql: String,
    forceNullableColumn: (columnIndex: Int, totalColumnCount: Int) -> Boolean,
  ): String? = try {
    connection.prepareStatement(probeSql).use { preparedStatement ->
      val metadata = preparedStatement.metaData
      if (metadata == null || metadata.columnCount == 0) {
        "SELECT NULL::int4 AS norm_stub WHERE FALSE"
      } else {
        buildString {
          append("SELECT ")
          for (i in 1..metadata.columnCount) {
            if (i > 1) append(", ")
            val typeName = when (metadata.getColumnTypeName(i)) {
              "serial" -> "int4"
              "smallserial", "serial2" -> "int2"
              "bigserial", "serial8" -> "int8"
              else -> metadata.getColumnTypeName(i)
            }
            val isNullable = forceNullableColumn(i, metadata.columnCount) ||
              metadata.isNullable(i) != ResultSetMetaData.columnNoNulls
            if (isNullable) {
              append("NULL::").append(typeName)
            } else {
              append(nonNullSentinel(typeName))
            }
            append(" AS ").append(quoteStubColumnAlias(metadata.getColumnName(i)))
          }
          append(" WHERE FALSE")
        }
      }
    }
  } catch (_: SQLException) {
    null
  }

  /**
   * Double-quotes [name] for use as a stub column alias, doubling any embedded `"`.
   *
   * `ResultSetMetaData.getColumnName` reports the `RETURNING` alias exactly as PostgreSQL
   * resolved it — case-exact, and not necessarily a valid bare identifier at all (e.g.
   * `?column?` for a nameless item like `RETURNING 1`). Emitting it unquoted would let
   * PostgreSQL fold any mixed-case name to lowercase, so a quoted, mixed-case original alias
   * (e.g. `RETURNING id AS "myId"`) would silently become `myid` on the stub built here — and
   * the outer query's `"myId"`-quoted reference to the CTE would then fail to resolve against
   * it (`column ins.myId does not exist`) when this stub is used to create the temporary view
   * [analyzeViaTemporaryView] reads nullability from. That failure never escapes as a build
   * error: [queryColumnNullability] catches it around its own [analyzeViaTemporaryView] calls
   * (see its inline "never an abort" note) and silently degrades to [analyzeUnconvertibleDml],
   * which reads real `ResultSetMetaData.isNullable` OR'd with [forcedNullabilityPredicate] rather
   * than the join-aware node-tree analyzer this quoting bug prevented from running — so the CTE's
   * exact nullability is lost in favor of this coarser, still-safe approximation. This stub is
   * throwaway SQL used only to
   * probe nullability, so quoting unconditionally (unlike
   * `JdbcAnalyzer.buildIdentifierQuoter`'s conditional quoting, which matters for generated,
   * user-facing SQL) is always valid and also handles names like `?column?` that aren't valid
   * bare identifiers to begin with.
   */
  private fun quoteStubColumnAlias(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

  /**
   * Determines result-column nullability for [sql] when [transformForViewCreation] found no join
   * structure to convert (or its conversion attempt failed to prepare) — the top-level (non-CTE)
   * counterpart of the CTE-body stub path's [forcedNullabilityPredicate] safety net (issue #207).
   *
   * Used for DML shapes with no join structure for [convertDmlToSelect] to convert in the first
   * place (a plain `INSERT`, `UPDATE`/`DELETE` without `FROM`/`USING`, or `MERGE` without
   * `RETURNING`), and for a top-level conversion this file attempted but could not validate (e.g.
   * a `RETURNING` that reads PostgreSQL 18's `OLD`/`NEW`, or a `MERGE`'s `merge_action()`).
   *
   * Unlike the CTE-body stub path — which builds a throwaway probe SQL statement and forces
   * specific columns to `NULL::<type>` in its text — this reads real `ResultSetMetaData` directly
   * from [sql] itself (already a preparable statement; no probe construction is needed) and ORs
   * two independent signals per column: `ResultSetMetaData.isNullable` (base-table `attnotnull`,
   * blind to outer-join null extension) and [forcedNullabilityPredicate] (the same OLD/NEW and
   * dangerous-sibling-CTE triggers the CTE-body path applies). The OR is monotone in the safe
   * direction: the predicate can only flip a column from NOT NULL toward nullable, never the
   * reverse, so a genuinely NOT NULL column reported as such by `ResultSetMetaData` stays NOT NULL
   * unless the predicate itself forces it.
   *
   * UNLIKE the CTE-body path, this caller passes `scopeNullExtendingScanToDmlSourceClause = true`:
   * the null-extending-construct arm (an outer join, or a `MERGE`'s grouping-set supertotal row)
   * is scanned only over [sql]'s own `FROM`/`USING`/`USING ... ON` source clause (see
   * [dmlSourceClauseRegion]), not the whole statement text. A flat whole-body scan here would
   * force a column nullable because of a `LEFT JOIN` sitting inside an unrelated `WHERE ... IN
   * (SELECT ...)` subquery that cannot null-extend `RETURNING` at all — confirmed against real
   * PostgreSQL to over-nullify exactly that shape before this scoping was added (issue #207,
   * round 2). The CTE-body path keeps the unscoped whole-body scan (its default parameter value)
   * because [forcedNullabilityPredicate]'s scoped variant still falls back to the whole-body scan
   * for any body that ISN'T itself a top-level `UPDATE`/`DELETE`/`MERGE` (e.g. a plain `SELECT`
   * CTE body) — see [forcedNullabilityPredicate]'s KDoc for why that fallback is deliberate, not
   * an oversight.
   *
   * [forcedNullabilityPredicate] is built from [sql]'s own main-query text (after any leading
   * `WITH` clause, matching what the CTE-body path passes for its own body) and, when [sql] has a
   * `WITH` clause of its own, the [computeDangerousSiblingNames] of its CTEs — so a top-level
   * statement that references one of its OWN dangerous CTEs by name (e.g. a plain `INSERT`, which
   * has no join structure for Phase 2 to convert but can still read a dangerous sibling's output)
   * is covered the same way a CTE referencing another CTE's danger is.
   *
   * `ResultSetMetaData.columnNullableUnknown` counts as NOT NULL here ONLY AS A FALLBACK — the
   * answer used when [probeUnknownColumnNullability] cannot resolve the column's real
   * nullability at all. This is now the OPPOSITE of what it was before issue #226: previously
   * every `columnNullableUnknown` column took this NOT NULL default unconditionally, deliberately
   * disagreeing with [tryPrepareStub] (whose CTE-body stub path treats that same value as
   * nullable, unconditionally, and is untouched by this change — see below). This function's
   * predecessor, [buildAllNonNullable] (removed by issue #207's fix), treated every column NOT
   * NULL unconditionally, `columnNullableUnknown` included — an expression or literal `RETURNING`
   * item (`1 AS one`, `'x'::TEXT AS lbl`) reports exactly that value, and PostgreSQL never returns
   * `NULL` for a literal, so treating it as NOT NULL for that shape is not a compromise, it is
   * simply correct. That NOT NULL default is preserved here as the FALLBACK for any
   * `columnNullableUnknown` column [probeUnknownColumnNullability] cannot resolve, so a column
   * that was already correct under the pre-#226 baseline stays correct.
   *
   * What the probe resolves, and what still falls back: [probeUnknownColumnNullability] builds a
   * throwaway `SELECT <returning items> FROM <target relation>` from [sql] itself and reads its
   * EXACT nullability through [analyzeViaTemporaryView]'s join-aware [NodeTreeNullabilityAnalyzer]
   * — the same machinery this file already trusts for a plain `SELECT` or a convertible DML body
   * — rather than guessing from `ResultSetMetaData` alone. That resolves an expression built over a
   * source column (`RETURNING lower(note)`, `RETURNING COALESCE(note, 'x')`, a scalar subquery
   * `RETURNING (SELECT ...)`, a `CASE`, a cast — anything [NodeTreeNullabilityAnalyzer] already
   * understands), closing the exact gap issue #226 reported: `DELETE ... RETURNING lower(note)`
   * over a nullable `note` now correctly reports nullable, and `RETURNING lower(name)` over a NOT
   * NULL `name` still correctly reports NOT NULL — the probe is exact, not a blanket flip either
   * way. The probe is skipped or discarded — falling back to this function's own NOT NULL default
   * — for a specific, provably-safe set of shapes: no `RETURNING` clause; a star item in the
   * `RETURNING` list (index alignment with real columns cannot be trusted); the split item count
   * disagreeing with the real column count; no recognizable target relation
   * ([dmlTargetRelationReference] returns `null`); or the probe SQL failing to prepare or its
   * temporary view failing to create. That last case is not an arbitrary residual gap — it is
   * EXACTLY the set of items this function's own history already established are always NOT NULL
   * regardless of any join or source column: `merge_action()` is invalid outside a `MERGE
   * RETURNING` list, so a plain-`SELECT` probe can never prepare it; `OLD`/`NEW` are invalid
   * outside `RETURNING` for the identical reason (see [referencesOldOrNew]'s KDoc); and a bare
   * `ROW(a, b)`/`(a, b)` composite makes `CREATE TEMPORARY VIEW` itself fail (PostgreSQL cannot
   * create a view column of the pseudo-type `record`). Every one of those is a constant, a
   * pseudo-relation reference invalid anywhere the probe could reach it, or a construct PostgreSQL
   * itself refuses to name a type for — never an ordinary expression over a genuinely nullable
   * source column — so falling back to NOT NULL for precisely that set is provably safe, not
   * merely convenient.
   *
   * [tryPrepareStub]'s CTE-body stub path is DELIBERATELY NOT changed by this: it has always
   * treated `columnNullableUnknown` as nullable, and roughly 90 existing CTE-path tests encode
   * that choice — changing it now would be an unrelated, unforced behavior change to a path issue
   * #226 does not touch. The claim that the two paths "deliberately disagree" no longer describes
   * this function accurately, though: for any column [probeUnknownColumnNullability] can resolve,
   * this function now reports that column's REAL nullability — computed by the identical
   * [analyzeViaTemporaryView] + [NodeTreeNullabilityAnalyzer] machinery [tryPrepareStub]'s own stub
   * path depends on — rather than a fixed rule chosen to stay monotone against a baseline. The two
   * paths no longer disagree BY RULE for that column; whichever answer is correct, both paths
   * would compute it identically if fed the same buildable probe. What remains a genuine, narrower
   * disagreement is confined to the columns the probe here cannot resolve at all (this function
   * still defaults to NOT NULL for those, unchanged) and to [tryPrepareStub]'s own path, which does
   * not attempt this probe and keeps its unconditional nullable default regardless of what the
   * real answer would be.
   *
   * @param sql The already `?`-sentinel-substituted (or `?`-free) form of the statement — see
   *   [buildViewSqlWithSentinels]/[replaceParameterPlaceholders] upstream — safe to `PREPARE` and
   *   to `CREATE TEMPORARY VIEW` from directly.
   * @param originalSql The statement exactly as [queryColumnNullability] received it, BEFORE any
   *   `?`-sentinel substitution. Passed through, unchanged, to [probeUnknownColumnNullability] so
   *   its own `UPDATE ... SET` analysis can see a genuine `?` parameter placeholder that [sql]'s
   *   sentinel substitution has already erased — see that function's KDoc for why this
   *   distinction matters (issue #228).
   * @return An empty list if [sql] cannot be prepared or has no result columns — [JdbcAnalyzer]
   *   treats a missing index as nullable (the safe default), so this is a safe, not merely
   *   convenient, degradation.
   */
  private fun analyzeUnconvertibleDml(
    @Language("PostgreSQL") sql: String,
    @Language("PostgreSQL") originalSql: String,
  ): List<Boolean> {
    val cteClause = parseCteClause(sql)
    val dangerousSiblingNames = cteClause?.let { computeDangerousSiblingNames(sql, it.definitions) }.orEmpty()
    val mainQueryText = cteClause?.let { sql.substring(it.mainQueryStart) } ?: sql
    val forceNullableColumn = forcedNullabilityPredicate(
      mainQueryText,
      dangerousSiblingNames,
      scopeNullExtendingScanToDmlSourceClause = true,
    )
    return try {
      connection.prepareStatement(sql).use { preparedStatement ->
        val metadata = preparedStatement.metaData ?: return emptyList()
        val hasUnknownColumn = (1..metadata.columnCount).any {
          metadata.isNullable(it) == ResultSetMetaData.columnNullableUnknown
        }
        val probeResult = if (hasUnknownColumn) {
          probeUnknownColumnNullability(sql, metadata.columnCount, originalSql)
        } else {
          null
        }
        List(metadata.columnCount) { index ->
          val columnIndex = index + 1
          val metadataNullable = when (metadata.isNullable(columnIndex)) {
            ResultSetMetaData.columnNullable -> true
            ResultSetMetaData.columnNoNulls -> false
            else -> probeResult?.get(index) ?: false
          }
          forceNullableColumn(columnIndex, metadata.columnCount) || metadataNullable
        }
      }
    } catch (_: SQLException) {
      emptyList()
    }
  }

  /**
   * Attempts to resolve the EXACT nullability of every result column of an unconvertible DML
   * [sql] statement, for use only on the columns [analyzeUnconvertibleDml] found reporting
   * `ResultSetMetaData.columnNullableUnknown` — an expression PostgreSQL's own metadata cannot
   * itself classify (e.g. a function call, a cast, or a scalar subquery in `RETURNING`).
   *
   * Builds a throwaway `SELECT <returning items> FROM <target relation>` from [sql]'s own
   * `RETURNING` list and target relation — see [dmlTargetRelationReference] — and reads that
   * probe's real per-column nullability through [analyzeViaTemporaryView]'s join-aware
   * [NodeTreeNullabilityAnalyzer], the SAME machinery this file already trusts for a plain
   * `SELECT` or a convertible DML body. A plain `SELECT` over the same target relation and the
   * same `RETURNING` expressions has identical nullability to the real `RETURNING` list itself:
   * neither an `UPDATE`/`DELETE`'s own row-filtering (`WHERE`) nor the fact that a row was
   * modified changes what a source column, or an expression over it, evaluates to for that row.
   *
   * Every gate below returns `null` — never a wrong or half-computed answer — whenever this
   * probe cannot be trusted, so [analyzeUnconvertibleDml] can safely fall back to its own NOT NULL
   * default:
   * - no top-level `RETURNING` clause on [sql] at all;
   * - any `RETURNING` item is a star item ([isStarItem]) — a star's expansion width is unknown
   *   without asking PostgreSQL, so positional alignment against [expectedColumnCount] cannot be
   *   trusted;
   * - the split `RETURNING` item count does not equal [expectedColumnCount] (the same
   *   real-column-count cross-check [oldOrNewReturningColumns]'s callers already apply for an
   *   identical reason);
   * - [dmlTargetRelationReference] cannot find a target relation to probe from;
   * - the probe SQL cannot be prepared, or its temporary view cannot be created (a `SQLException`
   *   from either) — see [analyzeUnconvertibleDml]'s KDoc for exactly which real-world shapes hit
   *   this (`merge_action()`, `OLD`/`NEW`, a bare `ROW(...)`/`(a, b)` composite);
   * - the probe's own result size does not equal [expectedColumnCount] (defense in depth against
   *   the same star-expansion risk the item-count gate above targets).
   *
   * For a plain `UPDATE ... SET` (never an `UPDATE ... FROM` — that has join structure of its own
   * and is converted long before this function is ever reached), the bare `FROM <target relation>`
   * above is upgraded to a `SET`-assignment-aware derived table by
   * [buildUpdateSetAwareFromClause] — see its KDoc for exactly what that upgrade can and cannot
   * see (issue #228): it closes the specific gap where a `RETURNING` expression is built over a
   * column the statement's OWN `SET` clause just assigned a provably non-null value to (`UPDATE t
   * SET note = 'x' RETURNING lower(note)`, where `note` is nullable in the catalog but can never
   * be `NULL` in this particular result). [buildUpdateSetAwareFromClause] returns `null` — falling
   * back to the bare `FROM <target>` unchanged — for every case it cannot confidently upgrade, so
   * this function's own answer is never LESS safe than before that upgrade existed, only
   * sometimes more precise.
   *
   * @param sql The unconvertible top-level DML statement (already parameter-sentinel-substituted
   *   by the caller — see [buildViewSqlWithSentinels]/[replaceParameterPlaceholders] upstream —
   *   so this function's own [buildViewSqlWithSentinels] call over the freshly-built probe SQL is
   *   only a defensive re-check, not the primary `?` removal).
   * @param expectedColumnCount The real result column count, from [sql]'s own
   *   `ResultSetMetaData.getColumnCount()`.
   * @param originalSql [sql] before `?`-sentinel substitution — see [buildUpdateSetAwareFromClause]'s
   *   KDoc for why a `SET` assignment's right-hand side must be inspected in THIS form, not [sql]'s.
   * @return One nullability value per `RETURNING` item, in order, or `null` if the probe could not
   *   be built, prepared, or trusted.
   */
  private fun probeUnknownColumnNullability(
    @Language("PostgreSQL") sql: String,
    expectedColumnCount: Int,
    @Language("PostgreSQL") originalSql: String,
  ): List<Boolean>? {
    val dml = stripLeadingNestedWithClause(sql)
    val returningIndex = findTopLevelReturningKeyword(dml)
    if (returningIndex < 0) return null

    val returningText = dml.substring(returningIndex + "RETURNING".length).trim().trimEnd(';')
    val items = splitAtTopLevel(returningText, ',')
    if (items.any(::isStarItem)) return null
    if (items.size != expectedColumnCount) return null

    val target = dmlTargetRelationReference(sql) ?: return null
    val bareFromClause = "FROM $target"
    val setAwareFromClause = buildUpdateSetAwareFromClause(sql, originalSql, target, returningText)

    // The SET-aware FROM clause is a strict refinement, never a replacement of last resort: try
    // it first ONLY when it exists, but if it fails to prepare, fails to build its temporary
    // view, or comes back with the wrong column count, fall back to the bare `FROM <target>`
    // probe that issue #226 already trusted — never surface a construction failure the #228
    // wrapping introduced as a `null` (which `analyzeUnconvertibleDml` reads as NOT NULL) when
    // the unwrapped probe would have answered correctly. This is what keeps every wrapping
    // failure, not merely the ones already known about, no less safe than #226's own answer.
    val setAwareResult = setAwareFromClause?.let { runNullabilityProbe(returningText, it, expectedColumnCount) }
    return setAwareResult ?: runNullabilityProbe(returningText, bareFromClause, expectedColumnCount)
  }

  /**
   * Runs a single `SELECT <returningText> FROM <fromClause>` nullability probe through
   * [analyzeViaTemporaryView], returning `null` — never a wrong or half-computed answer —
   * whenever the probe cannot be trusted: it fails to `PREPARE`, its temporary view fails to
   * create (either surfaces as a `SQLException`), or its result size does not equal
   * [expectedColumnCount]. Factored out of [probeUnknownColumnNullability] so that function can
   * run this same logic twice — once for its SET-aware [fromClause], once for the bare
   * `FROM <target>` fallback — without duplicating the probe-construction or exception-handling
   * logic between the two attempts.
   *
   * @param returningText The already-extracted `RETURNING` list text (no leading `RETURNING`
   *   keyword), verbatim from [probeUnknownColumnNullability].
   * @param fromClause A complete `FROM ...` clause — either the bare `FROM <target>` or the
   *   `SET`-aware derived-table form from [buildUpdateSetAwareFromClause].
   * @param expectedColumnCount The real result column count the probe's own result must match.
   * @return One nullability value per `RETURNING` item, in order, or `null` if the probe could
   *   not be built, prepared, or trusted.
   */
  private fun runNullabilityProbe(
    returningText: String,
    fromClause: String,
    expectedColumnCount: Int,
  ): List<Boolean>? {
    // A newline, not a space, separates the RETURNING text from FROM: a trailing `--` line
    // comment on the last RETURNING item (e.g. "RETURNING id, lower(note) AS n -- lowercased")
    // has no line terminator of its own to stop at when returningText is the LAST thing before
    // FROM on one line — it would otherwise swallow the FROM clause into the comment, making the
    // probe fail to prepare (a `SQLException`, caught below) and silently degrading to today's
    // NOT NULL default instead of the real answer. Putting FROM on its own line means the comment
    // can only ever consume up to that line's own end, never past it.
    val probeSql = "SELECT $returningText\n$fromClause"
    val viewSql = buildViewSqlWithSentinels(probeSql) ?: if ('?' in probeSql) return null else probeSql

    return try {
      analyzeViaTemporaryView(viewSql).takeIf { it.size == expectedColumnCount }
    } catch (_: SQLException) {
      null
    }
  }

  /**
   * Builds `FROM (SELECT <col-list> FROM <target>) AS <alias>` for a plain `UPDATE ... SET`
   * statement — the `SET`-assignment-aware upgrade [probeUnknownColumnNullability] applies to its
   * own bare `FROM <target>` (issue #228). Wrapping the target in a derived table whose column
   * list already carries the `SET`-assigned expressions lets the SAME node-tree analysis
   * [probeUnknownColumnNullability] already trusts resolve every identifier itself — no
   * hand-rolled rewriting of the `RETURNING` text is needed. For `UPDATE t SET note = 'x'
   * RETURNING lower(note)`, this turns the probe's `FROM t` into `FROM (SELECT id, ('x') AS
   * "note" FROM t) AS t`, so `note` inside the `RETURNING` expression now evaluates against the
   * literal `'x'` rather than `note`'s own (nullable) catalog column.
   *
   * An assigned column's own expression may reference OTHER target columns by their OLD
   * (pre-update) value (`SET note = note || 'x'`): inside the inner `SELECT`, an unqualified
   * `note` resolves against the bare `<target>` — the OLD row — which is exactly what an
   * `UPDATE`'s `SET`-list semantics already mean, so the substituted expression is spliced in
   * VERBATIM with no extra handling.
   *
   * [parseSetAssignments] is run TWICE — once over [originalSql] (before `?`-sentinel
   * substitution), once over [sql] (after) — and the two are reconciled BY COLUMN NAME, never by
   * position, so the two texts never need to align positionally (see [parseSetAssignments]'s own
   * KDoc for why a `?` sentinel's different length could otherwise desynchronize them). A column
   * keeps its bare, unsubstituted form in the derived table — deferring to the pre-existing,
   * already-safe catalog-`attnotnull` answer for that one column — whenever ANY of the following
   * holds:
   * - it has no plain (single-column) assignment in [originalSql] at all — this covers both an
   *   untouched column and the row form `SET (a, b) = (...)`, whose right-hand side cannot be
   *   safely attributed to any one of its several target columns;
   * - its [originalSql] assignment's right-hand side is exactly the keyword `DEFAULT` — an
   *   unknown, possibly-`null` value this function makes no attempt to resolve;
   * - its [originalSql] assignment's right-hand side contains a lexical `?` — by the time [sql]
   *   reaches this function, [buildViewSqlWithSentinels] has already replaced every `?` parameter
   *   placeholder with a typed NON-NULL sentinel (`0::int4`, `''::text`, …), so trusting [sql]'s
   *   own substituted expression here would silently assert NOT NULL for a value a real caller
   *   can bind `NULL` to at runtime — the exact regression issue #228 exists to close. Only
   *   [originalSql]'s pre-sentinel text still carries the bare `?`, so only it can catch this;
   * - it has no matching plain assignment in [sql]'s own parse — should not happen when [sql] and
   *   [originalSql] are truly the sentinel/pre-sentinel forms of the same statement, but kept as
   *   a defensive check: with no [sql]-side expression there is nothing trustworthy to splice.
   *
   * Before any of the above, this function bails outright — without even attempting to parse the
   * `SET` list — whenever the target relation is one where `RETURNING` can see a tuple something
   * OTHER than this statement's own `SET` clause rewrote between assignment and return. `RETURNING`
   * always reflects the FINAL, post-trigger, post-rule tuple, never the `SET` expression's raw
   * value, so substituting that raw value is only safe when nothing else in the pipeline can have
   * changed it. See [resolveTargetRelationOidIfSubstitutionSafe] for the exact catalog-based
   * check.
   *
   * Also bails when [returningText] references PostgreSQL 18's `OLD`/`NEW` `RETURNING`
   * pseudo-relations (via [referencesOldOrNew]): `OLD.col` must see the PRE-update value, which is
   * exactly the opposite of what this function's substitution would splice in for a column `OLD`
   * qualifies.
   *
   * @param returningText The already-extracted `RETURNING` list text (no leading `RETURNING`
   *   keyword) [probeUnknownColumnNullability] parsed from [sql] — checked for an `OLD`/`NEW`
   *   pseudo-relation reference before any substitution is attempted.
   * @return `null` — the caller's signal to keep its own bare `FROM <target>` unchanged — when
   *   [sql] (after stripping any leading nested `WITH` clause) is not a plain `UPDATE`; when
   *   [target]'s own alias can't be confidently derived (see [deriveDmlTargetAlias]); when
   *   `SELECT * FROM <target>` fails to prepare (so the real column list can't be read); when the
   *   `SET` list fails to parse in either [sql] or [originalSql] (see [parseSetAssignments]'s KDoc
   *   for why a partial parse is never trusted either); when [returningText] references `OLD`/`NEW`;
   *   or when [resolveTargetRelationOidIfSubstitutionSafe] reports the target relation unsafe.
   */
  private fun buildUpdateSetAwareFromClause(
    @Language("PostgreSQL") sql: String,
    @Language("PostgreSQL") originalSql: String,
    target: String,
    returningText: String,
  ): String? {
    val dml = stripLeadingNestedWithClause(sql)
    val trimmedStart = skipWhitespaceAndComments(dml, 0)
    if (skipOptionalKeyword(dml, trimmedStart, "UPDATE") == trimmedStart) return null

    if (referencesOldOrNew(returningText)) return null
    val relationOid = resolveTargetRelationOidIfSubstitutionSafe(target) ?: return null

    val alias = deriveDmlTargetAlias(target) ?: return null

    val columnNames = try {
      connection.prepareStatement("SELECT * FROM $target").use { preparedStatement ->
        preparedStatement.metaData?.let { metadata -> (1..metadata.columnCount).map { metadata.getColumnName(it) } }
      }
    } catch (_: SQLException) {
      null
    }
    if (columnNames.isNullOrEmpty()) return null

    val originalAssignments = parseSetAssignments(stripLeadingNestedWithClause(originalSql)) ?: return null
    val sentinelAssignments = parseSetAssignments(dml) ?: return null

    val originalPlainByColumn = originalAssignments.filter { !it.isRowForm }
      .associate { it.columnNames.single() to it.expression }
    val originalRowFormColumns = originalAssignments.filter { it.isRowForm }
      .flatMapTo(mutableSetOf()) { it.columnNames }
    val sentinelPlainByColumn = sentinelAssignments.filter { !it.isRowForm }
      .associate { it.columnNames.single() to it.expression }

    // Never trusted from any other source: an untyped literal or expression spliced bare into the
    // derived table's column list is typed `text` by PostgreSQL there (issue #226's original bug,
    // resurfacing as a #228-fix regression) — a DIFFERENT type than the real column's, which can
    // resolve a RETURNING function call against a completely different, sometimes safe-listed,
    // overload than the real statement would ever use (`lower(int4range)` vs. the real
    // `lower(text)` is exactly this). Casting to the column's own declared type — typmod included,
    // via `format_type` — makes the derived table's column type IDENTICAL to the real one, so
    // whatever overload the real statement resolves is the same one this probe resolves too.
    val declaredTypesByColumn = lookupDeclaredColumnTypes(relationOid) ?: emptyMap()

    val innerColumnList = columnNames.joinToString(", ") { columnName ->
      val originalExpression = originalPlainByColumn[columnName]
      val sentinelExpression = sentinelPlainByColumn[columnName]
      val declaredType = declaredTypesByColumn[columnName]
      val eligible = columnName !in originalRowFormColumns &&
        originalExpression != null &&
        sentinelExpression != null &&
        !isDefaultKeyword(originalExpression) &&
        !containsLexicalPlaceholder(originalExpression) &&
        declaredType != null
      if (eligible) {
        "($sentinelExpression)::$declaredType AS ${quoteStubColumnAlias(columnName)}"
      } else {
        quoteStubColumnAlias(columnName)
      }
    }

    return "FROM (SELECT $innerColumnList FROM $target) AS $alias"
  }

  /**
   * Looks up the declared type — base type AND typmod (length, precision/scale, array dimensions,
   * domain identity, etc.) — of every column of [relationOid], via `format_type(atttypid,
   * atttypmod)` over `pg_attribute`. [buildUpdateSetAwareFromClause] casts each substituted `SET`
   * expression to this exact string so the derived table's column type is indistinguishable from
   * the real table's — see that function's own KDoc for why a bare, uncast substitution can
   * silently resolve a DIFFERENT (and sometimes wrongly safe-listed) function overload than the
   * real statement would.
   *
   * `format_type` itself produces a re-parsable type name — already quoted or schema-qualified
   * wherever PostgreSQL would otherwise misparse it — so its result is spliced directly after `::`
   * with no additional quoting from this function.
   *
   * @param relationOid The OID [resolveTargetRelationOidIfSubstitutionSafe] already resolved for
   *   this same target relation — never re-resolved here, so the two functions can never
   *   disagree about which relation they mean.
   * @return A map from column name to its declared type text, or `null` if the catalog query
   *   itself fails to execute — treated by the caller exactly like every column being absent from
   *   the map: no column becomes eligible for substitution, and each keeps its bare, unsubstituted
   *   form, deferring to the pre-existing, already-safe catalog-`attnotnull` answer.
   */
  private fun lookupDeclaredColumnTypes(relationOid: Int): Map<String, String>? = try {
    connection.prepareStatement(
      """
      SELECT attname, format_type(atttypid, atttypmod) AS declared_type
      FROM pg_catalog.pg_attribute
      WHERE attrelid = ? AND attnum > 0 AND NOT attisdropped
      """.trimIndent(),
    ).use { preparedStatement ->
      preparedStatement.setInt(1, relationOid)
      preparedStatement.executeQuery().use { rs ->
        buildMap {
          while (rs.next()) {
            put(rs.getString("attname"), rs.getString("declared_type"))
          }
        }
      }
    }
  } catch (_: SQLException) {
    null
  }

  /**
   * Checks whether [target] — a plain `UPDATE ... SET` statement's target relation, exactly as
   * [dmlTargetRelationReference] extracted it — is one where `RETURNING` can see a FINAL tuple
   * that differs from what this statement's own `SET` clause assigned, making
   * [buildUpdateSetAwareFromClause]'s substitution (issue #228's fix) itself unsafe: `RETURNING`
   * always reflects the tuple actually stored (or, for `DO INSTEAD`, whatever the replacing query
   * produces), never the raw `SET` expression, so a row-level `BEFORE` trigger, a rewrite rule, an
   * `INSTEAD OF` trigger on a view, or an FDW's own write path can each substitute a completely
   * different value for the very column [buildUpdateSetAwareFromClause] would otherwise splice in
   * as provably non-null.
   *
   * Resolves [target] to an OID via `to_regclass` and returns that SAME OID to the caller on
   * success — [buildUpdateSetAwareFromClause] reuses it to look up each column's declared type
   * (see [lookupDeclaredColumnTypes]) rather than resolving [target] to an OID a second time.
   * Answers "unsafe" (`null`, bail, don't substitute) when ANY of the following hold:
   * 1. [target] cannot be resolved to an OID at all — an ambiguous, unparseable, or unknown
   *    relation reference. Never guessed at: an unresolvable target is treated exactly as unsafe
   *    as one known to be unsafe.
   * 2. The relation, OR ANY transitive inheritance descendant or partition (recursed via
   *    `pg_inherits`), has a `pg_class.relkind` of view (`v`), materialized view (`m`), or foreign
   *    table (`f`). An `INSTEAD OF` trigger or a foreign data wrapper's own `UPDATE` path can
   *    produce any tuple it likes, independent of this statement's `SET` clause — including an
   *    auto-updatable view, whose write routes through a base table whose OWN triggers then apply;
   *    this function does not attempt to chase that base table, it simply bails on the view itself.
   *    Checking every descendant, not just the root, matters because a partitioned table's
   *    partition — or a plain inheritance child — can itself be a foreign table even when the root
   *    relation is an ordinary local table: `UPDATE parent SET ... RETURNING ...` can still route a
   *    given row through a foreign partition's own remote write path.
   * 3. The relation, or ANY transitive inheritance descendant or partition (recursed via
   *    `pg_inherits`), has a row-level `BEFORE` trigger for `UPDATE` OR `INSERT`, excluding
   *    internal constraint triggers (`tgisinternal`). `INSERT` matters alongside `UPDATE` because a
   *    partitioned table's cross-partition `UPDATE` is internally re-routed as a `DELETE` on the
   *    source partition plus an `INSERT` on the destination partition, whose own `BEFORE INSERT`
   *    trigger can rewrite the tuple `RETURNING` ultimately sees — a risk that exists for the
   *    partitioned table as a whole regardless of which specific row this statement touches, so
   *    ANY descendant carrying such a trigger bails the WHOLE relation, not just that descendant's
   *    own rows. A statement-level trigger, or an `AFTER` trigger of either level, does not affect
   *    the tuple `RETURNING` observes and is deliberately NOT matched here.
   * 4. The relation, or any transitive descendant, has any rewrite rule in `pg_rewrite` other than
   *    a view's own implicit `_RETURN` `SELECT` rule (identified by `rulename`). A `DO INSTEAD`
   *    rule can replace the statement wholesale with a query this function has no visibility into.
   *
   * Deliberately does NOT bail for a generated or identity column (PostgreSQL rejects those as
   * `SET` targets outright, so they can never appear as a plain assignment in the first place) or
   * for a domain constraint on an assigned column (a constraint violation aborts the statement
   * rather than silently rewriting its value) — neither can produce the RETURNING-sees-something-
   * else risk this function exists to catch.
   *
   * @return `null` — meaning [buildUpdateSetAwareFromClause] must bail and keep the caller's bare,
   *   unwrapped `FROM <target>` probe — whenever any of the above cannot be ruled out, INCLUDING
   *   when the catalog query itself fails to execute (treated the same as "can't confirm this
   *   substitution is safe"). Otherwise, [target]'s resolved relation OID — every condition above
   *   checked and none matched — for [buildUpdateSetAwareFromClause] to proceed and reuse.
   */
  private fun resolveTargetRelationOidIfSubstitutionSafe(target: String): Int? {
    val relationName = dmlTargetRelationName(target) ?: return null

    val result = try {
      connection.prepareStatement(
        """
        WITH RECURSIVE root(relid) AS (
          SELECT to_regclass(?)::integer
        ),
        descendants(relid) AS (
          SELECT relid FROM root WHERE relid IS NOT NULL
          UNION
          SELECT i.inhrelid::integer
          FROM pg_catalog.pg_inherits i
          JOIN descendants d ON i.inhparent = d.relid
        )
        SELECT
          (SELECT relid FROM root) AS root_relid,
          EXISTS (
            SELECT 1
            FROM pg_catalog.pg_class c
            JOIN descendants d ON c.oid = d.relid
            WHERE c.relkind IN ('v', 'm', 'f')
          ) AS has_risky_relkind,
          EXISTS (
            SELECT 1
            FROM pg_catalog.pg_trigger tg
            JOIN descendants d ON tg.tgrelid = d.relid
            WHERE NOT tg.tgisinternal
              AND (tg.tgtype & 1) = 1
              AND (tg.tgtype & 2) = 2
              AND ((tg.tgtype & 4) = 4 OR (tg.tgtype & 16) = 16)
          ) AS has_mutating_row_trigger,
          EXISTS (
            SELECT 1
            FROM pg_catalog.pg_rewrite rw
            JOIN descendants d ON rw.ev_class = d.relid
            WHERE rw.rulename <> '_RETURN'
          ) AS has_non_view_rewrite_rule
        """.trimIndent(),
      ).use { preparedStatement ->
        preparedStatement.setString(1, relationName)
        preparedStatement.executeQuery().use { rs ->
          if (!rs.next()) return@use null
          val rootRelid = rs.getInt("root_relid").takeUnless { rs.wasNull() }
          val hasRisk = rs.getBoolean("has_risky_relkind") ||
            rs.getBoolean("has_mutating_row_trigger") ||
            rs.getBoolean("has_non_view_rewrite_rule")
          rootRelid to hasRisk
        }
      }
    } catch (_: SQLException) {
      null
    }

    val (rootRelid, hasRisk) = result ?: return null
    if (rootRelid == null) return null // to_regclass could not resolve the target — bail, don't guess
    return rootRelid.takeUnless { hasRisk }
  }

  /**
   * Checks that the connected PostgreSQL server is version 16 or later.
   *
   * Norm requires PostgreSQL 16+ for `varnullingrels` support in query tree nodes,
   * used for accurate outer join nullability detection.
   *
   * @throws IllegalStateException if the server version is below 16.
   */
  fun checkPostgresVersion() {
    check(pgMajorVersion >= 16) {
      "Norm requires PostgreSQL 16 or later (connected to version $pgMajorVersion). " +
        "PostgreSQL 16 added varnullingrels to query tree nodes, which Norm uses " +
        "for accurate outer join nullability detection."
    }
  }

  /**
   * Looks up the parameter types and names for a stored procedure from `pg_proc`.
   *
   * Used to generate typed parameters for `CALL` statements. Queries the `public` schema only.
   *
   * Argument names come from `proargnames`. When a procedure has no named arguments,
   * names fall back to `p1`, `p2`, etc.
   *
   * @param procName The unqualified procedure name to look up.
   * @return The procedure's parameters in declaration order, or an empty list if the procedure
   *   is not found in `pg_proc`.
   */
  fun lookupProcedureParameters(procName: String): List<Parameter> = buildList {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT p.proargnames, p.proargtypes, string_agg(t.typname, ',' ORDER BY ordinality) as type_names
        FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        CROSS JOIN LATERAL unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
        JOIN pg_catalog.pg_type t ON t.oid = u.type_oid
        WHERE p.proname = '$procName'
          AND n.nspname = 'public'
        GROUP BY p.proargnames, p.proargtypes
        """.trimIndent(),
      ).use { rs ->
        if (rs.next()) {
          val typeNames = rs.getString("type_names").split(",")
          val argNamesArray = rs.getArray("proargnames")
          val argNames = if (argNamesArray != null) {
            @Suppress("UNCHECKED_CAST")
            (argNamesArray.array as Array<String>).toList()
          } else {
            typeNames.indices.map { "p${it + 1}" }
          }
          for ((index, typeName) in typeNames.withIndex()) {
            add(
              Parameter(
                number = index + 1,
                column = Column(
                  name = argNames.getOrElse(index) { "p${index + 1}" },
                  notNull = true,
                  type = Identifier(name = typeName),
                ),
              ),
            )
          }
        }
      }
    }
  }

  internal companion object {
    /**
     * Exact `(pg_proc.proname, argument type signature)` pairs safe-listed for
     * [neverNullForNonNullInputOids]. Restricted to `pronamespace = 'pg_catalog'` at query time.
     * Argument types are matched by `pg_type.typname` in declaration order — e.g. `listOf("text",
     * "text")` matches only the two-`text`-argument overload of a name, not a same-arity overload
     * over different types. A signature is listed here ONLY when it was independently confirmed,
     * against a live PostgreSQL 18 instance, to be total on every non-null, well-typed input —
     * including infinite, empty, or unbounded edge values, not merely "typical" ones. Below is
     * that audit, one entry per bullet. Every `pg_catalog` overload actually present for each name
     * is listed even where excluded, so an omission is visibly a decision rather than an oversight.
     *
     * - **upper/lower**: `pg_catalog` overloads are `(text)`, `(anyrange)`, `(anymultirange)`.
     *   `(text)` is total (kept). `(anyrange)`/`(anymultirange)` are STRICT but return `null` for a
     *   non-null, non-empty, UNBOUNDED range/multirange (`upper(int4range '[1,)')`) or an EMPTY one
     *   (`lower(int4range 'empty')`, `lower(int4multirange '{}')`) — excluded.
     * - **initcap**: only overload is `(text)` — total (kept).
     * - **length**: overloads are `(text)`, `(bytea)`, `(bytea, name)` (length in a named
     *   encoding), `(bit)`, `(bpchar)`, `(lseg)`, `(path)`, `(tsvector)`. All total on non-null
     *   input — a degenerate zero-length `lseg`/single-point `path` returns `0`, not `null`; an
     *   unrecognized encoding name errors rather than returning `null` (all kept).
     * - **char_length**: overloads are `(text)`, `(bpchar)` — both total (kept).
     * - **btrim/ltrim/rtrim**: overloads are `(text)`, `(text, text)`, `(bytea, bytea)` — all total,
     *   including on an empty trim-characters argument (kept).
     * - **replace**: only overload is `(text, text, text)` — total (kept).
     * - **split_part**: only overload is `(text, text, int4)` — total, including a field position
     *   past the actual field count (returns `''`, not `null`) and a negative position counting
     *   from the end (kept).
     * - **strpos**: only overload is `(text, text)` — total, including empty needle/haystack
     *   (returns `0`/`1`, not `null`) (kept).
     * - **md5**: overloads are `(text)`, `(bytea)` — both total, including on empty input (kept).
     * - **abs**: overloads are `(int2)`, `(int4)`, `(int8)`, `(numeric)`, `(float4)`, `(float8)` —
     *   no `(interval)` overload exists in `pg_catalog` (interval's absolute value is the `@`
     *   OPERATOR, a distinct catalog entry, not a same-named function). All six are total,
     *   including on `NaN`/`Infinity` (kept).
     * - **round**: overloads are `(float8)`, `(numeric)`, `(numeric, int4)` — all total, including
     *   on `NaN`/`Infinity` (kept).
     * - **floor/ceil/ceiling**: overloads are `(float8)`, `(numeric)` — all total, including on
     *   `NaN`/`Infinity` (kept).
     * - **now**: only overload is `()` — total (kept).
     * - **date_trunc**: overloads are `(text, interval)`, `(text, timestamp)`, `(text,
     *   timestamptz)`, `(text, timestamptz, text)`. Unlike `extract`/`date_part` below,
     *   `date_trunc` special-cases infinite input for EVERY truncation field, verified across
     *   `hour`, `microseconds`, `week`, `quarter`, `day` on `'infinity'`/`'-infinity'` values of
     *   all three temporal types, plus the 3-argument timezone-name form — all preserve infinity
     *   rather than returning `null`, and an unrecognized field name or timezone name errors rather
     *   than returning `null` (all kept).
     * - **date_part/extract**: overloads are `(text, date)`, `(text, interval)`, `(text, time)`,
     *   `(text, timestamp)`, `(text, timestamptz)`, `(text, timetz)`. UNLIKE `date_trunc`, these
     *   only special-case a FEW fields (`epoch`, `year`, `century`, ...) for infinite input — most
     *   other fields silently return `null` for a non-null, well-typed infinite value:
     *   `extract(hour FROM 'infinity'::timestamp)`, `extract(month FROM 'infinity'::interval)`, and
     *   `extract(day FROM 'infinity'::date)` (`date` supports `'infinity'` too) all return `null`
     *   with NO error. `(text, date)`, `(text, interval)`, `(text, timestamp)`, `(text,
     *   timestamptz)` are therefore EXCLUDED. `(text, time)` and `(text, timetz)` have no infinite
     *   representation for their base type and were verified total across every valid field
     *   (`hour`, `minute`, `second`, `microseconds`, `milliseconds`, `epoch`,
     *   `timezone`/`timezone_hour`/`timezone_minute` for `timetz`) plus an unrecognized field name
     *   (errors, does not return `null`) — kept.
     * - **to_char**: DROPPED ENTIRELY. `to_char(timestamp, '')` (an empty, non-null, well-typed
     *   format string) returns `null` with no error — confirmed on `pg_catalog`'s
     *   `(timestamp, text)` overload; the same risk was not individually re-verified for every
     *   other `to_char` overload (`bigint`, `float8`, `int4`, `interval`, `numeric`, `float4`,
     *   `timestamptz`, each paired with `text`) but is assumed present, since the empty-format
     *   behavior is a property of `to_char`'s shared formatting engine, not of the first
     *   argument's type.
     * - **ntile**: only window-function overload is `(int4)` — errors (not `null`) for a
     *   non-positive bucket count, total otherwise (kept).
     * - **encode/decode**: overloads are `(bytea, text)` and `(text, text)` respectively — both
     *   total on empty input; an unrecognized format name errors rather than returning `null`
     *   (kept).
     */
    internal val NEVER_NULL_FUNCTION_SIGNATURES: List<SafeFunctionSignature> = listOf(
      SafeFunctionSignature("upper", listOf("text")),
      SafeFunctionSignature("lower", listOf("text")),
      SafeFunctionSignature("initcap", listOf("text")),
      SafeFunctionSignature("length", listOf("text")),
      SafeFunctionSignature("length", listOf("bytea")),
      SafeFunctionSignature("length", listOf("bytea", "name")),
      SafeFunctionSignature("length", listOf("bit")),
      SafeFunctionSignature("length", listOf("bpchar")),
      SafeFunctionSignature("length", listOf("lseg")),
      SafeFunctionSignature("length", listOf("path")),
      SafeFunctionSignature("length", listOf("tsvector")),
      SafeFunctionSignature("char_length", listOf("text")),
      SafeFunctionSignature("char_length", listOf("bpchar")),
      SafeFunctionSignature("btrim", listOf("text")),
      SafeFunctionSignature("btrim", listOf("text", "text")),
      SafeFunctionSignature("btrim", listOf("bytea", "bytea")),
      SafeFunctionSignature("ltrim", listOf("text")),
      SafeFunctionSignature("ltrim", listOf("text", "text")),
      SafeFunctionSignature("ltrim", listOf("bytea", "bytea")),
      SafeFunctionSignature("rtrim", listOf("text")),
      SafeFunctionSignature("rtrim", listOf("text", "text")),
      SafeFunctionSignature("rtrim", listOf("bytea", "bytea")),
      SafeFunctionSignature("replace", listOf("text", "text", "text")),
      SafeFunctionSignature("split_part", listOf("text", "text", "int4")),
      SafeFunctionSignature("strpos", listOf("text", "text")),
      SafeFunctionSignature("md5", listOf("text")),
      SafeFunctionSignature("md5", listOf("bytea")),
      SafeFunctionSignature("abs", listOf("int2")),
      SafeFunctionSignature("abs", listOf("int4")),
      SafeFunctionSignature("abs", listOf("int8")),
      SafeFunctionSignature("abs", listOf("numeric")),
      SafeFunctionSignature("abs", listOf("float4")),
      SafeFunctionSignature("abs", listOf("float8")),
      SafeFunctionSignature("round", listOf("float8")),
      SafeFunctionSignature("round", listOf("numeric")),
      SafeFunctionSignature("round", listOf("numeric", "int4")),
      SafeFunctionSignature("floor", listOf("float8")),
      SafeFunctionSignature("floor", listOf("numeric")),
      SafeFunctionSignature("ceil", listOf("float8")),
      SafeFunctionSignature("ceil", listOf("numeric")),
      SafeFunctionSignature("ceiling", listOf("float8")),
      SafeFunctionSignature("ceiling", listOf("numeric")),
      SafeFunctionSignature("now", emptyList()),
      SafeFunctionSignature("date_trunc", listOf("text", "interval")),
      SafeFunctionSignature("date_trunc", listOf("text", "timestamp")),
      SafeFunctionSignature("date_trunc", listOf("text", "timestamptz")),
      SafeFunctionSignature("date_trunc", listOf("text", "timestamptz", "text")),
      SafeFunctionSignature("date_part", listOf("text", "time")),
      SafeFunctionSignature("date_part", listOf("text", "timetz")),
      SafeFunctionSignature("extract", listOf("text", "time")),
      SafeFunctionSignature("extract", listOf("text", "timetz")),
      SafeFunctionSignature("ntile", listOf("int4")),
      SafeFunctionSignature("encode", listOf("bytea", "text")),
      SafeFunctionSignature("decode", listOf("text", "text")),
      SafeFunctionSignature("left", listOf("text", "int4")),
      SafeFunctionSignature("right", listOf("text", "int4")),
      SafeFunctionSignature("substr", listOf("text", "int4")),
      SafeFunctionSignature("substr", listOf("text", "int4", "int4")),
      SafeFunctionSignature("substr", listOf("bytea", "int4")),
      SafeFunctionSignature("substr", listOf("bytea", "int4", "int4")),
      SafeFunctionSignature("repeat", listOf("text", "int4")),
      SafeFunctionSignature("lpad", listOf("text", "int4")),
      SafeFunctionSignature("lpad", listOf("text", "int4", "text")),
      SafeFunctionSignature("rpad", listOf("text", "int4")),
      SafeFunctionSignature("rpad", listOf("text", "int4", "text")),
      SafeFunctionSignature("reverse", listOf("text")),
      SafeFunctionSignature("reverse", listOf("bytea")),
      SafeFunctionSignature("position", listOf("text", "text")),
      SafeFunctionSignature("position", listOf("bytea", "bytea")),
      SafeFunctionSignature("position", listOf("bit", "bit")),
      SafeFunctionSignature("translate", listOf("text", "text", "text")),
      SafeFunctionSignature("octet_length", listOf("text")),
      SafeFunctionSignature("octet_length", listOf("bytea")),
      SafeFunctionSignature("octet_length", listOf("bpchar")),
      SafeFunctionSignature("octet_length", listOf("bit")),
      SafeFunctionSignature("ascii", listOf("text")),
      SafeFunctionSignature("chr", listOf("int4")),
      SafeFunctionSignature("sqrt", listOf("float8")),
      SafeFunctionSignature("sqrt", listOf("numeric")),
      SafeFunctionSignature("power", listOf("float8", "float8")),
      SafeFunctionSignature("power", listOf("numeric", "numeric")),
      SafeFunctionSignature("mod", listOf("int2", "int2")),
      SafeFunctionSignature("mod", listOf("int4", "int4")),
      SafeFunctionSignature("mod", listOf("int8", "int8")),
      SafeFunctionSignature("mod", listOf("numeric", "numeric")),
      SafeFunctionSignature("div", listOf("numeric", "numeric")),
      SafeFunctionSignature("trunc", listOf("float8")),
      SafeFunctionSignature("trunc", listOf("numeric")),
      SafeFunctionSignature("trunc", listOf("numeric", "int4")),
      SafeFunctionSignature("sign", listOf("float8")),
      SafeFunctionSignature("sign", listOf("numeric")),
      SafeFunctionSignature("cardinality", listOf("anyarray")),
      SafeFunctionSignature("array_to_string", listOf("anyarray", "text")),
      SafeFunctionSignature("array_to_string", listOf("anyarray", "text", "text")),
    )

    /**
     * (Source type, target type) pairs safe-listed for [neverNullForNonNullInputOids], keyed by
     * `pg_type.typname` on both sides and restricted to `pg_cast.castfunc`-backed casts (`pg_cast`
     * rows implemented by an I/O-conversion (`castmethod = 'i'`) rather than a `castfunc` — e.g.
     * `text` → `integer`, `text` → `date`, `text` → `boolean`, any `enum` ↔ `text` — need no entry
     * here at all: those surface in the node tree as [PgNodeExpression.CoerceViaIo], which
     * [NodeTreeNullabilityAnalyzer.isNonNull] already treats as safe by simply recursing into the
     * argument, since a type's own input function errors on unparseable text rather than returning
     * `null`). Array-to-array casts (e.g. `text[]` → `int4[]`) likewise need no entry: they surface
     * as [PgNodeExpression.ArrayCoerceExpr], which recurses the same way.
     *
     * Several entries are SELF-casts (`numeric` → `numeric`, `bpchar` → `bpchar`, `varchar` →
     * `varchar`, `timestamp` → `timestamp`, `timestamptz` → `timestamptz`, `time` → `time`,
     * `timetz` → `timetz`, `interval` → `interval`, `bit` → `bit`). These are not no-ops: they are
     * `castfunc`-backed typmod-ENFORCEMENT casts — e.g. applying a declared length limit
     * (`VARCHAR(5)`) or precision (`NUMERIC(10,2)`, `TIMESTAMP(3)`) to an already-typed value.
     * PostgreSQL represents "assign this literal to a length/precision-constrained column" as a
     * same-type cast through this function, not as a no-op RelabelType, which is why
     * `varchar(5)`-typed columns need `varchar` → `varchar` listed explicitly rather than falling
     * out of some other rule. Every one of these was verified to either preserve the value or
     * silently truncate/round it (`'abcdef'::varchar(3)` truncates to `'abc'`, never `null`) by the
     * same sweep as every other entry here.
     *
     * NOT keyed by source type alone: `jsonb` → `integer`/`numeric`/`boolean`/etc. and
     * `timestamp`/`timestamptz` → `time`/`timetz` are real `castfunc`-backed `pg_catalog` casts
     * that are NOT total (see [neverNullForNonNullInputOids]'s KDoc for the counterexamples), so a
     * blanket "every cast function is safe" rule — which is what this list replaced — silently
     * shipped both. Every pair actually listed here was verified total by
     * [SafeListSweepTest] against a live PostgreSQL instance using an edge-value corpus (`NaN`,
     * `Infinity`/`-Infinity`, `infinity`/`-infinity`, min/max integers, empty strings) — that sweep,
     * not hand-reasoning about any individual pair, is what licenses an entry here. When in doubt
     * whether a pair is total, the correct default is to leave it off; omission only widens a
     * result to nullable, it never narrows a truly nullable expression to non-null.
     */
    internal val NEVER_NULL_CAST_SIGNATURES: List<SafeCastSignature> = listOf(
      SafeCastSignature("int2", "int4"),
      SafeCastSignature("int2", "int8"),
      SafeCastSignature("int2", "numeric"),
      SafeCastSignature("int2", "float4"),
      SafeCastSignature("int2", "float8"),
      SafeCastSignature("int4", "int2"),
      SafeCastSignature("int4", "int8"),
      SafeCastSignature("int4", "numeric"),
      SafeCastSignature("int4", "float4"),
      SafeCastSignature("int4", "float8"),
      SafeCastSignature("int8", "int2"),
      SafeCastSignature("int8", "int4"),
      SafeCastSignature("int8", "numeric"),
      SafeCastSignature("int8", "float4"),
      SafeCastSignature("int8", "float8"),
      SafeCastSignature("numeric", "int2"),
      SafeCastSignature("numeric", "int4"),
      SafeCastSignature("numeric", "int8"),
      SafeCastSignature("numeric", "float4"),
      SafeCastSignature("numeric", "float8"),
      SafeCastSignature("numeric", "numeric"),
      SafeCastSignature("float4", "int2"),
      SafeCastSignature("float4", "int4"),
      SafeCastSignature("float4", "int8"),
      SafeCastSignature("float4", "numeric"),
      SafeCastSignature("float4", "float8"),
      SafeCastSignature("float8", "int2"),
      SafeCastSignature("float8", "int4"),
      SafeCastSignature("float8", "int8"),
      SafeCastSignature("float8", "numeric"),
      SafeCastSignature("float8", "float4"),
      SafeCastSignature("int4", "bool"),
      SafeCastSignature("bool", "int4"),
      SafeCastSignature("bool", "text"),
      SafeCastSignature("bool", "bpchar"),
      SafeCastSignature("bool", "varchar"),
      SafeCastSignature("bpchar", "bpchar"),
      SafeCastSignature("varchar", "varchar"),
      SafeCastSignature("int2", "bytea"),
      SafeCastSignature("int4", "bytea"),
      SafeCastSignature("int8", "bytea"),
      SafeCastSignature("bytea", "int2"),
      SafeCastSignature("bytea", "int4"),
      SafeCastSignature("bytea", "int8"),
      SafeCastSignature("int4", "money"),
      SafeCastSignature("int8", "money"),
      SafeCastSignature("numeric", "money"),
      SafeCastSignature("money", "numeric"),
      SafeCastSignature("int4", "bit"),
      SafeCastSignature("int8", "bit"),
      SafeCastSignature("date", "timestamptz"),
      SafeCastSignature("date", "timestamp"),
      SafeCastSignature("timestamp", "date"),
      SafeCastSignature("timestamp", "timestamptz"),
      SafeCastSignature("timestamp", "timestamp"),
      SafeCastSignature("timestamptz", "date"),
      SafeCastSignature("timestamptz", "timestamp"),
      SafeCastSignature("timestamptz", "timestamptz"),
      SafeCastSignature("time", "timetz"),
      SafeCastSignature("time", "interval"),
      SafeCastSignature("time", "time"),
      SafeCastSignature("timetz", "time"),
      SafeCastSignature("timetz", "timetz"),
      SafeCastSignature("interval", "time"),
      SafeCastSignature("interval", "interval"),
      SafeCastSignature("bit", "bit"),
    )

    /**
     * (Symbol, left operand type, right operand type) triples safe-listed for
     * [neverNullForNonNullInputOids], keyed by `pg_type.typname` on each side and restricted to
     * `oprnamespace = 'pg_catalog'` at query time. Deliberately excludes `-> ->> #> #>>` (JSON path
     * extraction — `null` on a missing key/path, even though `pg_proc.proisstrict` is `true` for
     * them) and `@@` (jsonpath match — `null` on a non-boolean result) by never listing them, and
     * excludes every `path`-typed overload of `+`/`-`/`*` (`path_add` etc. return `null`, not an
     * error, when either operand is a CLOSED path — see [neverNullForNonNullInputOids]'s KDoc).
     * There is no `jsonb`-typed entry on this list at all — [neverNullForNonNullInputOids]'s KDoc
     * covers the `jsonb` `'null'` literal counterexample in the context of CASTS, not operators.
     *
     * NOT keyed by symbol alone: a blanket "every `pg_catalog` overload of this symbol is safe"
     * rule — which is what this list replaced — is what let `path + path` through, since `+` is
     * also the totally-safe `int4 + int4`. Every triple actually listed here was verified total by
     * [SafeListSweepTest] against a live PostgreSQL instance using an edge-value corpus (`NaN`,
     * `Infinity`/`-Infinity`, `infinity`/`-infinity`, min/max integers, empty string, empty
     * array/range/multirange, unbounded range), including the containment
     * operators (`@>`, `<@`, `&&`) against concrete `integer[]`/`text[]`/`int4range`/`numrange`/
     * `tsrange`/`daterange`/`int4multirange` instantiations of their generic `anyarray`/`anyrange`/
     * `anymultirange`/`anyelement` `pg_operator` rows — a single generic row backs every concrete
     * instantiation, so one safe-listed triple per generic row covers all of them.
     */
    internal val NEVER_NULL_OPERATOR_SIGNATURES: List<SafeOperatorSignature> = listOf(
      SafeOperatorSignature("!~", "bpchar", "text"),
      SafeOperatorSignature("!~", "text", "text"),
      SafeOperatorSignature("!~*", "bpchar", "text"),
      SafeOperatorSignature("!~*", "text", "text"),
      SafeOperatorSignature("!~~", "bytea", "bytea"),
      SafeOperatorSignature("!~~", "bpchar", "text"),
      SafeOperatorSignature("!~~", "text", "text"),
      SafeOperatorSignature("!~~*", "bpchar", "text"),
      SafeOperatorSignature("!~~*", "text", "text"),
      SafeOperatorSignature("%", "int8", "int8"),
      SafeOperatorSignature("%", "int4", "int4"),
      SafeOperatorSignature("%", "numeric", "numeric"),
      SafeOperatorSignature("%", "int2", "int2"),
      SafeOperatorSignature("*", "int8", "int8"),
      SafeOperatorSignature("*", "int8", "int4"),
      SafeOperatorSignature("*", "int8", "int2"),
      SafeOperatorSignature("*", "float8", "float8"),
      SafeOperatorSignature("*", "float8", "float4"),
      SafeOperatorSignature("*", "int4", "int8"),
      SafeOperatorSignature("*", "int4", "int4"),
      SafeOperatorSignature("*", "int4", "int2"),
      SafeOperatorSignature("*", "numeric", "numeric"),
      SafeOperatorSignature("*", "float4", "float8"),
      SafeOperatorSignature("*", "float4", "float4"),
      SafeOperatorSignature("*", "int2", "int8"),
      SafeOperatorSignature("*", "int2", "int4"),
      SafeOperatorSignature("*", "int2", "int2"),
      SafeOperatorSignature("+", "int8", "int8"),
      SafeOperatorSignature("+", "int8", "int4"),
      SafeOperatorSignature("+", "int8", "int2"),
      SafeOperatorSignature("+", "float8", "float8"),
      SafeOperatorSignature("+", "float8", "float4"),
      SafeOperatorSignature("+", "int4", "int8"),
      SafeOperatorSignature("+", "int4", "int4"),
      SafeOperatorSignature("+", "int4", "int2"),
      SafeOperatorSignature("+", "numeric", "numeric"),
      SafeOperatorSignature("+", "float4", "float8"),
      SafeOperatorSignature("+", "float4", "float4"),
      SafeOperatorSignature("+", "int2", "int8"),
      SafeOperatorSignature("+", "int2", "int4"),
      SafeOperatorSignature("+", "int2", "int2"),
      SafeOperatorSignature("-", "int8", "int8"),
      SafeOperatorSignature("-", "int8", "int4"),
      SafeOperatorSignature("-", "int8", "int2"),
      SafeOperatorSignature("-", "float8", "float8"),
      SafeOperatorSignature("-", "float8", "float4"),
      SafeOperatorSignature("-", "int4", "int8"),
      SafeOperatorSignature("-", "int4", "int4"),
      SafeOperatorSignature("-", "int4", "int2"),
      SafeOperatorSignature("-", "numeric", "numeric"),
      SafeOperatorSignature("-", "float4", "float8"),
      SafeOperatorSignature("-", "float4", "float4"),
      SafeOperatorSignature("-", "int2", "int8"),
      SafeOperatorSignature("-", "int2", "int4"),
      SafeOperatorSignature("-", "int2", "int2"),
      // Unary (prefix) overloads — no left operand (pg_operator.oprleft = 0). Negation errors on
      // overflow (negating a type's own minimum value) rather than returning null; unary plus is
      // a total no-op.
      SafeOperatorSignature("+", null, "int8"),
      SafeOperatorSignature("+", null, "int4"),
      SafeOperatorSignature("+", null, "int2"),
      SafeOperatorSignature("+", null, "numeric"),
      SafeOperatorSignature("+", null, "float4"),
      SafeOperatorSignature("+", null, "float8"),
      SafeOperatorSignature("-", null, "int8"),
      SafeOperatorSignature("-", null, "int4"),
      SafeOperatorSignature("-", null, "int2"),
      SafeOperatorSignature("-", null, "numeric"),
      SafeOperatorSignature("-", null, "float4"),
      SafeOperatorSignature("-", null, "float8"),
      SafeOperatorSignature("-", null, "interval"),
      // Date/time arithmetic. Binary `+`/`-` between a `date`/`timestamp`/`timestamptz` and an
      // `int4`/`interval` (and their commuted forms) are distinct pg_operator rows from the
      // numeric `+`/`-` overloads already listed above, each independently swept.
      SafeOperatorSignature("+", "date", "int4"),
      SafeOperatorSignature("+", "int4", "date"),
      SafeOperatorSignature("-", "date", "int4"),
      SafeOperatorSignature("-", "date", "date"),
      SafeOperatorSignature("+", "timestamp", "interval"),
      SafeOperatorSignature("+", "interval", "timestamp"),
      SafeOperatorSignature("-", "timestamp", "interval"),
      SafeOperatorSignature("-", "timestamp", "timestamp"),
      SafeOperatorSignature("+", "timestamptz", "interval"),
      SafeOperatorSignature("+", "interval", "timestamptz"),
      SafeOperatorSignature("-", "timestamptz", "interval"),
      SafeOperatorSignature("-", "timestamptz", "timestamptz"),
      SafeOperatorSignature("/", "int8", "int8"),
      SafeOperatorSignature("/", "int8", "int4"),
      SafeOperatorSignature("/", "int8", "int2"),
      SafeOperatorSignature("/", "float8", "float8"),
      SafeOperatorSignature("/", "float8", "float4"),
      SafeOperatorSignature("/", "int4", "int8"),
      SafeOperatorSignature("/", "int4", "int4"),
      SafeOperatorSignature("/", "int4", "int2"),
      SafeOperatorSignature("/", "numeric", "numeric"),
      SafeOperatorSignature("/", "float4", "float8"),
      SafeOperatorSignature("/", "float4", "float4"),
      SafeOperatorSignature("/", "int2", "int8"),
      SafeOperatorSignature("/", "int2", "int4"),
      SafeOperatorSignature("/", "int2", "int2"),
      SafeOperatorSignature("<", "int8", "int8"),
      SafeOperatorSignature("<", "int8", "int4"),
      SafeOperatorSignature("<", "int8", "int2"),
      SafeOperatorSignature("<", "bool", "bool"),
      SafeOperatorSignature("<", "bytea", "bytea"),
      SafeOperatorSignature("<", "bpchar", "bpchar"),
      SafeOperatorSignature("<", "date", "date"),
      SafeOperatorSignature("<", "date", "timestamptz"),
      SafeOperatorSignature("<", "date", "timestamp"),
      SafeOperatorSignature("<", "float8", "float8"),
      SafeOperatorSignature("<", "float8", "float4"),
      SafeOperatorSignature("<", "int4", "int8"),
      SafeOperatorSignature("<", "int4", "int4"),
      SafeOperatorSignature("<", "int4", "int2"),
      SafeOperatorSignature("<", "interval", "interval"),
      SafeOperatorSignature("<", "numeric", "numeric"),
      SafeOperatorSignature("<", "float4", "float8"),
      SafeOperatorSignature("<", "float4", "float4"),
      SafeOperatorSignature("<", "int2", "int8"),
      SafeOperatorSignature("<", "int2", "int4"),
      SafeOperatorSignature("<", "int2", "int2"),
      SafeOperatorSignature("<", "text", "text"),
      SafeOperatorSignature("<", "timetz", "timetz"),
      SafeOperatorSignature("<", "time", "time"),
      SafeOperatorSignature("<", "timestamptz", "date"),
      SafeOperatorSignature("<", "timestamptz", "timestamptz"),
      SafeOperatorSignature("<", "timestamptz", "timestamp"),
      SafeOperatorSignature("<", "timestamp", "date"),
      SafeOperatorSignature("<", "timestamp", "timestamptz"),
      SafeOperatorSignature("<", "timestamp", "timestamp"),
      SafeOperatorSignature("<", "uuid", "uuid"),
      SafeOperatorSignature("<=", "int8", "int8"),
      SafeOperatorSignature("<=", "int8", "int4"),
      SafeOperatorSignature("<=", "int8", "int2"),
      SafeOperatorSignature("<=", "bool", "bool"),
      SafeOperatorSignature("<=", "bytea", "bytea"),
      SafeOperatorSignature("<=", "bpchar", "bpchar"),
      SafeOperatorSignature("<=", "date", "date"),
      SafeOperatorSignature("<=", "date", "timestamptz"),
      SafeOperatorSignature("<=", "date", "timestamp"),
      SafeOperatorSignature("<=", "float8", "float8"),
      SafeOperatorSignature("<=", "float8", "float4"),
      SafeOperatorSignature("<=", "int4", "int8"),
      SafeOperatorSignature("<=", "int4", "int4"),
      SafeOperatorSignature("<=", "int4", "int2"),
      SafeOperatorSignature("<=", "interval", "interval"),
      SafeOperatorSignature("<=", "numeric", "numeric"),
      SafeOperatorSignature("<=", "float4", "float8"),
      SafeOperatorSignature("<=", "float4", "float4"),
      SafeOperatorSignature("<=", "int2", "int8"),
      SafeOperatorSignature("<=", "int2", "int4"),
      SafeOperatorSignature("<=", "int2", "int2"),
      SafeOperatorSignature("<=", "text", "text"),
      SafeOperatorSignature("<=", "timetz", "timetz"),
      SafeOperatorSignature("<=", "time", "time"),
      SafeOperatorSignature("<=", "timestamptz", "date"),
      SafeOperatorSignature("<=", "timestamptz", "timestamptz"),
      SafeOperatorSignature("<=", "timestamptz", "timestamp"),
      SafeOperatorSignature("<=", "timestamp", "date"),
      SafeOperatorSignature("<=", "timestamp", "timestamptz"),
      SafeOperatorSignature("<=", "timestamp", "timestamp"),
      SafeOperatorSignature("<=", "uuid", "uuid"),
      SafeOperatorSignature("<>", "int8", "int8"),
      SafeOperatorSignature("<>", "int8", "int4"),
      SafeOperatorSignature("<>", "int8", "int2"),
      SafeOperatorSignature("<>", "bool", "bool"),
      SafeOperatorSignature("<>", "bytea", "bytea"),
      SafeOperatorSignature("<>", "bpchar", "bpchar"),
      SafeOperatorSignature("<>", "date", "date"),
      SafeOperatorSignature("<>", "date", "timestamptz"),
      SafeOperatorSignature("<>", "date", "timestamp"),
      SafeOperatorSignature("<>", "float8", "float8"),
      SafeOperatorSignature("<>", "float8", "float4"),
      SafeOperatorSignature("<>", "int4", "int8"),
      SafeOperatorSignature("<>", "int4", "int4"),
      SafeOperatorSignature("<>", "int4", "int2"),
      SafeOperatorSignature("<>", "interval", "interval"),
      SafeOperatorSignature("<>", "numeric", "numeric"),
      SafeOperatorSignature("<>", "float4", "float8"),
      SafeOperatorSignature("<>", "float4", "float4"),
      SafeOperatorSignature("<>", "int2", "int8"),
      SafeOperatorSignature("<>", "int2", "int4"),
      SafeOperatorSignature("<>", "int2", "int2"),
      SafeOperatorSignature("<>", "text", "text"),
      SafeOperatorSignature("<>", "timetz", "timetz"),
      SafeOperatorSignature("<>", "time", "time"),
      SafeOperatorSignature("<>", "timestamptz", "date"),
      SafeOperatorSignature("<>", "timestamptz", "timestamptz"),
      SafeOperatorSignature("<>", "timestamptz", "timestamp"),
      SafeOperatorSignature("<>", "timestamp", "date"),
      SafeOperatorSignature("<>", "timestamp", "timestamptz"),
      SafeOperatorSignature("<>", "timestamp", "timestamp"),
      SafeOperatorSignature("<>", "uuid", "uuid"),
      SafeOperatorSignature("=", "int8", "int8"),
      SafeOperatorSignature("=", "int8", "int4"),
      SafeOperatorSignature("=", "int8", "int2"),
      SafeOperatorSignature("=", "bool", "bool"),
      SafeOperatorSignature("=", "bytea", "bytea"),
      SafeOperatorSignature("=", "bpchar", "bpchar"),
      SafeOperatorSignature("=", "date", "date"),
      SafeOperatorSignature("=", "date", "timestamptz"),
      SafeOperatorSignature("=", "date", "timestamp"),
      SafeOperatorSignature("=", "float8", "float8"),
      SafeOperatorSignature("=", "float8", "float4"),
      SafeOperatorSignature("=", "int4", "int8"),
      SafeOperatorSignature("=", "int4", "int4"),
      SafeOperatorSignature("=", "int4", "int2"),
      SafeOperatorSignature("=", "interval", "interval"),
      SafeOperatorSignature("=", "numeric", "numeric"),
      SafeOperatorSignature("=", "float4", "float8"),
      SafeOperatorSignature("=", "float4", "float4"),
      SafeOperatorSignature("=", "int2", "int8"),
      SafeOperatorSignature("=", "int2", "int4"),
      SafeOperatorSignature("=", "int2", "int2"),
      SafeOperatorSignature("=", "text", "text"),
      SafeOperatorSignature("=", "timetz", "timetz"),
      SafeOperatorSignature("=", "time", "time"),
      SafeOperatorSignature("=", "timestamptz", "date"),
      SafeOperatorSignature("=", "timestamptz", "timestamptz"),
      SafeOperatorSignature("=", "timestamptz", "timestamp"),
      SafeOperatorSignature("=", "timestamp", "date"),
      SafeOperatorSignature("=", "timestamp", "timestamptz"),
      SafeOperatorSignature("=", "timestamp", "timestamp"),
      SafeOperatorSignature("=", "uuid", "uuid"),
      SafeOperatorSignature(">", "int8", "int8"),
      SafeOperatorSignature(">", "int8", "int4"),
      SafeOperatorSignature(">", "int8", "int2"),
      SafeOperatorSignature(">", "bool", "bool"),
      SafeOperatorSignature(">", "bytea", "bytea"),
      SafeOperatorSignature(">", "bpchar", "bpchar"),
      SafeOperatorSignature(">", "date", "date"),
      SafeOperatorSignature(">", "date", "timestamptz"),
      SafeOperatorSignature(">", "date", "timestamp"),
      SafeOperatorSignature(">", "float8", "float8"),
      SafeOperatorSignature(">", "float8", "float4"),
      SafeOperatorSignature(">", "int4", "int8"),
      SafeOperatorSignature(">", "int4", "int4"),
      SafeOperatorSignature(">", "int4", "int2"),
      SafeOperatorSignature(">", "interval", "interval"),
      SafeOperatorSignature(">", "numeric", "numeric"),
      SafeOperatorSignature(">", "float4", "float8"),
      SafeOperatorSignature(">", "float4", "float4"),
      SafeOperatorSignature(">", "int2", "int8"),
      SafeOperatorSignature(">", "int2", "int4"),
      SafeOperatorSignature(">", "int2", "int2"),
      SafeOperatorSignature(">", "text", "text"),
      SafeOperatorSignature(">", "timetz", "timetz"),
      SafeOperatorSignature(">", "time", "time"),
      SafeOperatorSignature(">", "timestamptz", "date"),
      SafeOperatorSignature(">", "timestamptz", "timestamptz"),
      SafeOperatorSignature(">", "timestamptz", "timestamp"),
      SafeOperatorSignature(">", "timestamp", "date"),
      SafeOperatorSignature(">", "timestamp", "timestamptz"),
      SafeOperatorSignature(">", "timestamp", "timestamp"),
      SafeOperatorSignature(">", "uuid", "uuid"),
      SafeOperatorSignature(">=", "int8", "int8"),
      SafeOperatorSignature(">=", "int8", "int4"),
      SafeOperatorSignature(">=", "int8", "int2"),
      SafeOperatorSignature(">=", "bool", "bool"),
      SafeOperatorSignature(">=", "bytea", "bytea"),
      SafeOperatorSignature(">=", "bpchar", "bpchar"),
      SafeOperatorSignature(">=", "date", "date"),
      SafeOperatorSignature(">=", "date", "timestamptz"),
      SafeOperatorSignature(">=", "date", "timestamp"),
      SafeOperatorSignature(">=", "float8", "float8"),
      SafeOperatorSignature(">=", "float8", "float4"),
      SafeOperatorSignature(">=", "int4", "int8"),
      SafeOperatorSignature(">=", "int4", "int4"),
      SafeOperatorSignature(">=", "int4", "int2"),
      SafeOperatorSignature(">=", "interval", "interval"),
      SafeOperatorSignature(">=", "numeric", "numeric"),
      SafeOperatorSignature(">=", "float4", "float8"),
      SafeOperatorSignature(">=", "float4", "float4"),
      SafeOperatorSignature(">=", "int2", "int8"),
      SafeOperatorSignature(">=", "int2", "int4"),
      SafeOperatorSignature(">=", "int2", "int2"),
      SafeOperatorSignature(">=", "text", "text"),
      SafeOperatorSignature(">=", "timetz", "timetz"),
      SafeOperatorSignature(">=", "time", "time"),
      SafeOperatorSignature(">=", "timestamptz", "date"),
      SafeOperatorSignature(">=", "timestamptz", "timestamptz"),
      SafeOperatorSignature(">=", "timestamptz", "timestamp"),
      SafeOperatorSignature(">=", "timestamp", "date"),
      SafeOperatorSignature(">=", "timestamp", "timestamptz"),
      SafeOperatorSignature(">=", "timestamp", "timestamp"),
      SafeOperatorSignature(">=", "uuid", "uuid"),
      // Unary (prefix) bitwise-complement overloads of "~" — a DIFFERENT operator from the
      // binary regex-match "~" immediately below; PostgreSQL overloads the symbol. Complementing
      // any bit pattern of a fixed-width representation always fits back in that same width, so
      // there is no overflow case the way there is for negation. `macaddr`/`macaddr8`/`inet`
      // overloads of unary "~" also exist in pg_catalog but are deliberately NOT listed — network
      // address types are outside the families this fix targets, so an expression using them
      // stays conservatively nullable rather than being swept and verified.
      SafeOperatorSignature("~", null, "int8"),
      SafeOperatorSignature("~", null, "int4"),
      SafeOperatorSignature("~", null, "int2"),
      SafeOperatorSignature("~", null, "bit"),
      SafeOperatorSignature("~", "bpchar", "text"),
      SafeOperatorSignature("~", "text", "text"),
      SafeOperatorSignature("~*", "bpchar", "text"),
      SafeOperatorSignature("~*", "text", "text"),
      SafeOperatorSignature("~~", "bytea", "bytea"),
      SafeOperatorSignature("~~", "bpchar", "text"),
      SafeOperatorSignature("~~", "text", "text"),
      SafeOperatorSignature("~~*", "bpchar", "text"),
      SafeOperatorSignature("~~*", "text", "text"),
      SafeOperatorSignature("||", "bytea", "bytea"),
      SafeOperatorSignature("||", "text", "text"),
      SafeOperatorSignature("&&", "anymultirange", "anymultirange"),
      SafeOperatorSignature("&&", "anymultirange", "anyrange"),
      SafeOperatorSignature("&&", "anyrange", "anymultirange"),
      SafeOperatorSignature("&&", "anyrange", "anyrange"),
      SafeOperatorSignature("<@", "anymultirange", "anymultirange"),
      SafeOperatorSignature("<@", "anymultirange", "anyrange"),
      SafeOperatorSignature("<@", "anyrange", "anymultirange"),
      SafeOperatorSignature("<@", "anyrange", "anyrange"),
      SafeOperatorSignature("@>", "anymultirange", "anyelement"),
      SafeOperatorSignature("@>", "anymultirange", "anymultirange"),
      SafeOperatorSignature("@>", "anymultirange", "anyrange"),
      SafeOperatorSignature("@>", "anyrange", "anyelement"),
      SafeOperatorSignature("@>", "anyrange", "anymultirange"),
      SafeOperatorSignature("@>", "anyrange", "anyrange"),
      SafeOperatorSignature("&&", "anyarray", "anyarray"),
      SafeOperatorSignature("<@", "anyarray", "anyarray"),
      SafeOperatorSignature("@>", "anyarray", "anyarray"),
    )
  }
}

/**
 * One `pg_catalog` function signature safe-listed as TOTAL on non-null input — see
 * [PgCatalogLoader.NEVER_NULL_FUNCTION_SIGNATURES] for the audited list and the reasoning behind
 * each entry.
 *
 * @property name The unqualified `pg_proc.proname`.
 * @property argumentTypeNames The exact, ordered list of `pg_type.typname` values for this
 *   overload's declared argument types — e.g. `listOf("text", "text")` for the two-argument `text`
 *   overload of a name. Empty for a zero-argument function (e.g. `now()`).
 */
internal data class SafeFunctionSignature(val name: String, val argumentTypeNames: List<String>)

/**
 * One `pg_catalog` cast signature safe-listed as TOTAL on non-null input — see
 * [PgCatalogLoader.NEVER_NULL_CAST_SIGNATURES] for the audited list and the reasoning behind each
 * entry.
 *
 * @property sourceTypeName The `pg_type.typname` of `pg_cast.castsource`.
 * @property targetTypeName The `pg_type.typname` of `pg_cast.casttarget`.
 */
internal data class SafeCastSignature(val sourceTypeName: String, val targetTypeName: String)

/**
 * One `pg_catalog` operator signature safe-listed as TOTAL on non-null input — see
 * [PgCatalogLoader.NEVER_NULL_OPERATOR_SIGNATURES] for the audited list and the reasoning behind
 * each entry.
 *
 * @property symbol The `pg_operator.oprname`.
 * @property leftTypeName The `pg_type.typname` of `pg_operator.oprleft`, or `null` if the operator
 *   has no left operand (a right-unary / prefix operator, `pg_operator.oprleft = 0`).
 * @property rightTypeName The `pg_type.typname` of `pg_operator.oprright`, or `null` if the
 *   operator has no right operand (a left-unary / postfix operator, `pg_operator.oprright = 0`).
 */
internal data class SafeOperatorSignature(val symbol: String, val leftTypeName: String?, val rightTypeName: String?)

/**
 * Metadata about a PostgreSQL function overload from `pg_proc`.
 *
 * Functions may be overloaded (same name, different argument counts), so [PgCatalogLoader] stores
 * a list of overloads per function name, matched by argument count at inference time.
 *
 * @property argNames The formal argument names. Empty when the function has no named arguments
 *   (common for C-implemented extension functions like pgcrypto's `digest`). The caller uses the
 *   function name itself as a fallback in that case.
 * @property isStrict Whether the function returns `null` when any argument is `null` (`STRICT` /
 *   `RETURNS NULL ON NULL INPUT`). When `true`, a call with all non-null arguments is non-null.
 */
internal data class FunctionOverload(val argNames: List<String>, val isStrict: Boolean)
