package norm.generator

import org.intellij.lang.annotations.Language
import java.sql.SQLException
import java.util.UUID

/**
 * Drives per-column nullability analysis for a SQL query on behalf of [loader]: fetching the
 * query's own parsed node tree (via `prosqlbody` or a probe function, see
 * [queryColumnNullabilityViaProsqlbody]'s own KDoc), then recursively resolving CTE bodies,
 * subqueries, and `MERGE` actions to feed [NodeTreeNullabilityAnalyzer] the source-column
 * not-null information it needs to evaluate each result column's expression.
 *
 * Split out from [loader]'s own catalog-loading responsibilities (schema introspection, function
 * metadata, safe-list lookups) because this is a distinct concern: [loader] answers "what does the
 * catalog say", while this class answers "is this specific query's result column nullable" by
 * combining catalog answers with the query's own parsed structure.
 */
internal class ColumnNullabilityAnalyzer(private val loader: PgCatalogLoader) {
  private val connection get() = loader.connection
  private val nodeTreeParser get() = loader.nodeTreeParser
  private val columnNotNullByRelidAndAttnum get() = loader.columnNotNullByRelidAndAttnum
  private val viewColumnNotNullByRelidAndAttnum get() = loader.viewColumnNotNullByRelidAndAttnum
  private val aggregateHasNonNullInitialValue get() = loader.aggregateHasNonNullInitialValue
  private val alwaysNonNullFunctionOids get() = loader.alwaysNonNullFunctionOids
  private val neverNullForNonNullInputOids get() = loader.neverNullForNonNullInputOids
  private val lagLeadWithDefaultOids get() = loader.lagLeadWithDefaultOids
  private val immutableFunctionOids get() = loader.immutableFunctionOids
  private val nonNullIffFirstArgumentNonNullFunctionOids get() = loader.nonNullIffFirstArgumentNonNullFunctionOids
  private val isStrictFunction get() = loader.isStrictFunction

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
   * For [nodeTree]'s OWN outermost statement — never recursing into a CTE it declares; each CTE's
   * own body resolves its own MERGE independently (see [analyzeCteBodyNullability]) — determines
   * which of its two base-table relations (identified by `:rtable` varno) can be entirely absent
   * for some result row, via [explainMergeSideNullability] rather than `:mergeActionList`/text
   * inspection.
   *
   * A `MERGE`'s match-optionality (`WHEN NOT MATCHED BY SOURCE`, `WHEN NOT MATCHED [BY TARGET]
   * THEN INSERT`) is invisible to `:varnullingrels` — verified live: a `MERGE ... WHEN NOT MATCHED
   * BY SOURCE THEN DELETE RETURNING src.col` has an EMPTY `:varnullingrels` on `src.col`'s `Var`,
   * identical to an ordinary, always-present reference. [explainMergeSideNullability]'s KDoc has
   * the full reasoning for why `EXPLAIN`'s own join type answers this precisely instead.
   *
   * @param sql the EXACT (already sentinel-substituted) statement text to run `EXPLAIN` against —
   *   the WHOLE top-level statement, including any leading `WITH` clause, so a `MERGE` nested
   *   inside a CTE resolves through the SAME call as a top-level one, keyed by ITS OWN
   *   target/source relation names
   * @return an EMPTY map when [nodeTree]'s own outermost statement is not a `MERGE` at all; a map
   *   from varno to whether THAT relation can be entirely absent (containing the target and/or
   *   source varno, per [MergeSideNullability]) when it IS a `MERGE` and `EXPLAIN` successfully
   *   attributed the join; `null` when it's a `MERGE` but `EXPLAIN` could not resolve it (e.g. a
   *   `USING` clause with more than one relation of its own) — the caller must then treat this
   *   `MERGE` as entirely untrustworthy, never guessing at a partial answer
   */
  private fun mergeAbsentVarnos(
    nodeTree: String,
    rangeTable: Map<Int, Int>,
    @Language("PostgreSQL") sql: String,
  ): Map<Int, Boolean>? {
    if (nodeTreeParser.parseCommandType(nodeTree) != PgNodeTreeParser.COMMAND_TYPE_MERGE) return emptyMap()
    val targetVarno = nodeTreeParser.parseResultRelation(nodeTree)
    // A RETURNING list that only reads the target relation's own columns, or OLD/NEW references,
    // never needs EXPLAIN's resolution at all — see containsVarOutsideRelation's KDoc. Skipping it
    // here matters beyond saving an EXPLAIN round trip: a MERGE whose USING source is not a plain
    // base table (e.g. a VALUES list or a subquery) can never be resolved below, but that must not
    // block a RETURNING list that never depended on knowing which side of that join is nullable.
    val returningEntries = nodeTreeParser.parseReturningList(nodeTree)
    if (returningEntries.none { NodeTreeNullabilityAnalyzer.containsVarOutsideRelation(it.expression, targetVarno) }) {
      return emptyMap()
    }
    val targetRelid = rangeTable[targetVarno] ?: return null
    // A simple `MERGE INTO target USING source ON ...` has exactly one OTHER base-table :rtable
    // entry besides the target — the source. A `USING` clause with more than one relation of its
    // own (e.g. a join or subquery source) has no single relation this method can attribute a
    // join side to, so it bails rather than guess.
    val sourceEntries = rangeTable.filterKeys { it != targetVarno }
    if (sourceEntries.size != 1) return null
    val (sourceVarno, sourceRelid) = sourceEntries.entries.single()
    val targetName = resolveTableName(targetRelid) ?: return null
    val sourceName = resolveTableName(sourceRelid) ?: return null
    val mapping = explainMergeSideNullability(connection, sql, targetName, sourceName) ?: return null
    return buildMap {
      put(targetVarno, mapping.targetCanBeAbsent)
      put(sourceVarno, mapping.sourceCanBeAbsent)
    }
  }

  /**
   * Determines which result columns of a SQL query can be NULL, using full expression evaluation,
   * by way of PostgreSQL's `prosqlbody` — the analyzed query tree PostgreSQL stores for a
   * SQL-standard (`BEGIN ATOMIC ... END`) function body.
   *
   * `prosqlbody` holds the same post-parse-analysis `{QUERY ...}` node shape `pg_rewrite.ev_action`
   * does (neither is rewriter-expanded, so nested views stay relation RTEs in both), but — unlike
   * `CREATE VIEW` — PostgreSQL populates it for `UPDATE`/`DELETE`/`MERGE ... RETURNING` and for
   * data-modifying CTEs, because only `CREATE VIEW` itself rejects a data-modifying statement, not
   * a SQL-standard function body. [analyzeNodeTree] reads the same field shape either way, since
   * [PgNodeTreeParser]'s extraction methods locate each field by a depth-one scan starting at the
   * first unescaped `{`, ignoring whatever wrapping parentheses precede or follow it.
   *
   * The function is created with ZERO arguments: a real `$n` parameter would appear as a `PARAM`
   * node in the tree, which [NodeTreeNullabilityAnalyzer] has no case for and would fall through to
   * its `Unknown` (safe-nullable) handling for every expression that touches it — silently
   * widening every parameter-touching column. [sql]'s own `?` placeholders are therefore replaced
   * with typed non-null sentinel literals internally, via [buildViewSqlWithSentinels], before this
   * method is ever invoked.
   *
   * A statement with no result columns at all (an `INSERT`/`UPDATE`/`DELETE`/`MERGE` without
   * `RETURNING`) fails PostgreSQL's `RETURNS SETOF record` check on function creation — there is
   * nothing to probe, and the [SQLException] is caught here rather than propagated.
   *
   * @param sql the SQL query or DML statement to analyze; any `?` parameter placeholder is
   *   replaced with a typed non-null sentinel literal internally (see [buildViewSqlWithSentinels])
   * @return one boolean per result column in `SELECT`/`RETURNING` order (`true` means nullable), or
   *   `null` if [sql] has no result columns to probe or the probe itself failed for any reason —
   *   the caller must treat `null` as "this path has no answer", never as "zero columns."
   */
  internal fun queryColumnNullabilityViaProsqlbody(@Language("PostgreSQL") sql: String): List<Boolean>? {
    val substitutedSql = buildViewSqlWithSentinels(sql) ?: replaceParameterPlaceholders(sql)
    val functionName = "norm_nullability_${UUID.randomUUID().toString().replace("-", "")}"
    return try {
      connection.createStatement().use { stmt ->
        stmt.execute(
          "CREATE FUNCTION pg_temp.$functionName() RETURNS SETOF record LANGUAGE sql " +
            // The newline before "; END" is load-bearing, not style: [substitutedSql] is caller-
            // supplied SQL text that can legitimately end in a trailing `--` line comment (ordinary
            // in a queries.sql), which extends to end of LINE. Without a newline separating it from
            // "; END", the comment swallows the terminator too, and PostgreSQL sees unterminated
            // input instead of a syntax error naming the real cause. Verified live.
            "BEGIN ATOMIC $substitutedSql\n; END",
        )
      }
      try {
        val nodeTree = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            "SELECT prosqlbody::text FROM pg_proc " +
              // "pg_temp" is a per-session ALIAS, not a literal schema name — the real catalog row
              // is named "pg_temp_N", so 'pg_temp'::regnamespace fails to resolve at all (verified
              // live: "ERROR: schema "pg_temp" does not exist"). pg_my_temp_schema() returns the
              // current session's actual temp schema OID directly.
              "WHERE proname = '$functionName' AND pronamespace = pg_my_temp_schema()",
          ).use { rs ->
            check(rs.next()) { "No pg_proc row found for probe function $functionName" }
            rs.getString(1)
          }
        }
        val rangeTable = nodeTreeParser.parseRangeTable(nodeTree)
        val mergeAbsent = mergeAbsentVarnos(nodeTree, rangeTable, substitutedSql) ?: return null
        // '?' in sql, not substitutedSql: a sentinel-substituted CONST is byte-identical to a
        // hand-written literal once embedded in the SQL text — the parsed tree retains no memory
        // of which one it was. trustAssignedExpressions=false whenever the ORIGINAL sql had ANY
        // parameter blocks analyzeNodeTree's :targetList-to-:returningList substitution (see its
        // KDoc) for the WHOLE statement, not just the specific assignment a parameter feeds,
        // because there is no structural way from here to tell which assignment(s) it was.
        analyzeNodeTree(
          nodeTree,
          applyQualNarrowing = true,
          sql = substitutedSql,
          trustAssignedExpressions = '?' !in sql,
          mergeAbsentVarnos = mergeAbsent,
        )
      } finally {
        connection.createStatement().use { stmt ->
          stmt.execute("DROP FUNCTION IF EXISTS pg_temp.$functionName()")
        }
      }
    } catch (_: SQLException) {
      null
    }
  }

  /**
   * Computes per-column nullability from [nodeTree] — the `pg_rewrite.ev_action` text of a
   * temporary view, or the `pg_proc.prosqlbody` text of a temporary probe function; both share the
   * same post-parse-analysis `{QUERY ...}` node shape (see [queryColumnNullabilityViaProsqlbody]'s
   * KDoc).
   *
   * Reads the `:targetList` first — this covers every plain `SELECT`, including one that reaches
   * this function only because it CONTAINS a data-modifying CTE (the outer statement is still a
   * `SELECT`, so PostgreSQL's own `CREATE VIEW` restriction, and by extension nothing here, ever
   * blocked it). Falls back to `:returningList` when the outer statement's OWN target list is
   * empty — true only for a topmost `UPDATE`/`DELETE`/`MERGE ... RETURNING`: a plain `SELECT`
   * always has a non-empty target list, or [connection] would have rejected it as a query with no
   * result columns before ever reaching this point.
   *
   * @param applyQualNarrowing When `false`, disables `WHERE`-clause qual narrowing
   *   ([NodeTreeNullabilityAnalyzer.qualProvenNonNullVars]) for this entire call — the top-level
   *   query AND every nested CTE body and subquery reached from it. Threaded through rather than a
   *   fixed `true` so a future caller with a rewritten body whose `WHERE`/`ON` predicate no longer
   *   corresponds to what `RETURNING` sees can suppress it; every current caller passes `true`.
   */
  private fun analyzeNodeTree(
    nodeTree: String,
    applyQualNarrowing: Boolean,
    @Language("PostgreSQL") sql: String,
    trustAssignedExpressions: Boolean = true,
    mergeAbsentVarnos: Map<Int, Boolean> = emptyMap(),
  ): List<Boolean> {
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
    val subqueryColumnNotNull = buildSubqueryColumnNotNull(nodeTree, applyQualNarrowing, sql)
    val cteColumnNotNull = buildCteColumnNotNull(nodeTree, applyQualNarrowing, sql)
    // A non-zero :resultRelation means this is an INSERT/UPDATE/DELETE/MERGE, not a SELECT — see
    // parseResultRelation's KDoc. Its :targetList holds the value expressions being WRITTEN to
    // each explicitly-assigned column of the target relation (keyed by :resno = the column's
    // attribute number), which is exactly what a :returningList Var referencing that SAME
    // (resultRelationVarno, attno) pair actually reads back — NOT the column's general catalog
    // constraint, which says nothing about what THIS statement is about to write. See
    // targetListByResno's use below.
    val resultRelationVarno = nodeTreeParser.parseResultRelation(nodeTree)
    val targetListByResno = if (resultRelationVarno == 0 || !trustAssignedExpressions) {
      // !trustAssignedExpressions means the ORIGINAL sql (before sentinel substitution) contained
      // a `?` parameter placeholder somewhere — see queryColumnNullabilityViaProsqlbody's call
      // site KDoc. A sentinel-substituted CONST is byte-identical, in the parsed tree, to a
      // hand-written literal: there is no structural signal left to tell "the caller supplied
      // this at runtime, and could supply NULL" from "the query text itself guarantees this value"
      // for any SPECIFIC assignment, so trusting :targetList at all is unsafe for the WHOLE
      // statement once ANY parameter exists anywhere in it. Verified live: `INSERT INTO t(name)
      // VALUES (?) RETURNING name` reports NOT NULL if the sentinel substitution is trusted here,
      // even though the caller can bind an actual `NULL` for that exact parameter.
      emptyMap()
    } else {
      // rangeTable[resultRelationVarno] is only present for an ordinary base-table target (rtekind
      // 0) — never null for a real INSERT/UPDATE/DELETE/MERGE, since PostgreSQL requires a real
      // relation to write to, but defensively treated as "substitution unsafe" (empty map) rather
      // than trusting an assignment against a target this class cannot even identify.
      val targetRelid = rangeTable[resultRelationVarno]
      if (targetRelid != null && isSubstitutionSafeForRelation(targetRelid)) {
        nodeTreeParser.parseTargetList(nodeTree).associate { it.resultNumber to it.expression }
      } else {
        emptyMap()
      }
    }
    // GROUPING SETS/CUBE/ROLLUP null-extend grouping keys AFTER the WHERE clause has already
    // filtered rows, so a qual can never prove a grouped result column non-null. This matters
    // even for a NOT NULL base column, because null-extension overrides the base column's own
    // constraint. Suppress qual narrowing for the entire block rather than trying to map a
    // (possibly expression) grouping key back to its null-extended leaf Vars — that is more
    // machinery than this warrants, and a subtle mistake there would reintroduce an unsound
    // narrowing. This is conservative by construction: every non-key output column of a
    // grouping-sets query is an aggregate, so suppressing narrowing here costs nothing real.
    //
    // A non-zero resultRelationVarno (an UPDATE/DELETE/MERGE) suppresses narrowing for a similar
    // reason: a qual that looks like it proves a RETURNING column non-null may in fact be testing
    // the value the statement's own SET clause (or, for MERGE, an update/insert action) is about
    // to overwrite.
    val qualNotNullVars = if (applyQualNarrowing && !hasGroupingSets && resultRelationVarno == 0) {
      NodeTreeNullabilityAnalyzer.qualProvenNonNullVars(nodeTree, isStrictFunction)
    } else {
      emptySet()
    }
    val plainIsSourceColumnNotNull = { varno: Int, varattno: Int ->
      if (mergeAbsentVarnos[varno] == true) {
        // A MERGE relation EXPLAIN determined can be entirely absent for some result row (see
        // mergeAbsentVarnos' KDoc) can never be proven non-null here, regardless of what a qual or
        // this column's own catalog constraint would otherwise say — those both describe the
        // relation's rows WHEN PRESENT, which says nothing about whether this specific result row
        // has one at all.
        false
      } else if (isProvenByQuals(qualNotNullVars, groupRteMap, varno, varattno)) {
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
    val forceNewNullable = forcesNewNullable(nodeTree)
    val analyzer = buildAnalyzer(
      hasGroupingSets = hasGroupingSets,
      forceNewNullable = forceNewNullable,
      isSourceColumnNotNull = plainIsSourceColumnNotNull,
    )
    // :returningList must be checked FIRST, not as a fallback for an empty :targetList: an INSERT
    // or UPDATE's OWN :targetList holds the value expressions being WRITTEN to each assigned
    // column — a completely different, and typically shorter or differently-shaped, list than its
    // RETURNING projection — so it is very often non-empty even when :returningList is what this
    // call actually needs to read (verified live: `INSERT INTO t(name) VALUES ('test') RETURNING
    // *` against `t(id, name)` has a one-entry :targetList for "name" alone, but a two-entry
    // :returningList for "id, name"). A plain `SELECT` never populates :returningList at all, so
    // this ordering only ever matters for the DML-with-RETURNING case
    // [queryColumnNullabilityViaProsqlbody] reaches at the top level.
    val returningEntries = nodeTreeParser.parseReturningList(nodeTree)
    if (returningEntries.isNotEmpty()) {
      // A SEPARATE analyzer whose isSourceColumnNotNull substitutes a Var referencing
      // (resultRelationVarno, resno) with the ASSIGNED expression's own nullability — evaluated by
      // the PLAIN analyzer, deliberately NOT this substituting one, so a self-referencing
      // assignment (`SET note = note || 'x'`) reads note's OLD (plain, un-substituted) value for
      // that inner reference rather than looping back into its own substitution forever. A column
      // the statement never assigns (no entry in targetListByResno) falls through to the identical
      // plain catalog/qual/subquery/CTE resolution [analyzer] itself uses, which is exactly correct
      // for that column's pass-through (unmodified) value.
      val returningAnalyzer =
        buildAnalyzer(hasGroupingSets = false, forceNewNullable = forceNewNullable) { varno, varattno ->
          val assignedExpression = if (varno == resultRelationVarno) targetListByResno[varattno] else null
          if (assignedExpression != null) {
            analyzer.isNonNull(assignedExpression)
          } else {
            plainIsSourceColumnNotNull(varno, varattno)
          }
        }
      return returningEntries
        .filter { !it.isJunk }
        .sortedBy { it.resultNumber }
        .map { entry -> !returningAnalyzer.isNonNull(entry.expression) }
    }
    return analyzer.extractColumnNullability(nodeTree)
  }

  private fun isColumnNotNull(key: Pair<Int, Int>): Boolean =
    // A negative attribute number is a SYSTEM column (ctid, xmin, xmax, cmin, cmax, tableoid) —
    // pg_attribute rows for these are typically excluded from the catalog queries that populate
    // columnNotNullByRelidAndAttnum/viewColumnNotNullByRelidAndAttnum (both normally filter
    // attnum > 0), so a Var referencing one would otherwise fall through to "not found" (nullable)
    // even though every system column is unconditionally non-null for any real, returned row.
    key.second < 0 || columnNotNullByRelidAndAttnum[key] == true || viewColumnNotNullByRelidAndAttnum[key] == true

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
    forceNewNullable: Boolean = false,
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
    forceNewNullable = forceNewNullable,
  )

