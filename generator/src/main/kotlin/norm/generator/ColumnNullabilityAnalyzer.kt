package norm.generator

import org.intellij.lang.annotations.Language
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Recursion budget for [ColumnNullabilityAnalyzer.subLinkSubqueryColumnNotNull]: how many levels
 * of NESTED `ANY_SUBLINK`/`ALL_SUBLINK` (a `SubLink` whose own subselect contains another
 * `SubLink`) are resolved before defaulting to nullable.
 *
 * Deliberately small: a chain of four nested `= ANY (...)` sublinks that is semantically NOT NULL
 * end-to-end is reported nullable at this budget, pinning that the specific value `3`, not merely
 * its presence, is what is enforced.
 */
private const val SUBLINK_ANALYSIS_DEPTH_BUDGET = 3

/**
 * Recursion budget for [ColumnNullabilityAnalyzer.resolveViewColumnNullability]: how many levels of
 * nested view-over-view resolution are followed before returning the conservative (nullable) answer.
 *
 * Required to prevent a `java.lang.StackOverflowError`: resolution recurses through real JVM stack
 * frames, and `CREATE VIEW` permits unbounded nesting. `50` fits in a 512 KiB thread stack. A
 * separate cycle guard in [ViewColumnNullabilityResolver] catches cycles, which can be much shallower.
 *
 * A relid within this many levels of the deepest point of a chain that itself exceeds the budget
 * can get either the truncated or the fully-resolved answer, depending on which views were memoized
 * first, since the depth check runs before the memo lookup. Both answers are sound — truncation
 * only widens — and the sweep order is fixed, so a given schema always generates the same Kotlin.
 */
internal const val VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET = 50

/**
 * One result column's [nullable] flag (`true` means nullable) together with, when resolvable, its
 * [provenanceExpression] — the CTE-body SQL expression, verbatim from the developer's own query
 * text, for a column whose select item is merely a bare reference into a CTE's output. `null` when
 * no CTE reference could be attributed to this column, or the expression could not be proven
 * correct.
 *
 * Also carries [originalColumnName] — the real source column name, resolved from the outer target
 * entry's own `:resorigtbl`/`:resorigcol` rather than whatever alias the select item's text happens
 * to spell. `null` when those fields are `0` (no single source column) or the OID/attnum pair isn't
 * in the catalog map — the caller must fall back to its ordinary column-name resolution, never
 * guess.
 */
