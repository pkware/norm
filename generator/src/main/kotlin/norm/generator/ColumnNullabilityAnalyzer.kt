package norm.generator

import org.intellij.lang.annotations.Language
import java.sql.SQLException
import java.util.UUID

/**
 * Recursion budget for [ColumnNullabilityAnalyzer.subLinkSubqueryColumnNotNull]: how many levels
 * of NESTED `ANY_SUBLINK`/`ALL_SUBLINK` (a `SubLink` whose own subselect contains another
 * `SubLink`) are resolved before defaulting to nullable — see that method's KDoc.
 *
 * This is NOT required to prevent an infinite loop: [PgNodeExpression.SubLink.subselectBlock] is
 * always extracted, via [PgNodeTreeParser.extractFieldExpression], as a genuine SUBSTRING of its
 * enclosing `SubLink`'s own text, strictly shorter than it — [PgNodeTreeParser] has no mechanism to
 * produce a cyclic or self-referential node-tree text, so this recursion is provably bounded by the
 * ORIGINAL query text's finite length regardless of this constant's value, or even its presence.
 * The budget exists instead as a defensive bound on STACK DEPTH and repeated analysis WORK for a
 * pathologically deep (if syntactically legal) chain of nested `= ANY (...)`/`ALL (...)` sublinks,
 * the same role [NodeTreeNullabilityAnalyzer.MAX_EXPRESSION_DEPTH] plays for a deeply nested parsed
 * expression tree elsewhere in this codebase. Deliberately small: this analysis is only ever needed
 * for the (typically shallow) nullability proof of an `IN`/`= ANY` subquery's single output column,
 * not for arbitrarily deep query nesting in general — see `QueryAnalysisTest`'s four-level-nesting
 * test for a query shape that is semantically NOT NULL end-to-end but is reported nullable at this
 * budget, pinning that the budget's specific VALUE (not merely its presence) is what is enforced.
 */
private const val SUBLINK_ANALYSIS_DEPTH_BUDGET = 3

/**
 * Recursion budget for [ColumnNullabilityAnalyzer.resolveViewColumnNullability]: how many levels of
 * nested view-over-view resolution are followed before returning the conservative (nullable) answer.
 *
 * Unlike [SUBLINK_ANALYSIS_DEPTH_BUDGET], this bound is required to prevent a
 * `java.lang.StackOverflowError`, not merely to bound work: resolution recurses through real JVM
 * stack frames, and `CREATE VIEW` permits unbounded nesting. `50` is far past any real schema's
 * nesting depth and fits in a 512 KiB thread stack, so a small Gradle worker thread is safe. Actual
 * cycles, possible at much shallower depths, are handled by
 * [ColumnNullabilityAnalyzer.viewColumnNullabilityInProgress] instead.
 *
 * Views within this many levels of the deepest point of a chain that ITSELF exceeds the budget can
 * get either the truncated or the fully-resolved answer, depending on which views were memoized
 * first: the depth check runs before the memo lookup, so a relid at or past the budget always
 * truncates, but an untainted cached answer for a strictly shallower relid is reused without
 * revisiting this guard. Both answers are sound — truncation only widens — and the sweep order is
 * fixed (see `PgCatalogLoader.loadViewColumnNamesByRelidAndAttnum`), so a given schema always
 * generates the same Kotlin. Closing the residual would need per-entry tracking of how much depth
 * each cached answer needed, which this guard's narrow job does not warrant.
 */
internal const val VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET = 50

/**
 * One result column's [nullable] flag (`true` means nullable) together with, when resolvable, its
 * [provenanceExpression] — the CTE-body SQL expression, verbatim from the developer's own query
 * text, for a column whose select item is merely a bare reference into a CTE's output. `null` when
 * [NodeTreeProvenanceResolver] found no CTE reference to attribute this column to, or
 * [resolveNodeTreeProvenanceExpression] could not prove the expression correct.
 *
 * Also carries [originalColumnName] — the real source column name, resolved from the outer target
 * entry's own `:resorigtbl`/`:resorigcol` (see [PgCatalogLoader.columnNameByRelidAndAttnum]) rather
 * than whatever alias the select item's text happens to spell. `null` when those fields are `0` (no
 * single source column) or the OID/attnum pair isn't in the catalog map — the caller must fall back
 * to its ordinary column-name resolution, never guess.
 *
 * Carries all three facts together, rather than as separate parallel lists, because
 * [ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody] resolves them from the same parsed
 * node tree in one round trip.
 */
