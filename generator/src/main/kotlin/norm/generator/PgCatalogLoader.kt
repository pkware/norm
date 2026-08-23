package norm.generator

import org.intellij.lang.annotations.Language
import java.sql.Connection
import java.sql.SQLException

/**
 * Loads schema metadata from PostgreSQL's system catalogs via JDBC.
 *
 * Provides a lazy-loaded cache of [functionOverloads] computed once per instance, plus
 * on-demand queries for enums, domains, column comments, and stored procedures.
 *
 * @param connection An open JDBC connection to a PostgreSQL database with the schema applied.
 */
internal class PgCatalogLoader(internal val connection: Connection) {

  internal val nodeTreeParser = PgNodeTreeParser()

  private val pgMajorVersion = connection.metaData.databaseMajorVersion

  /** See [ColumnNullabilityAnalyzer]'s own KDoc for why this is a separate collaborator. */
  private val nullabilityAnalyzer = ColumnNullabilityAnalyzer(this)

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
   * from each other's answer for the same column.
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
  internal val isStrictFunction: (Int) -> Boolean = { oid -> functionStrictnessByOid[oid] == true }

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
   * Determines which result columns of [sql] can be `NULL`.
   *
   * Routes every statement — a plain `SELECT` exactly the same as a data-modifying statement or
   * CTE — through [queryColumnNullabilityViaProsqlbody]. `prosqlbody` holds the identical
   * post-parse-analysis `{QUERY ...}` shape `CREATE VIEW`'s `pg_rewrite.ev_action` does (see
   * [queryColumnNullabilityViaProsqlbody]'s KDoc), so a plain `SELECT` needs no separate route of
   * its own: a live sweep across PostgreSQL 16/17/18 of every shape `CREATE VIEW` accepts but a
   * SQL-standard function body might plausibly reject or
   * reinterpret — `UNION`/`INTERSECT`/`EXCEPT`, `WITH RECURSIVE`, `ORDER BY`/`LIMIT`/`OFFSET`,
   * `FOR UPDATE`/`FOR SHARE`, `DISTINCT ON`, a `VALUES` list, a set-returning function in the
   * target list, `LATERAL`, `TABLESAMPLE`, `WITH ORDINALITY`, and a query selecting from another
   * view — found no disagreement on any of them (see [QueryAnalysisTest]'s `SELECT DISTINCT ON`
   * and `TABLESAMPLE` cases, the two shapes the corpus had no other coverage for; every other
   * shape is exercised elsewhere in [QueryAnalysisTest] and in the `test-scenarios` golden-file
   * corpus, which pins the exact generated Kotlin type derived from this function's answer).
   *
   * @return one boolean per result column (`true` means nullable). If
   *   [queryColumnNullabilityViaProsqlbody] cannot produce an answer at all — a probe failure, or
   *   [sql] is a `MERGE` whose `USING` clause has more than one source relation of its own (see
   *   [mergeAbsentVarnos]'s KDoc) — every real result column (via
   *   `PreparedStatement.getMetaData()`, the only source of a column count this deep into a
   *   fallback) is reported nullable: the safe direction, and consistent with every other
   *   fallback in this file. A statement with no result columns at all (`INSERT`/`UPDATE`/
   *   `DELETE`/`MERGE` with no `RETURNING`) naturally reports an EMPTY list here, since its real
   *   column count is `0` — there is nothing for a caller to treat as nullable OR not.
   */
  fun queryColumnNullability(@Language("PostgreSQL") sql: String): List<Boolean> =
    queryColumnNullabilityViaProsqlbody(sql) ?: List(realColumnCount(sql)) { true }

  /**
   * The real number of result columns [sql] produces, via `PreparedStatement.getMetaData()` — used
   * ONLY by [queryColumnNullability]'s final, otherwise-blind fallback to size its all-nullable
   * default correctly (in particular, `0` for a `RETURNING`-less `INSERT`/`UPDATE`/`DELETE`/
   * `MERGE`, which must report an EMPTY list, not a list of one `true` per some guessed count).
   */
  private fun realColumnCount(@Language("PostgreSQL") sql: String): Int = try {
    connection.prepareStatement(sql).use { it.metaData?.columnCount ?: 0 }
  } catch (_: SQLException) {
    0
  }

  /**
   * Delegates to [ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody] -- see its KDoc
   * for the full contract. Exposed here (rather than only on the analyzer) because this is the
   * entry point `queryColumnNullability` and this class's own tests call.
   */
  internal fun queryColumnNullabilityViaProsqlbody(@Language("PostgreSQL") sql: String): List<Boolean>? =
    nullabilityAnalyzer.queryColumnNullabilityViaProsqlbody(sql)

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