  /**
   * `true` when a `RETURNING WITH (OLD AS o, NEW AS n)` reference to `NEW` in [nodeTree] must be
   * forced nullable — see [NodeTreeNullabilityAnalyzer]'s `forceNewNullable` constructor
   * parameter's KDoc for the full reasoning (a plain `DELETE` never has a `NEW` row at all; a
   * `MERGE` might not, depending on which `WHEN` clause matched a given result row).
   */
  private fun forcesNewNullable(nodeTree: String): Boolean = when (nodeTreeParser.parseCommandType(nodeTree)) {
    PgNodeTreeParser.COMMAND_TYPE_DELETE -> true
    // Only a MERGE with at least one DELETE action can leave NO new row behind for SOME result
    // row — see hasDeleteMergeAction's KDoc. A MERGE with only UPDATE/INSERT actions always
    // writes or inserts a row, so NEW is exactly as trustworthy there as an ordinary column.
    PgNodeTreeParser.COMMAND_TYPE_MERGE -> nodeTreeParser.hasDeleteMergeAction(nodeTree)
    else -> false
  }

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
   * @param applyQualNarrowing See [analyzeNodeTree]'s parameter of the same name. Threaded
   *   through unchanged to every CTE body analyzed here.
   */
  private fun buildCteColumnNotNull(
    nodeTree: String,
    applyQualNarrowing: Boolean = true,
    @Language("PostgreSQL") sql: String,
  ): Map<Pair<Int, Int>, Boolean> {
    val cteRteMap = nodeTreeParser.parseCteRangeTableEntries(nodeTree)
    if (cteRteMap.isEmpty()) return emptyMap()
    val resolvedCtes = resolveCteBodies(nodeTree, applyQualNarrowing, sql)
    if (resolvedCtes.isEmpty()) return emptyMap()

    return buildMap {
      for ((varno, cteName) in cteRteMap) {
        val nullabilities = resolvedCtes[cteName] ?: continue
        nullabilities.forEachIndexed { columnIndex, nullable ->
          put(varno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  /**
   * Analyzes every CTE declared in [nodeTree]'s OWN `:cteList` and returns each one's per-column
   * nullability, keyed by CTE name.
   *
   * Shared by [buildCteColumnNotNull] (resolving a CTE reference in [nodeTree]'s OWN `:rtable`)
   * and [buildSubqueryColumnNotNull] (resolving a CTE reference — `:ctelevelsup 1` — one level
   * DOWN, inside a nested subquery's OWN `:rtable`): a CTE's declaration scope is [nodeTree]'s
   * level regardless of which nesting level actually references it, so both callers resolve
   * against the SAME set of CTE bodies.
   */
  private fun resolveCteBodies(
    nodeTree: String,
    applyQualNarrowing: Boolean,
    @Language("PostgreSQL") sql: String,
  ): Map<String, List<Boolean>> {
    val cteDefinitions = nodeTreeParser.parseCteList(nodeTree)
    if (cteDefinitions.isEmpty()) return emptyMap()
    val resolvedCtes = mutableMapOf<String, List<Boolean>>()
    for (cte in cteDefinitions) {
      val nullabilities = analyzeCteBodyNullability(cte, resolvedCtes, applyQualNarrowing, sql) ?: continue
      resolvedCtes[cte.name] = nullabilities
    }
    return resolvedCtes
  }

  /**
   * @param sql See [mergeAbsentVarnos]'s parameter of the same name — passed through unchanged so
   *   a MERGE nested in [cte]'s own body can be resolved by the SAME EXPLAIN call this parameter
   *   documents, keyed by ITS OWN target/source relation names.
   */
  private fun analyzeCteBodyNullability(
    cte: NodeTreeCteDefinition,
    previouslyResolved: Map<String, List<Boolean>>,
    applyQualNarrowing: Boolean = true,
    @Language("PostgreSQL") sql: String,
  ): List<Boolean>? {
    if (nodeTreeParser.hasSetOperations(cte.queryBlock)) {
      return analyzeSetOperationBranches(cte.queryBlock, previouslyResolved, cte.name, applyQualNarrowing)
    }
    val cteRangeTable = nodeTreeParser.parseRangeTable(cte.queryBlock)
    val mergeAbsent = mergeAbsentVarnos(cte.queryBlock, cteRangeTable, sql) ?: return null
    val analyzer = buildCteBodyAnalyzer(cte.queryBlock, previouslyResolved, applyQualNarrowing, mergeAbsent, sql)
    // :returningList must be checked FIRST, not as a fallback for an empty :targetList — see
    // analyzeNodeTree's identical guard for the full reasoning (an INSERT/UPDATE's OWN :targetList
    // holds the value expressions being WRITTEN, a completely different list from its RETURNING
    // projection, and is very often non-empty even when :returningList is what must be read). A
    // data-modifying CTE body reaches this method with its RAW, un-rewritten :returningList only
    // via [queryColumnNullabilityViaProsqlbody] — `prosqlbody` is the only mechanism that ever
    // populates a node tree for a data-modifying CTE in the first place (see that function's
    // KDoc), so there is no other caller shape this ordering needs to account for.
    val returningEntries = nodeTreeParser.parseReturningList(cte.queryBlock)
    if (returningEntries.isNotEmpty()) {
      return returningEntries
        .filter { !it.isJunk }
        .sortedBy { it.resultNumber }
        .map { entry -> !analyzer.isNonNull(entry.expression) }
    }
    val result = analyzer.extractColumnNullability(cte.queryBlock)
    return result.ifEmpty { null }
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
    // than trusting that argument alone to keep this loop from ever running forever. columnCount
    // itself is seedResult.size, since every branch of a UNION/set operation is required (by
    // PostgreSQL) to have the same column count as every other branch, and the seed's is already
    // known at this point.
    var combined = seedResult
    var previous: List<Boolean>?
    var remainingPasses = seedResult.size + 1
    var anyBranchUnanalyzable = false
    do {
      previous = combined
      val resolvedWithSelfReference = previouslyResolved + (cteName to combined)
      val branchResults = mutableListOf(seedResult)
      for (branchBlock in otherBranches) {
        val branchAnalyzer = buildCteBodyAnalyzer(branchBlock, resolvedWithSelfReference, applyQualNarrowing)
        val result = branchAnalyzer.extractColumnNullability(branchBlock)
        // An empty result means this branch's own nullability could not be determined at all — not
        // "this branch has zero columns" (impossible; every branch of a set operation has the same
        // column count). Silently dropping it from the OR-combination would let the OTHER
        // branches' (possibly narrower, even all-NOT-NULL) answer stand as if this branch
        // contributed nothing, when in truth its contribution is simply unknown. Flagging it here
        // forces every column nullable below instead.
        if (result.isNotEmpty()) branchResults.add(result) else anyBranchUnanalyzable = true
      }
      val columnCount = branchResults.maxOf { it.size }
      combined = (0 until columnCount).map { col -> branchResults.any { it.getOrElse(col) { true } } }
      remainingPasses--
    } while (combined != previous && remainingPasses > 0)
    // combined != previous here means the loop was cut off by remainingPasses running out while
    // the result was still changing — i.e. it did not reach its fixpoint. Returning that
    // still-moving intermediate value could under-report nullability (a later pass might still
    // flip more columns to nullable), so every column is forced nullable instead, the same
    // fallback taken for an unanalyzable branch above.
    val didNotConverge = combined != previous
    return if (anyBranchUnanalyzable || didNotConverge) List(combined.size) { true } else combined
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
   * @param applyQualNarrowing See [analyzeNodeTree]'s parameter of the same name.
   * @param mergeAbsentVarnos See [analyzeNodeTree]'s parameter of the same name — [queryBlock]'s
   *   OWN varno-to-canBeAbsent map when [queryBlock] itself is a `MERGE` (resolved by
   *   [analyzeCteBodyNullability] before ever calling this method), empty otherwise.
   * @param sql See [mergeAbsentVarnos]'s (the method, not this parameter) `sql` parameter — passed
   *   through only so a subquery WITHIN [queryBlock] that references a CTE declared in
   *   [queryBlock]'s OWN nested `WITH` clause can resolve THAT (deeper) CTE's `MERGE`, if it has
   *   one, through [buildSubqueryColumnNotNull]. Defaults to an empty string for the (`SELECT`-only,
   *   never `MERGE`-shaped) set-operation branch callers in [analyzeSetOperationBranches], where an
   *   empty `EXPLAIN` target simply fails harmlessly (caught, treated as "cannot resolve") for the
   *   narrow, deeper case of a subquery nested that deep referencing ITS OWN local `MERGE` CTE.
   */
  private fun buildCteBodyAnalyzer(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
    applyQualNarrowing: Boolean = true,
    mergeAbsentVarnos: Map<Int, Boolean> = emptyMap(),
    @Language("PostgreSQL") sql: String = "",
  ): NodeTreeNullabilityAnalyzer {
    val cteRangeTable = nodeTreeParser.parseRangeTable(queryBlock)
    // See analyzeNodeTree's identical guard: GROUPING SETS/CUBE/ROLLUP can null-extend a
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
    val subqueryColumnNotNull = buildSubqueryColumnNotNull(queryBlock, applyQualNarrowing, sql)
    // See analyzeNodeTree's identical guard for why qual narrowing is suppressed whenever
    // hasGroupingSets: a grouping key is exactly the thing a GROUPING SETS/CUBE/ROLLUP query
    // null-extends after WHERE has already run. A non-zero :resultRelation suppresses narrowing
    // for the identical reason analyzeNodeTree's own guard does: a data-modifying CTE body's WHERE
    // clause can prove something about a column its OWN SET clause is about to overwrite — see
    // e.g. `WITH c AS (UPDATE t SET a = NULL FROM u WHERE u.id = t.id AND t.a IS NOT NULL
    // RETURNING t.a) SELECT a FROM c`, verified against real Postgres to return `a = NULL`, not the
    // value the WHERE clause proved before the SET ran.
    val isDml = nodeTreeParser.parseResultRelation(queryBlock) != 0
    val qualNotNullVars = if (applyQualNarrowing && !hasGroupingSets && !isDml) {
      NodeTreeNullabilityAnalyzer.qualProvenNonNullVars(queryBlock, isStrictFunction)
    } else {
      emptySet()
    }
    return buildAnalyzer(hasGroupingSets = hasGroupingSets, forceNewNullable = forcesNewNullable(queryBlock)) {
        varno,
        varattno,
      ->
      if (mergeAbsentVarnos[varno] == true) {
        // See analyzeNodeTree's identical guard: a MERGE relation EXPLAIN determined can be
        // entirely absent for some result row can never be proven non-null here.
        false
      } else if (isProvenByQuals(qualNotNullVars, groupRteMap, varno, varattno)) {
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
   * @param applyQualNarrowing See [analyzeNodeTree]'s parameter of the same name.
   * @return A map from `(varno, varattno)` pairs to `true` when the subquery column is non-null
   */
  private fun buildSubqueryColumnNotNull(
    nodeTree: String,
    applyQualNarrowing: Boolean = true,
    @Language("PostgreSQL") sql: String = "",
  ): Map<Pair<Int, Int>, Boolean> {
    // Set-operation queries (UNION ALL, INTERSECT, EXCEPT) store their branches as rtekind=1
    // subquery RTEs. Tracing through them would incorrectly report the first branch's nullability
    // as the result's nullability — the true result is the union across ALL branches, some of which
    // may introduce nulls (e.g., a branch with LEFT JOIN). Return empty so the analyzer conservatively
    // treats set-operation output columns as nullable (the correct safe default).
    if (nodeTreeParser.hasSetOperations(nodeTree)) return emptyMap()
    val subqueryRangeTable = nodeTreeParser.parseSubqueryRangeTable(nodeTree)
    if (subqueryRangeTable.isEmpty()) return emptyMap()
    // A subquery nested inside a DML statement's own :targetList (e.g. `INSERT INTO t(name)
    // SELECT name FROM source RETURNING *`, where "source" is a sibling CTE) can reference a CTE
    // declared at NODETREE'S level via `:ctelevelsup 1` — one level UP from the subquery's own
    // scope — inside its OWN `:rtable`, not [nodeTree]'s. Resolved once here, lazily, so a
    // [nodeTree] with no CTEs at all (the overwhelmingly common case) never pays for
    // [resolveCteBodies]'s recursive analysis.
    val resolvedCtes by lazy { resolveCteBodies(nodeTree, applyQualNarrowing, sql) }
    return buildMap {
      for ((outerVarno, subqueryBlock) in subqueryRangeTable) {
        // Parse the subquery's own base-table range table for isSourceColumnNotNull.
        val subRangeTable = nodeTreeParser.parseRangeTable(subqueryBlock)
        val subCteRteMap = nodeTreeParser.parseCteRangeTableEntries(subqueryBlock)
        // See analyzeNodeTree's identical guard: GROUPING SETS/CUBE/ROLLUP can
        // null-extend a grouping key even when the base table column is NOT NULL. The actual
        // override (including EXPRESSION grouping keys) is applied by extractColumnNullability
        // via hasGroupingSets below.
        val hasGroupingSets = nodeTreeParser.hasGroupingSets(subqueryBlock)
        val groupRteMap = if (hasGroupingSets) {
          emptyMap()
        } else {
          nodeTreeParser.parseGroupRteMap(subqueryBlock)
        }
        // See analyzeNodeTree's identical guard: suppress qual narrowing whenever
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
            val relid = subRangeTable[subVarno]
            if (relid != null) {
              isColumnNotNull(relid to subVarattno)
            } else {
              val cteName = subCteRteMap[subVarno]
              cteName != null && resolvedCtes[cteName]?.getOrNull(subVarattno - 1) == false
            }
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
   * Answers, for [analyzeNodeTree]'s own `:targetList`-to-`:returningList` substitution, whether a
   * `RETURNING` item that merely reads back a `:targetList` assignment can be trusted as what
   * `RETURNING` actually sees for [relid] — `false` whenever a row-level `BEFORE` trigger, a
   * rewrite rule, an `INSTEAD OF` trigger, or an FDW could substitute a different final value.
   *
   * @return `true` only when [relid] and every transitive inheritance/partition descendant is a
   *   plain table with no risky `relkind`, no mutating row-level trigger, and no non-view rewrite
   *   rule — `false` for every other case, INCLUDING the catalog query itself failing to execute
   *   (treated exactly like a confirmed risk: [analyzeNodeTree] must not trust the substitution
   *   when it cannot rule the risk out).
   */
  private fun isSubstitutionSafeForRelation(relid: Int): Boolean = try {
    connection.prepareStatement(
      """
      WITH RECURSIVE descendants(relid) AS (
        SELECT ?::integer
        UNION
        SELECT i.inhrelid::integer
        FROM pg_catalog.pg_inherits i
        JOIN descendants d ON i.inhparent = d.relid
      )
      SELECT
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
      preparedStatement.setInt(1, relid)
      preparedStatement.executeQuery().use { rs ->
        check(rs.next()) { "Expected exactly one row from the substitution-safety EXISTS query" }
        !rs.getBoolean("has_risky_relkind") &&
          !rs.getBoolean("has_mutating_row_trigger") &&
          !rs.getBoolean("has_non_view_rewrite_rule")
      }
    }
  } catch (_: SQLException) {
    false
  }

  /**
   * The bare (unqualified) table name for [relid], via `pg_class.relname` — used to attribute
   * an `EXPLAIN` plan's `"Relation Name"` fields (which are always the REAL table name, never an
   * alias) back to a specific `:rtable` entry this class already resolved structurally, without
   * ever re-parsing the SQL text for a table name or alias. See [mergeAbsentVarnos]'s only caller.
   *
   * @return `null` if [relid] cannot be resolved (should not happen for a real, structural
   *   `:rtable` entry, but treated the same as any other "cannot confirm" case: the caller must
   *   fall back to its own safe default rather than guess)
   */
  private fun resolveTableName(relid: Int): String? = try {
    connection.prepareStatement("SELECT relname FROM pg_catalog.pg_class WHERE oid = ?").use { preparedStatement ->
      preparedStatement.setInt(1, relid)
      preparedStatement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }
  } catch (_: SQLException) {
    null
  }
}