internal data class ColumnAnalysis(
  val nullable: Boolean,
  val provenanceExpression: String?,
  val originalColumnName: String? = null,
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
 * Everything needed to answer `isSourceColumnNotNull` — how a `Var` reference inside one query
 * block resolves to a source column's not-null answer — for a single query block, whether that
 * block is [ColumnNullabilityAnalyzer]'s own outermost statement, a CTE body, a `FROM`-clause
 * subquery, or a `SubLink`'s subselect. Built by [ColumnNullabilityAnalyzer.buildQueryBlockScope]
 * so all four call shapes share exactly one fallback chain.
 *
 * [qualProvenVars] and [groupRteMap] are computed empty, so their branches below never fire, when
 * the query block has GROUPING SETS/CUBE/ROLLUP (which null-extends a grouping key AFTER `WHERE`
 * has already filtered rows) or is itself an `INSERT`/`UPDATE`/`DELETE`/`MERGE` (whose own `WHERE`
 * clause can test a column value its `SET` clause, or a `MERGE` action, is about to overwrite).
 *
 * @property rangeTable varno to relid, base tables only.
 * @property hasGroupingSets `true` when the query block uses `GROUPING SETS`, `CUBE`, or `ROLLUP`.
 * @property groupRteMap `(groupVarno, attrPos)` to `(baseVarno, baseVarattno)`, empty whenever
 *   [hasGroupingSets].
 * @property qualProvenVars `(varno, varattno)` pairs the query block's own `WHERE` clause proves
 *   non-null, empty whenever qual narrowing does not apply.
 * @property ownCtes CTE bodies declared directly in the query block's own `:cteList`, keyed by
 *   name.
 * @property enclosingCtes CTE bodies visible via `:ctelevelsup` greater than `0` — declared in
 *   whichever scope encloses the query block, never its own nested `WITH` clause. Empty for the
 *   outermost statement, which has no enclosing scope to point past.
 * @property cteReferences varno to CTE reference, for a `Var` whose range-table entry is a CTE
 *   rather than a base table or subquery.
 * @property subqueryColumnNotNull `(varno, varattno)` to `true` for a `FROM`-clause subquery RTE
 *   column already proven non-null by recursively analyzing that subquery's own target list.
 * @property mergeAbsentVarnos varno to whether that relation can be entirely absent for some
 *   result row, only when the query block is itself a `MERGE` — empty for every other shape.
 * @property forceNewNullable `true` when a `RETURNING WITH (OLD AS o, NEW AS n)` reference to
 *   `NEW` must be forced nullable.
 * @property resultRelationVarno the query block's own `:resultRelation` varno, `0` for a plain
 *   `SELECT`.
 * @property isColumnNotNull `true` when base-table or view column `(relid, attnum)` is never `null`.
 */
private class QueryBlockScope(
  val rangeTable: Map<Int, Int>,
  val hasGroupingSets: Boolean,
  val groupRteMap: Map<Pair<Int, Int>, Pair<Int, Int>>,
  val qualProvenVars: Set<Pair<Int, Int>>,
  val ownCtes: Map<String, List<Boolean>>,
  val enclosingCtes: Map<String, List<Boolean>>,
  val cteReferences: Map<Int, NodeTreeCteReference>,
  val subqueryColumnNotNull: Map<Pair<Int, Int>, Boolean>,
  val mergeAbsentVarnos: Map<Int, Boolean>,
  val forceNewNullable: Boolean,
  val resultRelationVarno: Int,
  val isColumnNotNull: (Pair<Int, Int>) -> Boolean,
) {
  /**
   * The source-column-resolution chain every query block shape resolves a `Var` through: a
   * `MERGE` relation `EXPLAIN` proved can be entirely absent for some result row, a `WHERE`-clause
   * qual (directly or through the GROUP RTE remap), a base-table relation's own catalog constraint
   * (via [isColumnNotNull]), a GROUP RTE remapped back to its base column, a `FROM`-clause
   * subquery's already-resolved column, or a CTE reference resolved against whichever of
   * [ownCtes]/[enclosingCtes] its own `:ctelevelsup` selects.
   */
  fun isSourceColumnNotNull(varno: Int, varattno: Int): Boolean {
    if (mergeAbsentVarnos[varno] == true) return false
    if (isProvenByQuals(qualProvenVars, groupRteMap, varno, varattno)) return true
    rangeTable[varno]?.let { relid -> return isColumnNotNull(relid to varattno) }
    groupRteMap[varno to varattno]?.let { (baseVarno, baseAttno) ->
      val baseRelid = rangeTable[baseVarno] ?: return false
      return isColumnNotNull(baseRelid to baseAttno)
    }
    if (subqueryColumnNotNull[varno to varattno] == true) return true
    val reference = cteReferences[varno] ?: return false
    val ctesInScope = if (reference.ctelevelsup == 0) ownCtes else enclosingCtes
    return ctesInScope[reference.name]?.getOrNull(varattno - 1) == false
  }
}

/**
 * Drives per-column nullability analysis for a SQL query: fetching the query's own parsed node
 * tree via a temporary `prosqlbody` probe function, then recursively resolving CTE bodies,
 * subqueries, and `MERGE` actions to feed
 * [NodeTreeNullabilityAnalyzer] the source-column not-null information it needs to evaluate each
 * result column's expression. [catalog] answers "what does the catalog say" (function strictness,
 * safe-list membership, column not-null facts); this class combines those answers with the
 * query's own parsed structure to answer "is this specific result column nullable".
 */
internal class ColumnNullabilityAnalyzer(private val connection: Connection, private val catalog: NullabilityCatalog) {
  private val nodeTreeParser = PgNodeTreeParser()

  /** Untainted view-column nullability by relid, kept across top-level [resolveViewColumnNullability] calls. */
  private val viewColumnNullabilityMemo = mutableMapOf<Int, ViewNullabilityCacheEntry>()

  /**
   * Resolver for the top-level [resolveViewColumnNullability] call in progress, or `null` when there is none. Nested
   * view references re-enter through [isColumnNotNull] and reuse it.
   */
  private var activeViewNullabilityTraversal: ViewColumnNullabilityResolver? = null

  /**
   * Replaces `?` parameter placeholders in [sql] with typed non-null sentinel values (e.g.,
   * `0::int4`, `''::text`).
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
      replaceParameterPlaceholders(sql) { sentinels.getOrElse(it) { "NULL" } }
    } catch (_: SQLException) {
      null
    }
  }

  /**
   * For [nodeTree]'s own outermost statement — never recursing into a CTE it declares; each CTE's
   * own body resolves its own MERGE independently — determines which of its two base-table
   * relations (identified by `:rtable` varno) can be entirely absent for some result row, via
   * [explainMergeSideNullability].
   *
   * A `MERGE`'s match-optionality (`WHEN NOT MATCHED BY SOURCE`, `WHEN NOT MATCHED [BY TARGET]
   * THEN INSERT`) is invisible to `:varnullingrels`: a `MERGE ... WHEN NOT MATCHED BY SOURCE THEN
   * DELETE RETURNING src.col` has an empty `:varnullingrels` on `src.col`'s `Var`, identical to an
   * ordinary, always-present reference.
   *
   * @param sql the EXACT (already sentinel-substituted) statement text to run `EXPLAIN` against —
   *   the whole top-level statement, including any leading `WITH` clause, so a `MERGE` nested
   *   inside a CTE resolves through the same call as a top-level one, keyed by its own
   *   target/source relation names
   * @return an EMPTY map when [nodeTree]'s own outermost statement is not a `MERGE` at all; a map
   *   from varno to whether THAT relation can be entirely absent (containing the target and/or
   *   source varno, per [MergeSideNullability]) when it is a `MERGE` and `EXPLAIN` successfully
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
    // never needs EXPLAIN's resolution at all. Skipping it here matters beyond saving an EXPLAIN
    // round trip: a MERGE whose USING source is not a plain base table or CTE (e.g. a VALUES list
    // or a subquery) can never be resolved below, but that must not block a RETURNING list that
    // never depended on knowing which side of that join is nullable.
    val returningEntries = nodeTreeParser.parseReturningList(nodeTree)
    if (returningEntries.none { NodeTreeNullabilityAnalyzer.containsVarOutsideRelation(it.expression, targetVarno) }) {
      return emptyMap()
    }
    val targetRelid = rangeTable[targetVarno] ?: return null
    // A simple `MERGE INTO target USING source ON ...` has exactly one other :rtable entry besides
    // the target — the source, of any rtekind. A `USING` clause with more than one relation of its
    // own (e.g. a join or subquery source) has no single relation this method can attribute a join
    // side to, so it bails rather than guess. Reads the FULL range table, not [rangeTable] (base
    // tables only), since a CTE source's own varno never appears there at all.
    val sourceEntries = nodeTreeParser.parseRangeTableEntries(nodeTree).filterKeys { it != targetVarno }
    if (sourceEntries.size != 1) return null
    val (sourceVarno, sourceEntry) = sourceEntries.entries.single()
    val targetName = resolveTableName(targetRelid) ?: return null
    val sourceNames = mergeSourceRelationNameCandidates(nodeTree, sourceEntry) ?: return null
    val mapping = explainMergeSideNullability(connection, sql, targetName, sourceNames) ?: return null
    return buildMap {
      put(targetVarno, mapping.targetCanBeAbsent)
      put(sourceVarno, mapping.sourceCanBeAbsent)
    }
  }

  /**
   * The name(s) `EXPLAIN`'s plan JSON might attribute to [sourceEntry] — a `MERGE`'s own `USING`
   * source relation — so [explainMergeSideNullability] can match it regardless of how the planner
   * chooses to execute it.
   *
   * A plain base-table source ([RangeTableEntry.Relation]) has exactly one name: its own catalog
   * name, via [resolveTableName]. A CTE source ([RangeTableEntry.Cte]) can appear in the plan
   * either under its own `WITH`-clause name (a `"CTE Scan"` node, when not inlined) or, when
   * PostgreSQL inlines it into whatever it scans, under the name [resolveInlinedBaseRelationName]
   * recovers — only for the simplest possible body, `SELECT ... FROM oneBaseTable`. Offering both
   * candidates together never risks a false attribution: for a given plan, at most one can appear.
   *
   * @return `null` when [sourceEntry] is neither a base table nor a CTE (a join, subquery, function,
   *   `VALUES`, or another `rtekind` this cannot safely name)
   */
  private fun mergeSourceRelationNameCandidates(nodeTree: String, sourceEntry: RangeTableEntry): Set<String>? =
    when (sourceEntry) {
      is RangeTableEntry.Relation -> resolveTableName(sourceEntry.relid)?.let(::setOf)
      is RangeTableEntry.Cte -> buildSet {
        add(sourceEntry.reference.name)
        resolveInlinedBaseRelationName(nodeTree, sourceEntry.reference.name)?.let(::add)
      }
      else -> null
    }

  /**
   * The bare table name [cteName]'s body resolves to, only when that body is nothing but a plain
   * `SELECT ... FROM oneBaseTable` — a single `rtekind 0` range-table entry and nothing else.
   *
   * @return `null` when [cteName] cannot be found in [nodeTree]'s own `:cteList`, or its body is
   *   anything other than exactly one base-table range-table entry
   */
  private fun resolveInlinedBaseRelationName(nodeTree: String, cteName: String): String? {
    val cteDefinition = nodeTreeParser.parseCteList(nodeTree).find { it.name == cteName } ?: return null
    val onlyEntry = nodeTreeParser.parseRangeTableEntries(cteDefinition.queryBlock).values.singleOrNull()
    return (onlyEntry as? RangeTableEntry.Relation)?.let { resolveTableName(it.relid) }
  }

  /**
   * Determines which result columns of a SQL query can be NULL, using full expression evaluation,
   * by way of PostgreSQL's `prosqlbody` — the analyzed query tree PostgreSQL stores for a
   * SQL-standard (`BEGIN ATOMIC ... END`) function body.
   *
   * `prosqlbody` holds the same post-parse-analysis `{QUERY ...}` node shape `pg_rewrite.ev_action`
   * does, but — unlike `CREATE VIEW` — PostgreSQL populates it for `UPDATE`/`DELETE`/`MERGE ...
   * RETURNING` and for data-modifying CTEs, because only `CREATE VIEW` itself rejects a
   * data-modifying statement, not a SQL-standard function body.
   *
   * The function is created with ZERO arguments: a real `$n` parameter would appear as a `PARAM`
   * node, silently widening every parameter-touching column to nullable. [sql]'s own `?`
   * placeholders are therefore replaced with typed non-null sentinel literals internally, via
   * [buildViewSqlWithSentinels], before this method is ever invoked.
   *
   * A statement with no result columns at all (an `INSERT`/`UPDATE`/`DELETE`/`MERGE` without
   * `RETURNING`) fails PostgreSQL's `RETURNS SETOF record` check on function creation — there is
   * nothing to probe, and the [SQLException] is caught here rather than propagated.
   *
   * Provenance piggybacks the same round trip: each column's expression text is extracted and
   * cross-validated from [sql] — the original, un-substituted query text, never [substitutedSql] —
   * so a sentinel literal built only to satisfy a `?`'s type can never leak into generated KDoc.
   *
   * @param sql the SQL query or DML statement to analyze; any `?` parameter placeholder is
   *   replaced with a typed non-null sentinel literal internally only for building the probe
   *   function — [sql] itself, `?` intact, is what provenance expression text is extracted from
   * @return one [ColumnAnalysis] per result column in `SELECT`/`RETURNING` order, or `null` if
   *   [sql] has no result columns to probe or the probe itself failed for any reason — the caller
   *   must treat `null` as "this path has no answer", never as "zero columns."
   */
  internal fun queryColumnNullabilityViaProsqlbody(@Language("PostgreSQL") sql: String): List<ColumnAnalysis>? {
    val substitutedSql = buildViewSqlWithSentinels(sql) ?: replaceParameterPlaceholders(sql) { "NULL" }
    val functionName = "norm_nullability_${UUID.randomUUID().toString().replace("-", "")}"
    return try {
      connection.createStatement().use { statement ->
        statement.execute(
          "CREATE FUNCTION pg_temp.$functionName() RETURNS SETOF record LANGUAGE sql " +
            // The newline before "; END" is required, not style: substitutedSql can legitimately
            // end in a trailing `--` line comment, which extends to end of line; without a newline
            // separating it from "; END", the comment swallows the terminator too.
            "BEGIN ATOMIC $substitutedSql\n; END",
        )
      }
      try {
        val nodeTree = connection.createStatement().use { statement ->
          statement.executeQuery(
            "SELECT prosqlbody::text FROM pg_proc " +
              // "pg_temp" is a per-session ALIAS, not a literal schema name: 'pg_temp'::regnamespace
              // fails with `ERROR: schema "pg_temp" does not exist`. pg_my_temp_schema() returns the
              // current session's actual temp schema OID directly.
              "WHERE proname = '$functionName' AND pronamespace = pg_my_temp_schema()",
          ).use { resultSet ->
            check(resultSet.next()) { "No pg_proc row found for probe function $functionName" }
            resultSet.getString(1)
          }
        }
        val rangeTable = nodeTreeParser.parseRangeTableEntries(nodeTree).baseRelations()
        val mergeAbsent = mergeAbsentVarnos(nodeTree, rangeTable, substitutedSql) ?: return null
        // '?' in sql, not substitutedSql: a sentinel-substituted CONST is byte-identical to a
        // hand-written literal once embedded in the SQL text — the parsed tree retains no memory
        // of which one it was. trustAssignedExpressions=false whenever the original sql had any
        // parameter blocks the :targetList-to-:returningList substitution for the whole statement,
        // not just the specific assignment a parameter feeds, since there is no structural way from
        // here to tell which assignment(s) it was.
        val nullability = analyzeNodeTree(
          nodeTree,
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
   * Resolves each result column's REAL source column name from [PgNodeTreeParser.resultProjection]'s
   * entries for [nodeTree] — the same projection [analyzeNodeTree] reads for nullability, so the two
   * stay index-aligned.
   *
   * Each entry's own `:resorigtbl`/`:resorigcol` — [TargetEntry.originalTableOid] and
   * [TargetEntry.originalColumnNumber] — name the column PostgreSQL itself traced this result
   * column back to, walking through a CTE or subquery reference rather than stopping at the
   * immediate select item's own alias: `WITH c AS (SELECT id AS parent_id FROM parent) SELECT
   * parent_id FROM c`'s outer target entry carries `:resorigtbl`/`:resorigcol` for `parent.id`, not
   * the CTE's own `parent_id` alias.
   *
   * @return one entry per result column: the resolved column name, or `null` when
   *   [TargetEntry.originalTableOid]/[TargetEntry.originalColumnNumber] is `0` (no single source
   *   column — a computed expression, an aggregate, a set-operation branch, or a `USING`/`NATURAL`
   *   merged join column) or the OID/attnum pair is absent from the catalog map for any other
   *   reason. The caller must treat `null` as "fall back to the ordinary resolution", never guess.
   */
  private fun resolveOriginalColumnNames(nodeTree: String): List<String?> =
    nodeTreeParser.resultProjection(nodeTree).entries.map { entry ->
      if (entry.originalTableOid == 0 || entry.originalColumnNumber == 0) {
        null
      } else {
        catalog.columnNameByRelidAndAttnum[entry.originalTableOid to entry.originalColumnNumber]
      }
    }

  /**
   * Computes per-column nullability from [nodeTree] — the `pg_rewrite.ev_action` text of a
   * temporary view, or the `pg_proc.prosqlbody` text of a temporary probe function; both share the
   * same post-parse-analysis `{QUERY ...}` node shape.
   *
   * Reads [PgNodeTreeParser.resultProjection]'s entries: the `RETURNING` projection of a topmost
   * `INSERT`/`UPDATE`/`DELETE`/`MERGE ... RETURNING`, otherwise the `:targetList` of every plain
   * `SELECT`, including one that reaches this function only because it CONTAINS a data-modifying CTE.
   */
  private fun analyzeNodeTree(
    nodeTree: String,
    @Language("PostgreSQL") sql: String,
    trustAssignedExpressions: Boolean = true,
    mergeAbsentVarnos: Map<Int, Boolean> = emptyMap(),
  ): List<Boolean> {
    val scope =
      buildQueryBlockScope(
        nodeTree,
        emptyMap(),
        sql = sql,
        depth = SUBLINK_ANALYSIS_DEPTH_BUDGET,
        mergeAbsentVarnos = mergeAbsentVarnos,
      )
    // A non-zero :resultRelation means this is an INSERT/UPDATE/DELETE/MERGE, not a SELECT. Its
    // :targetList holds the value expressions being written to each explicitly-assigned column of
    // the target relation (keyed by :resno = the column's attribute number), which is exactly what
    // a :returningList Var referencing that same (resultRelationVarno, attno) pair actually reads
    // back — not the column's general catalog constraint, which says nothing about what this
    // statement is about to write.
    val targetListByResno = if (scope.resultRelationVarno == 0 || !trustAssignedExpressions) {
      // !trustAssignedExpressions means the original sql (before sentinel substitution) contained a
      // `?` parameter placeholder somewhere. A sentinel-substituted CONST is byte-identical, in the
      // parsed tree, to a hand-written literal: there is no structural signal left to tell "the
      // caller supplied this at runtime, and could supply NULL" from "the query text itself
      // guarantees this value", so trusting :targetList at all is unsafe once any parameter exists
      // anywhere in the statement. `INSERT INTO t(name) VALUES (?) RETURNING name` reports NOT NULL
      // if the sentinel substitution is trusted here, even though the caller can bind `NULL`.
      emptyMap()
    } else {
      // rangeTable[resultRelationVarno] is only present for an ordinary base-table target (rtekind
      // 0) — never null for a real INSERT/UPDATE/DELETE/MERGE — but defensively treated as
      // "substitution unsafe" rather than trusting an assignment against an unidentified target.
      val targetRelid = scope.rangeTable[scope.resultRelationVarno]
      if (targetRelid != null && isSubstitutionSafeForRelation(targetRelid)) {
        nodeTreeParser.parseTargetList(nodeTree).associate { it.resultNumber to it.expression }
      } else {
        emptyMap()
      }
    }
    val plainIsSourceColumnNotNull = { varno: Int, varattno: Int ->
      scope.isSourceColumnNotNull(varno, varattno)
    }
    val analyzer = buildAnalyzer(scope, depth = SUBLINK_ANALYSIS_DEPTH_BUDGET)
    // See PgNodeTreeParser.resultProjection for why :returningList is checked before :targetList.
    val projection = nodeTreeParser.resultProjection(nodeTree)
    if (projection.fromReturningList) {
      // A separate analyzer whose isSourceColumnNotNull substitutes a Var referencing
      // (resultRelationVarno, resno) with the assigned expression's own nullability — evaluated by
      // the plain analyzer, deliberately not this substituting one, so a self-referencing
      // assignment (`SET note = note || 'x'`) reads note's old (plain, un-substituted) value for
      // that inner reference rather than looping back into its own substitution forever. A column
      // the statement never assigns (no entry in targetListByResno) falls through to the identical
      // plain catalog/qual/subquery/CTE resolution [analyzer] itself uses, which is exactly correct
      // for that column's pass-through (unmodified) value.
      val returningAnalyzer =
        buildAnalyzer(
          hasGroupingSets = false,
          forceNewNullable = scope.forceNewNullable,
          depth = SUBLINK_ANALYSIS_DEPTH_BUDGET,
          resolvedCtes = scope.ownCtes,
        ) { varno, varattno ->
          val assignedExpression = if (varno == scope.resultRelationVarno) targetListByResno[varattno] else null
          if (assignedExpression != null) {
            analyzer.isNonNull(assignedExpression)
          } else {
            plainIsSourceColumnNotNull(varno, varattno)
          }
        }
      return projection.entries.map { entry -> !returningAnalyzer.isNonNull(entry.expression) }
    }
    return analyzer.extractColumnNullability(nodeTree)
  }

  /**
   * `true` when `(relid, attnum)` — [key] — is guaranteed NOT NULL, whether it identifies a base-table
   * column (checked against [NullabilityCatalog.columnNotNullByRelidAndAttnum]) or a view column (resolved via
   * [resolveViewColumnNullability], since `pg_attribute.attnotnull` is always `false` for a view
   * column regardless of the view's definition).
   *
   * `internal`, not `private`: [PgCatalogLoader.loadViewColumnNullability] calls this directly.
   */
  internal fun isColumnNotNull(key: Pair<Int, Int>): Boolean {
    // A negative attribute number is a SYSTEM column (ctid, xmin, xmax, cmin, cmax, tableoid),
    // unconditionally non-null for any real, returned row, but absent from the catalog's own
    // not-null map (which only tracks attnum > 0).
    if (key.second < 0) return true
    if (catalog.columnNotNullByRelidAndAttnum[key] == true) return true
    val viewNullability = resolveViewColumnNullability(key.first) ?: return false
    return viewNullability.getOrNull(key.second - 1) == false
  }

  /**
   * Resolves [relid]'s per-column nullability by fully evaluating its view definition's own node
   * tree (`pg_rewrite`'s `_RETURN` rule), rather than inheriting a same-named source column's
   * constraint: `SELECT NULLIF(v, 'x') AS v FROM u` is nullable regardless of whether `u.v` is
   * `NOT NULL`.
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
    activeViewNullabilityTraversal?.let { return it.resolve(relid) }
    val traversal = ViewColumnNullabilityResolver(
      permanentCache = viewColumnNullabilityMemo,
      fetchNodeTree = ::fetchViewNodeTree,
      analyzeNodeTree = ::analyzeViewOrSetOperationNodeTree,
      columnCountFor = ::columnCountFor,
    )
    activeViewNullabilityTraversal = traversal
    try {
      return traversal.resolve(relid)
    } finally {
      activeViewNullabilityTraversal = null
    }
  }

  /**
   * Analyzes [nodeTree], the `_RETURN` rule of view [relid].
   *
   * @return one nullable flag per target-list entry, or `null` when analysis throws [SQLException].
   */
  private fun analyzeViewOrSetOperationNodeTree(relid: Int, nodeTree: String): List<Boolean>? = try {
    if (nodeTreeParser.hasSetOperations(nodeTree)) {
      // A top-level UNION ALL/INTERSECT/EXCEPT must be resolved branch-by-branch and OR-combined.
      // The synthetic name cannot collide with a CTE declared inside the view's own body, so
      // previouslyResolved's self-reference entry is never consulted here.
      analyzeSetOperationBranches(nodeTree, emptyMap(), "__norm_view_relid_$relid")
    } else {
      analyzeViewNodeTree(nodeTree)
    }
  } catch (_: SQLException) {
    null
  }

  /** The number of user-visible columns (`attnum > 0 AND NOT attisdropped`) [relid] has. */
  private fun columnCountFor(relid: Int): Int = catalog.columnNotNullByRelidAndAttnum.keys.count { it.first == relid }

  /**
   * Aligns [nullability] — one flag per non-junk target-list entry, in resno order — onto exactly
   * [expectedColumnCount] entries, falling back to nullable for every column when the two disagree or
   * when [nullability] is `null` (the analysis could not answer at all).
   *
   * `internal`, not `private`, purely so a unit test can drive it with a synthetic mismatch.
   */
  internal fun alignViewColumnNullability(nullability: List<Boolean>?, expectedColumnCount: Int): List<Boolean> =
    alignedViewColumnNullability(nullability, expectedColumnCount)

  /**
   * `true` when [relid] has an entry in [viewColumnNullabilityMemo]. Lets a test detect a tainted answer that leaked
   * there when its value matches the untainted one.
   */
  internal fun isRelidMemoized(relid: Int): Boolean = viewColumnNullabilityMemo.containsKey(relid)

  /**
   * Evaluates [nodeTree] — a view's own `pg_rewrite.ev_action` `_RETURN` rule text — through the same
   * [analyzeNodeTree] machinery an ordinary query uses, rather than widening [analyzeNodeTree]'s
   * visibility for this one caller. A view's defining query is always a plain `SELECT` with no `?`
   * placeholders, so [analyzeNodeTree]'s `trustAssignedExpressions`/`mergeAbsentVarnos` defaults
   * apply.
   */
  internal fun analyzeViewNodeTree(nodeTree: String): List<Boolean> = analyzeNodeTree(nodeTree, sql = "")

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
   * Creates a [NodeTreeNullabilityAnalyzer] pre-configured with [catalog]'s lookups. All
   * constructor arguments except [isSourceColumnNotNull] are identical across every call site in
   * this class; callers only need to supply the source-column resolution strategy, which varies by
   * context (outer query, CTE body, subquery).
   *
   * @param depth how many more levels of nested `SubLink` this analyzer may resolve. Only
   *   [subLinkSubqueryColumnNotNull] decrements it; the top-level statement and each CTE body start
   *   at [SUBLINK_ANALYSIS_DEPTH_BUDGET].
   * @param resolvedCtes CTE bodies declared in this analyzer's own query block. A `SubLink`
   *   subselect resolves its `:ctelevelsup 1` references against them, so passing an enclosing
   *   block's CTEs resolves those references against the wrong body.
   */
  private fun buildAnalyzer(
    hasGroupingSets: Boolean,
    forceNewNullable: Boolean,
    depth: Int,
    resolvedCtes: Map<String, List<Boolean>>,
    isSourceColumnNotNull: (varno: Int, varattno: Int) -> Boolean,
  ): NodeTreeNullabilityAnalyzer = NodeTreeNullabilityAnalyzer(
    isStrict = catalog.isStrictFunction,
    hasNonNullInitialValue = { oid -> catalog.aggregateHasNonNullInitialValue[oid] == true },
    isSourceColumnNotNull = isSourceColumnNotNull,
    isOuterJoinNullable = { nullingRelations -> nullingRelations.isNotEmpty() },
    isAlwaysNonNull = { oid -> oid in catalog.alwaysNonNullFunctionOids },
    isNeverNullForNonNullInput = { oid -> oid in catalog.neverNullForNonNullInputOids },
    isLagLeadWithDefault = { oid -> oid in catalog.lagLeadWithDefaultOids },
    isFoldableToConst = { oid -> oid in catalog.immutableFunctionOids },
    isNonNullIffFirstArgumentNonNull = { oid -> oid in catalog.nonNullIffFirstArgumentNonNullFunctionOids },
    isSubLinkSubqueryColumnNotNull = { subselectBlock ->
      subLinkSubqueryColumnNotNull(subselectBlock, resolvedCtes, depth = depth)
    },
    hasGroupingSets = hasGroupingSets,
    forceNewNullable = forceNewNullable,
  )

  /** Builds the analyzer for [scope]'s own query block. */
  private fun buildAnalyzer(scope: QueryBlockScope, depth: Int): NodeTreeNullabilityAnalyzer = buildAnalyzer(
    hasGroupingSets = scope.hasGroupingSets,
    forceNewNullable = scope.forceNewNullable,
    depth = depth,
    resolvedCtes = scope.ownCtes,
    isSourceColumnNotNull = { varno, varattno -> scope.isSourceColumnNotNull(varno, varattno) },
  )

  /**
   * Backs [NodeTreeNullabilityAnalyzer]'s `isSubLinkSubqueryColumnNotNull` callback: `true` when
   * [subselectBlock] — the raw `{QUERY ...}` text of an `ANY_SUBLINK`'s or `ALL_SUBLINK`'s
   * `:subselect` — produces exactly one non-junk output column and that column is provably non-null.
   *
   * Set-operation subselects (`UNION`/`INTERSECT`/`EXCEPT`) are rejected outright: tracing through
   * would report only the first branch's nullability, not the union across every branch.
   *
   * @param resolvedCtes CTE bodies declared directly in [subselectBlock]'s own enclosing query
   *   block, so [subselectBlock] can resolve a reference to one of them.
   * @param depth remaining recursion budget. Returns `false` (safe: nullable) once exhausted, so a
   *   `SubLink` nested inside another `SubLink`'s subselect cannot recurse indefinitely.
   */
  private fun subLinkSubqueryColumnNotNull(
    subselectBlock: String,
    resolvedCtes: Map<String, List<Boolean>>,
    depth: Int,
  ): Boolean {
    if (depth <= 0) return false
    if (nodeTreeParser.hasSetOperations(subselectBlock)) return false
    val nullability = analyzeQueryBlockNullability(subselectBlock, resolvedCtes, sql = "", depth = depth - 1)
    return nullability.size == 1 && !nullability[0]
  }

  /**
   * `true` when a `RETURNING WITH (OLD AS o, NEW AS n)` reference to `NEW` in [nodeTree] must be
   * forced nullable: a plain `DELETE` never has a `NEW` row at all; a `MERGE` might not, depending
   * on which `WHEN` clause matched a given result row.
   */
  private fun forcesNewNullable(nodeTree: String): Boolean = when (nodeTreeParser.parseCommandType(nodeTree)) {
    PgNodeTreeParser.COMMAND_TYPE_DELETE -> true
    // Only a MERGE with at least one DELETE action can leave no new row behind for some result
    // row. A MERGE with only UPDATE/INSERT actions always writes or inserts a row, so NEW is
    // exactly as trustworthy there as an ordinary column.
    PgNodeTreeParser.COMMAND_TYPE_MERGE -> nodeTreeParser.hasDeleteMergeAction(nodeTree)
    else -> false
  }

  /**
   * Analyzes every CTE declared in [nodeTree]'s own `:cteList` and returns each one's per-column
   * nullability, keyed by CTE name.
   *
   * Shared by [buildQueryBlockScope] (resolving a CTE reference in [nodeTree]'s own `:rtable`) and
   * [buildSubqueryColumnNotNull] (resolving a CTE reference — `:ctelevelsup 1` — one level down,
   * inside a nested subquery's own `:rtable`): a CTE's declaration scope is [nodeTree]'s level
   * regardless of which nesting level actually references it.
   */
  private fun resolveCteBodies(nodeTree: String, @Language("PostgreSQL") sql: String): Map<String, List<Boolean>> {
    val cteDefinitions = nodeTreeParser.parseCteList(nodeTree)
    if (cteDefinitions.isEmpty()) return emptyMap()
    val resolvedCtes = mutableMapOf<String, List<Boolean>>()
    for (cte in cteDefinitions) {
      val nullabilities = analyzeCteBodyNullability(cte, resolvedCtes, sql = sql) ?: continue
      resolvedCtes[cte.name] = nullabilities
    }
    return resolvedCtes
  }

  /**
   * @param sql Passed through unchanged so a `MERGE` nested in [cte]'s own body can be resolved
   *   via the same `EXPLAIN` call, keyed by its own target/source relation names.
   */
  private fun analyzeCteBodyNullability(
    cte: NodeTreeCteDefinition,
    previouslyResolved: Map<String, List<Boolean>>,
    @Language("PostgreSQL") sql: String,
  ): List<Boolean>? {
    if (nodeTreeParser.hasSetOperations(cte.queryBlock)) {
      return analyzeSetOperationBranches(cte.queryBlock, previouslyResolved, cte.name)
    }
    val cteRangeTable = nodeTreeParser.parseRangeTableEntries(cte.queryBlock).baseRelations()
    val mergeAbsent = mergeAbsentVarnos(cte.queryBlock, cteRangeTable, sql) ?: return null
    val analyzer = buildCteBodyAnalyzer(cte.queryBlock, previouslyResolved, sql = sql, mergeAbsentVarnos = mergeAbsent)
    // See PgNodeTreeParser.resultProjection for why :returningList is checked before :targetList.
    val projection = nodeTreeParser.resultProjection(cte.queryBlock)
    if (projection.fromReturningList) {
      return projection.entries.map { entry -> !analyzer.isNonNull(entry.expression) }
    }
    val result = analyzer.extractColumnNullability(cte.queryBlock)
    return result.ifEmpty { null }
  }

  /**
   * Analyzes a `UNION`/`UNION ALL`/`INTERSECT`/`EXCEPT` [queryBlock] branch-by-branch and combines
   * each branch's per-column nullability with OR: a column is nullable in the combined result if
   * any branch can produce `null` for it.
   *
   * A `WITH RECURSIVE` CTE's recursive term(s) reference the CTE by name ([cteName]), creating a
   * genuine fixpoint problem: the recursive term's own nullability depends on the CTE's own
   * combined nullability, which this function computes. The seed (first) branch never references
   * the CTE itself — PostgreSQL requires this — so it is computed once, outside the loop, as a
   * known starting point. Every subsequent branch is then re-analyzed, feeding back the current
   * combined result as [cteName]'s own nullability, and the combined result is recomputed —
   * repeated until a pass changes nothing.
   *
   * This converges because OR-combining more branch results can only ever add `true` (nullable)
   * bits, never remove one: a monotone-widening step over a `columnCount`-bit lattice reaches its
   * fixpoint in at most `columnCount + 1` passes, which the `while` condition below checks directly.
   *
   * @return `null` if [queryBlock] has no analyzable subquery branches at all, or if the seed
   *   branch itself could not be analyzed (an empty result). Otherwise, one nullability value per
   *   output column, in `SELECT` order.
   */
  private fun analyzeSetOperationBranches(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
    cteName: String,
  ): List<Boolean>? {
    val subqueryBranches = nodeTreeParser.parseRangeTableEntries(queryBlock).subqueryBlocks().values.toList()
    if (subqueryBranches.isEmpty()) return null

    // The seed (first) branch of a recursive CTE structurally cannot reference the CTE itself, so
    // its nullability never depends on the fixpoint loop below and is computed exactly once.
    val seedBlock = subqueryBranches.first()
    val seedAnalyzer = buildCteBodyAnalyzer(seedBlock, previouslyResolved, sql = "")
    val seedResult = seedAnalyzer.extractColumnNullability(seedBlock)
    if (seedResult.isEmpty()) return null

    val otherBranches = subqueryBranches.drop(1)
    if (otherBranches.isEmpty()) return seedResult

    // Bounded at columnCount + 1 passes — the maximum this monotone-widening loop can take to
    // reach its fixpoint. columnCount itself is seedResult.size, since every branch of a UNION/set
    // operation is required (by PostgreSQL) to have the same column count as every other branch.
    var combined = seedResult
    var previous: List<Boolean>?
    var remainingPasses = seedResult.size + 1
    var anyBranchUnanalyzable = false
    do {
      previous = combined
      val resolvedWithSelfReference = previouslyResolved + (cteName to combined)
      val branchResults = mutableListOf(seedResult)
      for (branchBlock in otherBranches) {
        val branchAnalyzer = buildCteBodyAnalyzer(branchBlock, resolvedWithSelfReference, sql = "")
        val result = branchAnalyzer.extractColumnNullability(branchBlock)
        // An empty result means this branch's own nullability could not be determined at all — not
        // "this branch has zero columns" (impossible; every branch of a set operation has the same
        // column count). Flagging it forces every column nullable below instead of silently
        // dropping it and letting the other branches' answer stand as if it contributed nothing.
        if (result.isNotEmpty()) branchResults.add(result) else anyBranchUnanalyzable = true
      }
      val columnCount = branchResults.maxOf { it.size }
      combined = (0 until columnCount).map { col -> branchResults.any { it.getOrElse(col) { true } } }
      remainingPasses--
    } while (combined != previous && remainingPasses > 0)
    // combined != previous here means the loop was cut off before reaching its fixpoint. Returning
    // that still-moving intermediate value could under-report nullability, so every column is
    // forced nullable instead, the same fallback taken for an unanalyzable branch above.
    val didNotConverge = combined != previous
    return if (anyBranchUnanalyzable || didNotConverge) List(combined.size) { true } else combined
  }

  /**
   * Resolves everything [QueryBlockScope.isSourceColumnNotNull] needs to answer a `Var` reference
   * inside [queryBlock] — the source-column-resolution chain [analyzeNodeTree],
   * [buildCteBodyAnalyzer], and [analyzeQueryBlockNullability] all build against.
   *
   * `:ctelevelsup 0` means [queryBlock] declares that CTE reference's own CTE, possibly shadowing a
   * sibling of the same name one level up, so it resolves from [enclosingCtes]'s own-scope
   * counterpart — the freshly-resolved [QueryBlockScope.ownCtes] — rather than [enclosingCtes]
   * itself; anything greater resolves from [enclosingCtes]. Without this split a local shadowing
   * `WITH` would resolve against the wrong sibling body — an unsound answer, not merely a widened
   * one.
   *
   * @param enclosingCtes CTE bodies visible via `:ctelevelsup` greater than `0` relative to
   *   [queryBlock] — declared in whichever scope encloses it, never [queryBlock]'s own nested `WITH`
   *   clause. Empty for [queryBlock]'s outermost statement, which has no enclosing scope to point
   *   past.
   * @param sql statement text that `EXPLAIN` runs to resolve a `MERGE` in [queryBlock]'s own `WITH`
   *   clause. An empty string makes that `EXPLAIN` fail, leaving the `MERGE` CTE's columns nullable.
   * @param depth The [subLinkSubqueryColumnNotNull] recursion budget threaded, not refilled,
   *   through a recursive hop into a nested query block.
   * @param mergeAbsentVarnos [queryBlock]'s own varno-to-canBeAbsent map when [queryBlock] itself is
   *   a `MERGE` — empty for every query block that cannot itself be one (a `SELECT`'s `FROM`
   *   subquery, a `SubLink`'s subselect, or a set-operation branch).
   */
  private fun buildQueryBlockScope(
    queryBlock: String,
    enclosingCtes: Map<String, List<Boolean>>,
    @Language("PostgreSQL") sql: String,
    depth: Int,
    mergeAbsentVarnos: Map<Int, Boolean> = emptyMap(),
  ): QueryBlockScope {
    val rangeTableEntries = nodeTreeParser.parseRangeTableEntries(queryBlock)
    val rangeTable = rangeTableEntries.baseRelations()
    val hasGroupingSets = nodeTreeParser.hasGroupingSets(queryBlock)
    val groupRteMap = if (hasGroupingSets) {
      emptyMap()
    } else {
      rangeTableEntries.groupRteMap(nodeTreeParser)
    }
    val ownCtes = resolveCteBodies(queryBlock, sql = sql)
    val subqueryColumnNotNull = buildSubqueryColumnNotNull(queryBlock, ownCtes, sql = sql, depth = depth)
    val cteReferences = rangeTableEntries.cteReferences()
    val resultRelationVarno = nodeTreeParser.parseResultRelation(queryBlock)
    val qualProvenVars = if (!hasGroupingSets && resultRelationVarno == 0) {
      NodeTreeNullabilityAnalyzer.qualProvenNonNullVars(queryBlock, catalog.isStrictFunction)
    } else {
      emptySet()
    }
    return QueryBlockScope(
      rangeTable = rangeTable,
      hasGroupingSets = hasGroupingSets,
      groupRteMap = groupRteMap,
      qualProvenVars = qualProvenVars,
      ownCtes = ownCtes,
      enclosingCtes = enclosingCtes,
      cteReferences = cteReferences,
      subqueryColumnNotNull = subqueryColumnNotNull,
      mergeAbsentVarnos = mergeAbsentVarnos,
      forceNewNullable = forcesNewNullable(queryBlock),
      resultRelationVarno = resultRelationVarno,
      isColumnNotNull = ::isColumnNotNull,
    )
  }

  /**
   * @param mergeAbsentVarnos [queryBlock]'s own varno-to-canBeAbsent map when [queryBlock] itself
   *   is a `MERGE` (resolved by [analyzeCteBodyNullability] before ever calling this method),
   *   empty otherwise.
   */
  private fun buildCteBodyAnalyzer(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
    @Language("PostgreSQL") sql: String,
    mergeAbsentVarnos: Map<Int, Boolean> = emptyMap(),
  ): NodeTreeNullabilityAnalyzer = buildAnalyzer(
    buildQueryBlockScope(
      queryBlock,
      previouslyResolved,
      sql = sql,
      depth = SUBLINK_ANALYSIS_DEPTH_BUDGET,
      mergeAbsentVarnos = mergeAbsentVarnos,
    ),
    depth = SUBLINK_ANALYSIS_DEPTH_BUDGET,
  )

  /**
   * Builds a map from `(varno, varattno)` to `true` for columns of subquery RTEs that are
   * guaranteed non-null.
   *
   * For each `rtekind 1` (subquery) entry in the outer query's `:rtable`, extracts the embedded
   * `:subquery {QUERY ...}` block, recursively analyzes it with the subquery's own range table,
   * and maps each output column position to its nullability. This lets the outer analyzer
   * correctly evaluate a VAR referencing a subquery derived table (`SELECT s.col FROM (...) s`),
   * which otherwise has no `relid` in the outer range table and would always resolve nullable.
   *
   * @param nodeTree the `pg_rewrite.ev_action` text of the outer query's temporary view, or a
   *   nested `{QUERY ...}` block reached via [analyzeQueryBlockNullability] (a `SubLink`'s own
   *   subselect, or a derived table nested inside one)
   * @param resolvedCtes CTE bodies declared directly in [nodeTree]'s own `:cteList`. A subquery
   *   nested inside [nodeTree] can reference one of these via `:ctelevelsup 1` inside its own
   *   `:rtable`, not [nodeTree]'s.
   * @param depth remaining nested-`SubLink` budget, given to each derived table unchanged. Refilling
   *   it here would let `= ANY` sublinks separated by derived tables exceed
   *   [SUBLINK_ANALYSIS_DEPTH_BUDGET].
   * @return A map from `(varno, varattno)` pairs to `true` when the subquery column is non-null
   */
  private fun buildSubqueryColumnNotNull(
    nodeTree: String,
    resolvedCtes: Map<String, List<Boolean>>,
    @Language("PostgreSQL") sql: String,
    depth: Int,
  ): Map<Pair<Int, Int>, Boolean> {
    // Set-operation queries (UNION ALL, INTERSECT, EXCEPT) store their branches as rtekind=1
    // subquery RTEs. Tracing through them would incorrectly report the first branch's nullability
    // as the result's nullability, so this returns empty, conservatively treating set-operation
    // output columns as nullable.
    if (nodeTreeParser.hasSetOperations(nodeTree)) return emptyMap()
    val subqueryRangeTable = nodeTreeParser.parseRangeTableEntries(nodeTree).subqueryBlocks()
    if (subqueryRangeTable.isEmpty()) return emptyMap()
    return buildMap {
      for ((outerVarno, subqueryBlock) in subqueryRangeTable) {
        val subNullabilities =
          analyzeQueryBlockNullability(subqueryBlock, resolvedCtes, sql = sql, depth = depth)
        subNullabilities.forEachIndexed { columnIndex, nullable ->
          // columnIndex is 0-based; varattno is 1-based
          put(outerVarno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  /**
   * Computes per-column nullability for a single query block ([queryBlock]): builds a
   * [QueryBlockScope] for it and runs [NodeTreeNullabilityAnalyzer.extractColumnNullability]
   * against that scope.
   *
   * @param resolvedCtes CTE bodies visible via `:ctelevelsup` greater than `0` relative to
   *   [queryBlock] — declared in whichever scope encloses it, never [queryBlock]'s own nested `WITH`
   *   clause. The caller must uphold this; resolving a `Var` against the wrong CTE body is unsound,
   *   not merely widened.
   * @param depth Passed through unchanged so a `SubLink` inside [queryBlock], and this method's own
   *   [buildSubqueryColumnNotNull] call for a derived table nested inside it, both get the
   *   already-decremented budget.
   */
  private fun analyzeQueryBlockNullability(
    queryBlock: String,
    resolvedCtes: Map<String, List<Boolean>>,
    @Language("PostgreSQL") sql: String,
    depth: Int,
  ): List<Boolean> =
    buildAnalyzer(buildQueryBlockScope(queryBlock, resolvedCtes, sql = sql, depth = depth), depth = depth)
      .extractColumnNullability(queryBlock)

  /**
   * Answers, for [analyzeNodeTree]'s own `:targetList`-to-`:returningList` substitution, whether a
   * `RETURNING` item that merely reads back a `:targetList` assignment can be trusted as what
   * `RETURNING` actually sees for [relid] — `false` whenever a row-level `BEFORE` trigger, a
   * rewrite rule, an `INSTEAD OF` trigger, or an FDW could substitute a different final value.
   *
   * @return `true` only when [relid] and every transitive inheritance/partition descendant is a
   *   plain table with no risky `relkind`, no mutating row-level trigger, and no non-view rewrite
   *   rule — `false` for every other case, including the catalog query itself failing to execute
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
   * The bare (unqualified) table name for [relid], via `pg_class.relname` — used to attribute an
   * `EXPLAIN` plan's `"Relation Name"` fields (always the real table name, never an alias) back to
   * a specific `:rtable` entry.
   *
   * @return `null` if [relid] cannot be resolved; the caller must fall back to its own safe
   *   default rather than guess.
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