internal data class ColumnAnalysis(
  val nullable: Boolean,
  val provenanceExpression: String?,
  val originalColumnName: String? = null,
)

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
  private val columnNameByRelidAndAttnum get() = loader.columnNameByRelidAndAttnum
  private val aggregateHasNonNullInitialValue get() = loader.aggregateHasNonNullInitialValue
  private val alwaysNonNullFunctionOids get() = loader.alwaysNonNullFunctionOids
  private val neverNullForNonNullInputOids get() = loader.neverNullForNonNullInputOids
  private val lagLeadWithDefaultOids get() = loader.lagLeadWithDefaultOids
  private val immutableFunctionOids get() = loader.immutableFunctionOids
  private val nonNullIffFirstArgumentNonNullFunctionOids get() = loader.nonNullIffFirstArgumentNonNullFunctionOids
  private val isStrictFunction get() = loader.isStrictFunction

  /**
   * Memoized per-relid view-column nullability, populated by [resolveViewColumnNullability]. Index
   * `i` (0-based) corresponds to attnum `i + 1`. A `null` VALUE, as opposed to an absent key, means the
   * relid is not a view or materialized view at all, so [isColumnNotNull] must fall through to
   * base-table resolution.
   *
   * Written UNCONDITIONALLY by every successful resolution, even for a tainted answer — see
   * [viewColumnNullabilityTaintedRelids]. Needs no synchronization (single-threaded [connection]).
   */
  private val viewColumnNullabilityMemo = mutableMapOf<Int, List<Boolean>?>()

  /**
   * Relids currently being resolved by [resolveViewColumnNullability] — guards against infinite
   * recursion through a view dependency cycle.
   *
   * Load-bearing, not merely defensive: `CREATE OR REPLACE VIEW` only requires the relations the new
   * definition references to exist at replace time, so both a mutual cycle (`a` selects from `b`,
   * then `b` is replaced to select from `a`) and a direct self-cycle are constructible. PostgreSQL
   * refuses to QUERY such a view at all, so the nullable placeholder this guard returns has no true
   * answer to under-approximate.
   */
  private val viewColumnNullabilityInProgress = mutableSetOf<Int>()

  /**
   * Current depth of nested [resolveViewColumnNullability] recursion — see
   * [VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET]. Depth `0` identifies the outermost call, where
   * [viewColumnNullabilityTaintedRelids] is evicted.
   */
  private var viewColumnNullabilityRecursionDepth = 0

  /**
   * Total number of taint events since construction — monotonically increasing, never reset;
   * [resolveViewColumnNullability] compares entry and exit snapshots to decide whether its own answer
   * was built from one. See [viewColumnNullabilityTaintedRelids] for what counts and why.
   */
  private var viewColumnNullabilityTaintEventCount = 0

  /**
   * Relids whose current [viewColumnNullabilityMemo] entry was computed from a guard placeholder —
   * directly, or transitively through another tainted relid's cached answer — rather than from a
   * genuine, order-independent evaluation of the view's own defining query.
   *
   * A taint event is the cycle guard firing, the depth guard firing, an unanalyzable node tree, or a
   * memo READ of an already-tainted relid. Any frame whose own entry-to-exit window spans one built
   * its answer from it, so its relid is marked here too. That last case is required, not merely
   * convenient: a sibling reaching a tainted relid purely through the memo fast path does no recursion
   * of its own and would otherwise see no counter movement and treat itself as untainted.
   *
   * Tainted entries are EVICTED once the outermost call finishes, rather than never cached at all.
   * Never caching also removes cross-call order dependence, but makes every reference to a shared
   * tainted ancestor re-walk its whole subtree, turning a branching view graph exponential. Evicting
   * keeps each relid resolved at most once per top-level call while still recomputing a poisoned
   * answer for the next, independent query.
   */
  private val viewColumnNullabilityTaintedRelids = mutableSetOf<Int>()

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
   * Provenance piggybacks the same round trip: [NodeTreeProvenanceResolver] resolves each column's
   * CTE-body position from the identical [nodeTree] this method already builds for nullability, and
   * [resolveNodeTreeProvenanceExpression] extracts and cross-validates the actual expression text
   * from [sql] — the original, un-substituted query text, never [substitutedSql] — so a sentinel
   * literal built only to satisfy a `?`'s type can never leak into generated KDoc.
   *
   * @param sql the SQL query or DML statement to analyze; any `?` parameter placeholder is
   *   replaced with a typed non-null sentinel literal internally (see [buildViewSqlWithSentinels])
   *   only for building the probe function — [sql] itself, `?` intact, is what provenance
   *   expression text is extracted from
   * @return one [ColumnAnalysis] per result column in `SELECT`/`RETURNING` order, or `null` if
   *   [sql] has no result columns to probe or the probe itself failed for any reason — the caller
   *   must treat `null` as "this path has no answer", never as "zero columns."
   */
  internal fun queryColumnNullabilityViaProsqlbody(@Language("PostgreSQL") sql: String): List<ColumnAnalysis>? {
    val substitutedSql = buildViewSqlWithSentinels(sql) ?: replaceParameterPlaceholders(sql)
    val functionName = "norm_nullability_${UUID.randomUUID().toString().replace("-", "")}"
    return try {
      connection.createStatement().use { statement ->
        statement.execute(
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
        val nodeTree = connection.createStatement().use { statement ->
          statement.executeQuery(
            "SELECT prosqlbody::text FROM pg_proc " +
              // "pg_temp" is a per-session ALIAS, not a literal schema name — the real catalog row
              // is named "pg_temp_N", so 'pg_temp'::regnamespace fails to resolve at all (verified
              // live: "ERROR: schema "pg_temp" does not exist"). pg_my_temp_schema() returns the
              // current session's actual temp schema OID directly.
              "WHERE proname = '$functionName' AND pronamespace = pg_my_temp_schema()",
          ).use { resultSet ->
            check(resultSet.next()) { "No pg_proc row found for probe function $functionName" }
            resultSet.getString(1)
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
        val nullability = analyzeNodeTree(
          nodeTree,
          applyQualNarrowing = true,
          sql = substitutedSql,
          trustAssignedExpressions = '?' !in sql,
          mergeAbsentVarnos = mergeAbsent,
        )
        val provenance = NodeTreeProvenanceResolver().resolveColumnProvenance(nodeTree).map { columnProvenance ->
          columnProvenance?.let { resolveNodeTreeProvenanceExpression(sql, nodeTree, it) }
        }
        val originalColumnNames = resolveOriginalColumnNames(nodeTree)
        nullability.mapIndexed { index, nullable ->
          ColumnAnalysis(nullable, provenance.getOrNull(index), originalColumnNames.getOrNull(index))
        }
      } finally {
        connection.createStatement().use { statement ->
          statement.execute("DROP FUNCTION IF EXISTS pg_temp.$functionName()")
        }
      }
    } catch (_: SQLException) {
      null
    }
  }

  /**
   * Resolves each result column's REAL source column name from [nodeTree]'s own `:returningList`
   * (DML `RETURNING`) or `:targetList` (a plain `SELECT`) — the same list, in the same order
   * ([TargetEntry.isJunk] filtered, sorted by [TargetEntry.resultNumber]), [analyzeNodeTree] reads
   * for nullability, so the two stay index-aligned.
   *
   * Each entry's own `:resorigtbl`/`:resorigcol` — [TargetEntry.originalTableOid] and
   * [TargetEntry.originalColumnNumber] — name the column PostgreSQL itself traced this result
   * column back to, walking through a CTE or subquery reference rather than stopping at the
   * immediate select item's own alias (`WITH c AS (SELECT id AS parent_id FROM parent) SELECT
   * parent_id FROM c`'s outer target entry carries `:resorigtbl`/`:resorigcol` for `parent.id`, not
   * the CTE's own `parent_id` alias).
   *
   * @return one entry per result column: the resolved column name, or `null` when
   *   [TargetEntry.originalTableOid]/[TargetEntry.originalColumnNumber] is `0` (no single source
   *   column — a computed expression, an aggregate, a set-operation branch, or a `USING`/`NATURAL`
   *   merged join column) or the OID/attnum pair is absent from [columnNameByRelidAndAttnum] for any
   *   other reason. The caller must treat `null` as "fall back to the ordinary resolution", never
   *   guess a value.
   */
  private fun resolveOriginalColumnNames(nodeTree: String): List<String?> {
    val returningEntries = nodeTreeParser.parseReturningList(nodeTree)
    val entries = returningEntries.ifEmpty { nodeTreeParser.parseTargetList(nodeTree) }
    return entries.filter { !it.isJunk }.sortedBy { it.resultNumber }.map { entry ->
      if (entry.originalTableOid == 0 || entry.originalColumnNumber == 0) {
        null
      } else {
        columnNameByRelidAndAttnum[entry.originalTableOid to entry.originalColumnNumber]
      }
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
    // Computed once so the same resolution feeds both buildCteColumnNotNull's varno-keyed projection
    // and buildAnalyzer's resolvedCtes, which needs the raw, name-keyed map.
    val resolvedCtes = resolveCteBodies(nodeTree, applyQualNarrowing, sql)
    // For subquery RTEs (rtekind 1), the outer VAR's varno is not in rangeTable.
    // Resolve their nullability by recursively analyzing each subquery's target list.
    // The map is keyed by (varno, varattno) for direct lookup in isSourceColumnNotNull.
    val subqueryColumnNotNull = buildSubqueryColumnNotNull(nodeTree, resolvedCtes, applyQualNarrowing, sql)
    val cteColumnNotNull = buildCteColumnNotNull(nodeTree, resolvedCtes)
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
      applyQualNarrowing = applyQualNarrowing,
      resolvedCtes = resolvedCtes,
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
        buildAnalyzer(
          hasGroupingSets = false,
          forceNewNullable = forceNewNullable,
          applyQualNarrowing = applyQualNarrowing,
          resolvedCtes = resolvedCtes,
        ) { varno, varattno ->
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

  /**
   * `true` when `(relid, attnum)` — [key] — is guaranteed NOT NULL, whether it identifies a base-table
   * column (checked against [columnNotNullByRelidAndAttnum]) or a view column (resolved via
   * [resolveViewColumnNullability], since `pg_attribute.attnotnull` is always `false` for a view
   * column regardless of the view's definition).
   *
   * `internal`, not `private`: [PgCatalogLoader.loadViewColumnNullability] calls this directly.
   */
  internal fun isColumnNotNull(key: Pair<Int, Int>): Boolean {
    // A negative attribute number is a SYSTEM column (ctid, xmin, xmax, cmin, cmax, tableoid) —
    // pg_attribute rows for these are typically excluded from the catalog queries that populate
    // columnNotNullByRelidAndAttnum (which filters attnum > 0), so a Var referencing one would
    // otherwise fall through to "not found" (nullable) even though every system column is
    // unconditionally non-null for any real, returned row.
    if (key.second < 0) return true
    if (columnNotNullByRelidAndAttnum[key] == true) return true
    val viewNullability = resolveViewColumnNullability(key.first) ?: return false
    return viewNullability.getOrNull(key.second - 1) == false
  }

  /**
   * Resolves [relid]'s per-column nullability by fully evaluating its view definition's own node
   * tree (`pg_rewrite`'s `_RETURN` rule) rather than inheriting a same-named source column's
   * constraint the way the `pg_depend` name-join this replaces did (`#256`: `SELECT NULLIF(v, 'x') AS
   * v FROM u` was reported NOT NULL whenever `u.v` was).
   *
   * @return one nullable flag per user-visible column (`attnum > 0 AND NOT attisdropped`), index `i`
   *   corresponding to attnum `i + 1`, or `null` when [relid] is not a view or materialized view at all
   *   (no `_RETURN` rule). That alignment holds because a view's non-junk target-list resnos are always
   *   contiguous `1..N`: there is no `ALTER VIEW DROP COLUMN`, `CREATE OR REPLACE VIEW` can only
   *   append, and a resjunk entry (an `ORDER BY`/`GROUP BY` expression not in the SELECT list) is
   *   always appended after every real column. [alignViewColumnNullability] guards this breaking on a
   *   future PostgreSQL version anyway.
   */
  internal fun resolveViewColumnNullability(relid: Int): List<Boolean>? {
    if (viewColumnNullabilityRecursionDepth >= VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET) {
      // Depth guard — a deep but acyclic pass-through chain can exhaust the JVM stack before ever
      // revisiting a relid the cycle guard below would catch. Runs BEFORE the memo lookup so a relid
      // at or past the budget always truncates rather than returning a cached answer; see
      // VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET's KDoc. A taint event: correct only while this deep.
      viewColumnNullabilityTaintEventCount++
      return List(columnCountFor(relid)) { true }
    }
    if (viewColumnNullabilityMemo.containsKey(relid)) {
      // A memo READ of an already-tainted relid is itself a taint event for the reading frame — see
      // viewColumnNullabilityTaintedRelids' KDoc.
      if (relid in viewColumnNullabilityTaintedRelids) viewColumnNullabilityTaintEventCount++
      return viewColumnNullabilityMemo[relid]
    }
    if (relid in viewColumnNullabilityInProgress) {
      // Cycle guard — see viewColumnNullabilityInProgress's KDoc. A taint event: this placeholder is
      // correct only while the cycle is being walked, not relid's own answer.
      viewColumnNullabilityTaintEventCount++
      return List(columnCountFor(relid)) { true }
    }
    val taintEventCountAtEntry = viewColumnNullabilityTaintEventCount
    val isOutermostCall = viewColumnNullabilityRecursionDepth == 0
    viewColumnNullabilityInProgress.add(relid)
    viewColumnNullabilityRecursionDepth++
    try {
      val nodeTree = fetchViewNodeTree(relid)
      if (nodeTree == null) {
        // Not a view at all — this determination did no recursion of its own, so it can never be
        // tainted by anything happening elsewhere; always safe to memoize permanently.
        viewColumnNullabilityMemo[relid] = null
        return null
      }
      val nullability = try {
        if (nodeTreeParser.hasSetOperations(nodeTree)) {
          // Same split as analyzeCteBodyNullability: a top-level UNION ALL/INTERSECT/EXCEPT must be
          // resolved branch-by-branch and OR-combined. The synthetic name cannot collide with a CTE
          // declared inside the view's own body, so previouslyResolved's self-reference entry is
          // never consulted here.
          analyzeSetOperationBranches(nodeTree, emptyMap(), "__norm_view_relid_$relid", applyQualNarrowing = true)
        } else {
          analyzeViewNodeTree(nodeTree)
        }
      } catch (_: SQLException) {
        null
      }
      val expectedColumnCount = columnCountFor(relid)
      // A caught SQLException or an unanalyzable set-operation result forces the all-nullable
      // fallback below; that is a taint event too, so it is not cached for this analyzer's whole
      // remaining lifetime with no eviction path.
      if (nullability == null || nullability.size != expectedColumnCount) {
        viewColumnNullabilityTaintEventCount++
      }
      val result = alignViewColumnNullability(nullability, expectedColumnCount)
      // Always memoize (see viewColumnNullabilityMemo's KDoc); whether this frame's own window saw a
      // taint event decides whether relid is also marked tainted, and therefore evicted below.
      viewColumnNullabilityMemo[relid] = result
      if (viewColumnNullabilityTaintEventCount != taintEventCountAtEntry) {
        viewColumnNullabilityTaintedRelids.add(relid)
      }
      return result
    } finally {
      viewColumnNullabilityRecursionDepth--
      viewColumnNullabilityInProgress.remove(relid)
      if (isOutermostCall) {
        // Back at depth 0: evict what this traversal tainted so a later, independent (possibly
        // shallower) query recomputes it. Untainted entries stay cached.
        for (taintedRelid in viewColumnNullabilityTaintedRelids) {
          viewColumnNullabilityMemo.remove(taintedRelid)
        }
        viewColumnNullabilityTaintedRelids.clear()
      }
    }
  }

  /** The number of user-visible columns (`attnum > 0 AND NOT attisdropped`) [relid] has. */
  private fun columnCountFor(relid: Int): Int = columnNotNullByRelidAndAttnum.keys.count { it.first == relid }

  /**
   * Aligns [nullability] — one flag per non-junk target-list entry, in resno order — onto exactly
   * [expectedColumnCount] entries, falling back to nullable for every column when the two disagree or
   * when [nullability] is `null` (the analysis could not answer at all).
   *
   * Insurance against a future PostgreSQL version breaking [resolveViewColumnNullability]'s
   * resno-contiguity argument; no known real SQL reaches it. `internal`, not `private`, purely so a
   * unit test can drive it with a synthetic mismatch.
   */
  internal fun alignViewColumnNullability(nullability: List<Boolean>?, expectedColumnCount: Int): List<Boolean> =
    if (nullability != null && nullability.size == expectedColumnCount) {
      nullability
    } else {
      List(expectedColumnCount) { true }
    }

  /**
   * `true` when [relid] currently has a (possibly tainted) answer cached in
   * [viewColumnNullabilityMemo] — `internal`, not `private`, purely so a test can assert directly
   * that eviction happened for a fixture whose stale and recomputed values are identical, and so
   * cannot be told apart by output value alone.
   */
  internal fun isRelidMemoized(relid: Int): Boolean = viewColumnNullabilityMemo.containsKey(relid)

  /**
   * Evaluates [nodeTree] — a view's own `pg_rewrite.ev_action` `_RETURN` rule text — through the same
   * [analyzeNodeTree] machinery an ordinary query uses, rather than widening [analyzeNodeTree]'s
   * visibility for this one caller. A view's defining query is always a plain `SELECT` with no `?`
   * placeholders, so [analyzeNodeTree]'s `trustAssignedExpressions`/`mergeAbsentVarnos` defaults
   * apply.
   */
  internal fun analyzeViewNodeTree(nodeTree: String): List<Boolean> =
    analyzeNodeTree(nodeTree, applyQualNarrowing = true, sql = "")

  /**
   * Fetches [relid]'s `_RETURN` rule text (`pg_rewrite.ev_action`, the query PostgreSQL runs when
   * the view is selected from) for [resolveViewColumnNullability], or `null` when [relid] is not a
   * view or materialized view, or otherwise has no such rule.
   *
   * Guarded by `relkind IN ('v', 'm')` so a base table that happens to carry an unrelated
   * `ev_type = '1'` rule is never mistaken for a view — `ev_type = '1'` alone identifies only that a
   * rule is a `_RETURN` rule, not that its relation is actually a view.
   */
  private fun fetchViewNodeTree(relid: Int): String? = try {
    connection.prepareStatement(
      """
      SELECT rw.ev_action::text AS node_tree
      FROM pg_catalog.pg_rewrite rw
      JOIN pg_catalog.pg_class c ON c.oid = rw.ev_class
      WHERE rw.ev_class = ? AND rw.ev_type = '1' AND c.relkind IN ('v', 'm')
      """.trimIndent(),
    ).use { preparedStatement ->
      preparedStatement.setInt(1, relid)
      preparedStatement.executeQuery().use { resultSet ->
        if (resultSet.next()) resultSet.getString("node_tree") else null
      }
    }
  } catch (_: SQLException) {
    null
  }

  /**
   * Creates a [NodeTreeNullabilityAnalyzer] pre-configured with this loader's catalog lookups.
   *
   * All constructor arguments except [isSourceColumnNotNull] are identical across every call site
   * in this class. This method captures the common configuration so callers only need to supply
   * the source-column resolution strategy, which varies by context (outer query, CTE body,
   * subquery).
   *
   * @param applyQualNarrowing See [analyzeNodeTree]'s parameter of the same name — passed through
   *   only so [isSubLinkSubqueryColumnNotNull]'s wiring below can apply the SAME qual-narrowing
   *   policy to a `SubLink`'s subselect that the caller applies to everything else.
   * @param depth The [subLinkSubqueryColumnNotNull] recursion budget for a `SubLink` encountered by
   *   the returned analyzer — see that method's KDoc for why a budget is mandatory (a subselect can
   *   itself contain a `SubLink`, whose own subselect can contain another). Defaults to
   *   [SUBLINK_ANALYSIS_DEPTH_BUDGET] for every analyzer built directly from a top-level node tree,
   *   CTE body, or subquery-RTE body; [subLinkSubqueryColumnNotNull] passes `depth - 1` when
   *   building the analyzer for a `SubLink`'s OWN subselect, so the budget only ever decreases
   *   along a chain of NESTED sublinks, never along the unrelated CTE/subquery-RTE recursion this
   *   class already performs independently of it.
   *
   *   No `MERGE`-resolution parameter is threaded through here: a sublink's `:subselect` is, by SQL
   *   grammar, always a `SELECT` — a `MERGE`, `UPDATE`, or `DELETE` can never appear as a sublink's
   *   own subquery body — so [mergeAbsentVarnos] can never apply to anything
   *   [subLinkSubqueryColumnNotNull] reaches, and there is nothing for a `sql`/`EXPLAIN` parameter
   *   here to resolve.
   * @param resolvedCtes CTE bodies declared directly in the query block this analyzer is built for —
   *   see [analyzeQueryBlockNullability]'s parameter of the same name for the invariant every caller
   *   must uphold. Threaded to [subLinkSubqueryColumnNotNull] so a `SubLink`'s subselect can resolve a
   *   reference to an ENCLOSING `WITH` clause (`#257`). Defaults to `emptyMap()`, the safe (nullable)
   *   answer for a query block with no CTEs of its own.
   */
  private fun buildAnalyzer(
    hasGroupingSets: Boolean = false,
    forceNewNullable: Boolean = false,
    applyQualNarrowing: Boolean = true,
    depth: Int = SUBLINK_ANALYSIS_DEPTH_BUDGET,
    resolvedCtes: Map<String, List<Boolean>> = emptyMap(),
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
    isSubLinkSubqueryColumnNotNull = { subselectBlock ->
      subLinkSubqueryColumnNotNull(subselectBlock, applyQualNarrowing, depth, resolvedCtes)
    },
    hasGroupingSets = hasGroupingSets,
    forceNewNullable = forceNewNullable,
  )

  /**
   * Backs [NodeTreeNullabilityAnalyzer]'s `isSubLinkSubqueryColumnNotNull` callback: `true` when
   * [subselectBlock] — the raw `{QUERY ...}` text of an `ANY_SUBLINK`'s or `ALL_SUBLINK`'s
   * `:subselect` — produces EXACTLY ONE non-junk output column and that column is provably non-null.
   *
   * Set-operation subselects (`UNION`/`INTERSECT`/`EXCEPT`) are rejected outright, the same
   * conservative default [buildSubqueryColumnNotNull] applies to a `FROM`-clause subquery RTE for
   * the identical reason: tracing through would report only the first branch's nullability, not
   * the union across every branch (see that method's own KDoc).
   *
   * @param depth remaining recursion budget — see [buildAnalyzer]'s `depth` parameter KDoc.
   *   Returns `false` (safe: nullable) once exhausted, so a `SubLink` nested inside another
   *   `SubLink`'s subselect cannot recurse indefinitely.
   * @param resolvedCtes See [buildAnalyzer]'s parameter of the same name — CTE bodies declared
   *   directly in [subselectBlock]'s own enclosing query block, so [subselectBlock] can resolve a
   *   reference to one of them (`#257`).
   */
  private fun subLinkSubqueryColumnNotNull(
    subselectBlock: String,
    applyQualNarrowing: Boolean,
    depth: Int,
    resolvedCtes: Map<String, List<Boolean>>,
  ): Boolean {
    if (depth <= 0) return false
    if (nodeTreeParser.hasSetOperations(subselectBlock)) return false
    val nullability = analyzeQueryBlockNullability(subselectBlock, applyQualNarrowing, resolvedCtes, depth - 1)
    return nullability.size == 1 && !nullability[0]
  }

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
   * non-null.
   *
   * @param resolvedCtes [nodeTree]'s own directly-declared CTE bodies (from [resolveCteBodies]),
   *   keyed by name — passed in rather than computed here so the same resolution also feeds
   *   [buildAnalyzer] for a `SubLink` nested in [nodeTree]'s target list. Every CTE reference in
   *   [nodeTree]'s `:rtable` is `:ctelevelsup 0` by construction: [nodeTree] is always the outermost
   *   statement text this class analyzes, so it has no enclosing scope to point past.
   */
  private fun buildCteColumnNotNull(
    nodeTree: String,
    resolvedCtes: Map<String, List<Boolean>>,
  ): Map<Pair<Int, Int>, Boolean> {
    val cteRteMap = nodeTreeParser.parseCteRangeTableEntries(nodeTree)
    if (cteRteMap.isEmpty()) return emptyMap()
    if (resolvedCtes.isEmpty()) return emptyMap()

    return buildMap {
      for ((varno, reference) in cteRteMap) {
        val nullabilities = resolvedCtes[reference.name] ?: continue
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
   * Builds a map from `(varno, varattno)` to `true` for CTE RTE columns that are non-null.
   *
   * `:ctelevelsup 0` means [queryBlock] declares that CTE itself, possibly shadowing a sibling of the
   * same name one level up, so it resolves from [ownResolvedCtes]; anything greater resolves from
   * [previouslyResolved]. Without this split a local shadowing `WITH` resolved against the wrong
   * sibling body — an unsound answer, not merely a widened one (`#257`).
   *
   * @param ownResolvedCtes CTE bodies declared directly in [queryBlock]'s own `:cteList`.
   * @param previouslyResolved CTE bodies declared in the same outer `:cteList` [queryBlock]'s own CTE
   *   is declared in (siblings declared earlier in that `WITH` clause).
   */
  private fun buildInnerCteNotNull(
    queryBlock: String,
    ownResolvedCtes: Map<String, List<Boolean>>,
    previouslyResolved: Map<String, List<Boolean>>,
  ): Map<Pair<Int, Int>, Boolean> {
    val innerCteRtes = nodeTreeParser.parseCteRangeTableEntries(queryBlock)
    return buildMap {
      for ((varno, reference) in innerCteRtes) {
        val ctesInScope = if (reference.ctelevelsup == 0) ownResolvedCtes else previouslyResolved
        val nullabilities = ctesInScope[reference.name] ?: continue
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
    // queryBlock's OWN nested WITH clause, distinct from previouslyResolved (sibling CTEs one level
    // further up). Resolving any of the three uses below against previouslyResolved instead would
    // resolve a shadowing local WITH against the wrong body — see #257.
    val ownResolvedCtes = resolveCteBodies(queryBlock, applyQualNarrowing, sql)
    val innerCteNotNull = buildInnerCteNotNull(queryBlock, ownResolvedCtes, previouslyResolved)
    val subqueryColumnNotNull = buildSubqueryColumnNotNull(queryBlock, ownResolvedCtes, applyQualNarrowing, sql)
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
    return buildAnalyzer(
      hasGroupingSets = hasGroupingSets,
      forceNewNullable = forcesNewNullable(queryBlock),
      applyQualNarrowing = applyQualNarrowing,
      resolvedCtes = ownResolvedCtes,
    ) {
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
   * @param nodeTree the `pg_rewrite.ev_action` text of the outer query's temporary view, or a
   *   nested `{QUERY ...}` block reached via [analyzeQueryBlockNullability] (a `SubLink`'s own
   *   subselect, or a derived table nested inside one)
   * @param resolvedCtes CTE bodies declared directly in [nodeTree]'s own `:cteList`. A subquery
   *   nested inside [nodeTree] can reference one of these via `:ctelevelsup 1` inside its OWN
   *   `:rtable`, not [nodeTree]'s. Computed by every caller so the same resolution also feeds
   *   [buildAnalyzer] for a `SubLink` nested in [nodeTree].
   * @param applyQualNarrowing See [analyzeNodeTree]'s parameter of the same name.
   * @param depth The [subLinkSubqueryColumnNotNull] recursion budget, threaded to
   *   [analyzeQueryBlockNullability] for a `SubLink` reached via one of [nodeTree]'s subquery RTEs.
   *   Callers resolving [nodeTree]'s own top-level subquery RTEs use the default, full budget — a
   *   `FROM`-clause hop is not a nested-sublink hop. [analyzeQueryBlockNullability]'s own recursive
   *   call passes its current, possibly already-decremented `depth` through unchanged: it is the only
   *   path reaching a derived table nested INSIDE a `SubLink`'s subselect, and refilling the budget
   *   there would let a chain of `= ANY` sublinks separated by derived tables bypass it (`#257`).
   * @return A map from `(varno, varattno)` pairs to `true` when the subquery column is non-null
   */
  private fun buildSubqueryColumnNotNull(
    nodeTree: String,
    resolvedCtes: Map<String, List<Boolean>>,
    applyQualNarrowing: Boolean = true,
    @Language("PostgreSQL") sql: String = "",
    depth: Int = SUBLINK_ANALYSIS_DEPTH_BUDGET,
  ): Map<Pair<Int, Int>, Boolean> {
    // Set-operation queries (UNION ALL, INTERSECT, EXCEPT) store their branches as rtekind=1
    // subquery RTEs. Tracing through them would incorrectly report the first branch's nullability
    // as the result's nullability — the true result is the union across ALL branches, some of which
    // may introduce nulls (e.g., a branch with LEFT JOIN). Return empty so the analyzer conservatively
    // treats set-operation output columns as nullable (the correct safe default). This also covers
    // analyzeQueryBlockNullability's recursive call into this method.
    if (nodeTreeParser.hasSetOperations(nodeTree)) return emptyMap()
    val subqueryRangeTable = nodeTreeParser.parseSubqueryRangeTable(nodeTree)
    if (subqueryRangeTable.isEmpty()) return emptyMap()
    return buildMap {
      for ((outerVarno, subqueryBlock) in subqueryRangeTable) {
        val subNullabilities =
          analyzeQueryBlockNullability(subqueryBlock, applyQualNarrowing, resolvedCtes, depth, sql)
        subNullabilities.forEachIndexed { columnIndex, nullable ->
          // columnIndex is 0-based; varattno is 1-based
          put(outerVarno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  /**
   * Computes per-column nullability for a single query block ([queryBlock]) — the shared core of
   * [buildSubqueryColumnNotNull] (a `FROM`-clause subquery RTE) and [subLinkSubqueryColumnNotNull]
   * (an `ANY_SUBLINK`'s or `ALL_SUBLINK`'s `:subselect`): given a raw `{QUERY ...}` block, build a resolver over the
   * block's OWN `parseRangeTable`/`parseCteRangeTableEntries`/`parseGroupRteMap`/subquery-RTE/qual
   * narrowing, then run [NodeTreeNullabilityAnalyzer.extractColumnNullability] against it.
   *
   * [queryBlock]'s own `:rtable` can hold three things resolved differently: a base table (via
   * [isColumnNotNull]), a nested subquery RTE (a derived table, resolved by recursing into
   * [buildSubqueryColumnNotNull] on [queryBlock] itself), and a CTE RTE. Before `#257` only the
   * base-table case was handled, so a `SubLink`'s subselect reading either of the others degraded to
   * nullable.
   *
   * @param resolvedCtes CTE bodies visible via `:ctelevelsup` greater than `0` relative to
   *   [queryBlock] — declared in whichever scope ENCLOSES it, never [queryBlock]'s own nested `WITH`
   *   clause. Every caller must uphold this; resolving a `Var` against the wrong CTE body is silently
   *   worse than widening — see [NodeTreeCteReference.ctelevelsup]. A CTE at `:ctelevelsup 2` (a
   *   sibling of [queryBlock]'s own enclosing CTE) is out of this flat map's reach and stays
   *   conservatively nullable; reaching it would mean threading a stack of scopes through every caller.
   * @param depth See [buildAnalyzer]'s parameter of the same name — passed through unchanged so a
   *   `SubLink` inside [queryBlock], and this method's own [buildSubqueryColumnNotNull] call for a
   *   derived table nested inside it, both get the already-decremented budget.
   * @param sql See [mergeAbsentVarnos]'s `sql` parameter — passed through only so a data-modifying CTE
   *   in [queryBlock]'s own nested `WITH` clause can resolve its `MERGE`. The empty-string default
   *   makes that `EXPLAIN` fail harmlessly, the same limitation [buildCteBodyAnalyzer]'s default has.
   */
  private fun analyzeQueryBlockNullability(
    queryBlock: String,
    applyQualNarrowing: Boolean,
    resolvedCtes: Map<String, List<Boolean>>,
    depth: Int = SUBLINK_ANALYSIS_DEPTH_BUDGET,
    @Language("PostgreSQL") sql: String = "",
  ): List<Boolean> {
    // Parse the block's own base-table range table for isSourceColumnNotNull.
    val subRangeTable = nodeTreeParser.parseRangeTable(queryBlock)
    val subCteRteMap = nodeTreeParser.parseCteRangeTableEntries(queryBlock)
    // See analyzeNodeTree's identical guard: GROUPING SETS/CUBE/ROLLUP can
    // null-extend a grouping key even when the base table column is NOT NULL. The actual
    // override (including EXPRESSION grouping keys) is applied by extractColumnNullability
    // via hasGroupingSets below.
    val hasGroupingSets = nodeTreeParser.hasGroupingSets(queryBlock)
    val groupRteMap = if (hasGroupingSets) {
      emptyMap()
    } else {
      nodeTreeParser.parseGroupRteMap(queryBlock)
    }
    // See analyzeNodeTree's identical guard: suppress qual narrowing whenever
    // hasGroupingSets, because those are exactly what GROUPING SETS/CUBE/ROLLUP null-extends
    // after WHERE has already filtered rows.
    val subQualNotNullVars = if (applyQualNarrowing && !hasGroupingSets) {
      NodeTreeNullabilityAnalyzer.qualProvenNonNullVars(queryBlock, isStrictFunction)
    } else {
      emptySet()
    }
    // A ctelevelsup-0 reference must resolve against queryBlock's OWN CTEs, never the resolvedCtes
    // parameter, which belongs to an enclosing scope.
    val ownResolvedCtes = resolveCteBodies(queryBlock, applyQualNarrowing, sql)
    // depth is threaded, not defaulted: this is the recursive hop that must NOT refill the budget.
    val subqueryColumnNotNull = buildSubqueryColumnNotNull(queryBlock, ownResolvedCtes, applyQualNarrowing, sql, depth)
    val subAnalyzer = buildAnalyzer(
      hasGroupingSets = hasGroupingSets,
      applyQualNarrowing = applyQualNarrowing,
      depth = depth,
      resolvedCtes = ownResolvedCtes,
    ) { subVarno, subVarattno ->
      if (isProvenByQuals(subQualNotNullVars, groupRteMap, subVarno, subVarattno)) {
        true
      } else {
        val relid = subRangeTable[subVarno]
        if (relid != null) {
          isColumnNotNull(relid to subVarattno)
        } else if (subqueryColumnNotNull[subVarno to subVarattno] == true) {
          true
        } else {
          val cteReference = subCteRteMap[subVarno]
          if (cteReference == null) {
            false
          } else {
            val ctesInScope = if (cteReference.ctelevelsup == 0) ownResolvedCtes else resolvedCtes
            ctesInScope[cteReference.name]?.getOrNull(subVarattno - 1) == false
          }
        }
      }
    }
    return subAnalyzer.extractColumnNullability(queryBlock)
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
      preparedStatement.executeQuery().use { resultSet ->
        check(resultSet.next()) { "Expected exactly one row from the substitution-safety EXISTS query" }
        !resultSet.getBoolean("has_risky_relkind") &&
          !resultSet.getBoolean("has_mutating_row_trigger") &&
          !resultSet.getBoolean("has_non_view_rewrite_rule")
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
      preparedStatement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getString(1) else null }
    }
  } catch (_: SQLException) {
    null
  }
}
