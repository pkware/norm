package norm.generator

import org.intellij.lang.annotations.Language
import java.sql.Connection
import java.sql.SQLException

/**
 * `true` when a `MERGE`'s target relation can be entirely absent (all `NULL`) for some result
 * row, and likewise for its source relation — see [explainMergeSideNullability]'s KDoc for how
 * this is determined and why the parsed query tree alone cannot answer it.
 */
internal data class MergeSideNullability(val targetCanBeAbsent: Boolean, val sourceCanBeAbsent: Boolean)

/**
 * Determines, for a `MERGE` statement, whether its target and/or source relation can be entirely
 * absent (all-`NULL`) for some result row — via `EXPLAIN (FORMAT JSON)` rather than
 * `:mergeActionList`/text inspection.
 *
 * A `MERGE`'s match-optionality is invisible to `:varnullingrels` (see [mergeAbsentVarnos]): `WHEN NOT
 * MATCHED BY SOURCE` and `WHEN NOT MATCHED [BY TARGET] THEN INSERT` each mean one side of the
 * underlying target/source comparison may have no matching row, but PostgreSQL's `Var` nodes for
 * either relation carry an empty nulling-relations set regardless. The planner, however, executes
 * that comparison as an ordinary join whose type encodes this directly:
 * - `WHEN MATCHED` only: `"Join Type": "Inner"` — both sides always present.
 * - `WHEN MATCHED` + `WHEN NOT MATCHED THEN INSERT`: `"Join Type": "Left"`, source as the preserved
 *   (outer) side, target as the side that can be entirely absent (an inserted row has no prior
 *   target row).
 * - Adding `WHEN NOT MATCHED BY SOURCE`: `"Join Type": "Full"` — either side can be absent.
 *
 * `EXPLAIN` never executes the statement it plans, regardless of `FORMAT` or statement kind (see
 * [ExplainAnalysisTest.`EXPLAIN never executes the statement it plans`]).
 *
 * @param targetRelationName the real table name (not alias — resolved by the caller via the parsed
 *   query tree's own `:rtable`/`relid`, never by re-parsing the SQL text) of the `MERGE`'s target
 *   relation
 * @param sourceRelationNames every name the `MERGE`'s source relation might be attributed under in
 *   the plan — normally a single real table name, but a CTE source offers two candidates (its own
 *   literal name, for a `MATERIALIZED` or otherwise non-inlined plan; and, when resolvable, the
 *   single base table its body inlines to), since nothing in the parsed query tree says which shape
 *   the planner will choose (see [mergeAbsentVarnos]). At most one candidate can ever actually
 *   appear in a given plan, so offering more than one never risks attributing the wrong side.
 * @return `null` when `EXPLAIN` fails, its JSON cannot be parsed, no plan node is uniquely
 *   identifiable as the `ModifyTable` implementing this `MERGE` (see [findMergeModifyTableNode]), or
 *   no join node within that node's own join tree (see [findOwnJoinNodes]) lets [targetRelationName]
 *   and exactly one name from [sourceRelationNames] each be attributed to exactly one, different
 *   side — the caller must treat `null` as "cannot determine", never as "neither side is nullable".
 */
internal fun explainMergeSideNullability(
  connection: Connection,
  @Language("PostgreSQL") sql: String,
  targetRelationName: String,
  sourceRelationNames: Set<String>,
): MergeSideNullability? {
  val explainJsonText = try {
    connection.createStatement().use { statement ->
      statement.executeQuery("EXPLAIN (FORMAT JSON) $sql").use { resultSet ->
        if (!resultSet.next()) return null
        resultSet.getString(1)
      }
    }
  } catch (_: SQLException) {
    return null
  }
  val planNode = try {
    val topLevelArray = JsonValue.parse(explainJsonText) as? JsonValue.JsonArray ?: return null
    val firstPlanEntry = topLevelArray.items.firstOrNull() as? JsonValue.JsonObject ?: return null
    firstPlanEntry.objectField("Plan") ?: return null
  } catch (_: IllegalArgumentException) {
    return null
  }
  val mergeModifyTableNode = findMergeModifyTableNode(planNode, targetRelationName) ?: return null
  for (joinNode in findOwnJoinNodes(mergeModifyTableNode)) {
    val mapping = attributeJoinToSides(joinNode, targetRelationName, sourceRelationNames)
    if (mapping != null) return mapping
  }
  return null
}

private fun attributeJoinToSides(
  joinNode: JsonValue.JsonObject,
  targetRelationName: String,
  sourceRelationNames: Set<String>,
): MergeSideNullability? {
  val joinType = joinNode.stringField("Join Type") ?: return null
  if (joinType != "Inner" && joinType != "Left" && joinType != "Right" && joinType != "Full") return null
  val childPlans = joinNode.objectArrayField("Plans")
  if (childPlans.size != 2) return null
  val outerRelationNames = collectRelationNames(childPlans[0])
  val innerRelationNames = collectRelationNames(childPlans[1])
  val targetIsOuter = targetRelationName in outerRelationNames
  val targetIsInner = targetRelationName in innerRelationNames
  val sourceIsOuter = sourceRelationNames.any { it in outerRelationNames }
  val sourceIsInner = sourceRelationNames.any { it in innerRelationNames }
  // Each relation must appear on exactly one side, and target/source must be on different sides —
  // otherwise this isn't the join being searched for (e.g. it's an unrelated join the outer
  // statement introduces, or the USING clause has more than one relation of its own) and this
  // join cannot safely be attributed to either one.
  if (targetIsOuter == targetIsInner || sourceIsOuter == sourceIsInner || targetIsOuter == sourceIsOuter) {
    return null
  }
  val outerCanBeAbsent = joinType == "Right" || joinType == "Full"
  val innerCanBeAbsent = joinType == "Left" || joinType == "Full"
  return MergeSideNullability(
    targetCanBeAbsent = if (targetIsOuter) outerCanBeAbsent else innerCanBeAbsent,
    sourceCanBeAbsent = if (sourceIsOuter) outerCanBeAbsent else innerCanBeAbsent,
  )
}

/**
 * The plan node that is the `MERGE` targeting [targetRelationName]: a `"ModifyTable"` node whose
 * `"Operation"` is `"Merge"` and whose own `"Relation Name"` equals [targetRelationName]. Searches
 * the whole plan at any depth, so a `MERGE` nested inside a CTE is found the same way a top-level one
 * is; a `MERGE` elsewhere in the same plan targeting a different table is excluded by the
 * relation-name match.
 *
 * @return `null` when no such node exists, or when more than one does (e.g. two `MERGE`s in the same
 *   plan targeting the same table) — either way there is no single node to treat as the merge being
 *   searched for.
 */
private fun findMergeModifyTableNode(
  planRoot: JsonValue.JsonObject,
  targetRelationName: String,
): JsonValue.JsonObject? {
  val matches = buildList {
    fun walk(node: JsonValue.JsonObject) {
      val operation = node.stringField("Operation")
      val relationName = node.stringField("Relation Name")
      if (operation == "Merge" && relationName == targetRelationName) add(node)
      node.objectArrayField("Plans").forEach(::walk)
    }
    walk(planRoot)
  }
  return matches.singleOrNull()
}

/**
 * Every join node reachable from [mergeModifyTableNode] while descending only through children whose
 * `"Parent Relationship"` is `"Outer"` or `"Inner"` — i.e. the `MERGE`'s own join tree, never an
 * `"InitPlan"` or `"SubPlan"` sibling. An uncorrelated `EXISTS`/`IN` inside a `WHEN` condition plans
 * as an `"InitPlan"`; a correlated one plans as a `"SubPlan"` — both are independent subplans
 * PostgreSQL attaches alongside the `MERGE`'s real join, not part of it, and either can itself
 * contain a join over the exact same target/source relation names, which is why
 * [explainMergeSideNullability] must not simply take the first join found anywhere under the
 * `ModifyTable` node.
 */
private fun findOwnJoinNodes(mergeModifyTableNode: JsonValue.JsonObject): List<JsonValue.JsonObject> = buildList {
  fun walk(node: JsonValue.JsonObject) {
    if (node.fields.containsKey("Join Type")) add(node)
    node.objectArrayField("Plans").forEach { child ->
      val parentRelationship = child.stringField("Parent Relationship")
      if (parentRelationship == "Outer" || parentRelationship == "Inner") walk(child)
    }
  }
  walk(mergeModifyTableNode)
}

/**
 * Every `"Relation Name"` (a base table or view) or `"CTE Name"` (a `MATERIALIZED`, or otherwise
 * non-inlined, CTE's own `"CTE Scan"` node) reachable from [node], including [node] itself, at any
 * depth. Both fields are collected together because a CTE source can appear as either, depending
 * on a planner decision the parsed query tree cannot predict — see [explainMergeSideNullability]'s
 * own KDoc for how its caller offers both candidate names to cover either shape.
 */
private fun collectRelationNames(node: JsonValue.JsonObject): Set<String> {
  val names = mutableSetOf<String>()
  fun walk(current: JsonValue.JsonObject) {
    current.stringField("Relation Name")?.let { names.add(it) }
    current.stringField("CTE Name")?.let { names.add(it) }
    current.objectArrayField("Plans").forEach(::walk)
  }
  walk(node)
  return names
}

/**
 * The outcome of [mergeAbsentVarnos] resolving which side(s) of a `MERGE` statement can be
 * entirely absent for some result row.
 */
internal sealed interface MergeAbsence {

  /**
   * No relation needs to be treated as absent: the analyzed statement is not a `MERGE`, or it is a
   * `MERGE` whose `RETURNING` list reads only the target relation's own columns or `OLD`/`NEW`
   * references.
   */
  object NotApplicable : MergeAbsence

  /**
   * The analyzed statement is a `MERGE`, and `EXPLAIN` attributed its join to the target and
   * source relations.
   *
   * @property byVarno a map from `:rtable` varno to whether that relation can be entirely absent —
   *   containing exactly the target and source varno, per [MergeSideNullability]
   */
  data class Resolved(val byVarno: Map<Int, Boolean>) : MergeAbsence

  /**
   * The analyzed statement is a `MERGE`, but which side(s) can be entirely absent could not be
   * resolved (e.g. a `USING` clause with more than one relation of its own, or `EXPLAIN` could not
   * attribute the join) — the caller must treat this `MERGE` as entirely untrustworthy, never
   * guessing at a partial answer.
   */
  object Unresolvable : MergeAbsence
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
 * @return [MergeAbsence.NotApplicable], [MergeAbsence.Resolved], or [MergeAbsence.Unresolvable] —
 *   see each variant's own KDoc for exactly when it applies
 */
internal fun mergeAbsentVarnos(
  connection: Connection,
  nodeTreeParser: PgNodeTreeParser,
  nodeTree: String,
  rangeTable: Map<Int, Int>,
  @Language("PostgreSQL") sql: String,
): MergeAbsence {
  if (nodeTreeParser.parseCommandType(nodeTree) != PgNodeTreeParser.COMMAND_TYPE_MERGE) {
    return MergeAbsence.NotApplicable
  }
  val targetVarno = nodeTreeParser.parseResultRelation(nodeTree)
  // A RETURNING list that only reads the target relation's own columns, or OLD/NEW references,
  // never needs EXPLAIN's resolution at all. Skipping it here matters beyond saving an EXPLAIN
  // round trip: a MERGE whose USING source is not a plain base table or CTE (e.g. a VALUES list
  // or a subquery) can never be resolved below, but that must not block a RETURNING list that
  // never depended on knowing which side of that join is nullable.
  val returningEntries = nodeTreeParser.parseReturningList(nodeTree)
  if (returningEntries.none { NodeTreeNullabilityAnalyzer.containsVarOutsideRelation(it.expression, targetVarno) }) {
    return MergeAbsence.NotApplicable
  }
  val targetRelid = rangeTable[targetVarno] ?: return MergeAbsence.Unresolvable
  // A simple `MERGE INTO target USING source ON ...` has exactly one other :rtable entry besides
  // the target — the source, of any rtekind. A `USING` clause with more than one relation of its
  // own (e.g. a join or subquery source) has no single relation this method can attribute a join
  // side to, so it bails rather than guess. Reads the FULL range table, not [rangeTable] (base
  // tables only), since a CTE source's own varno never appears there at all.
  val sourceEntries = nodeTreeParser.parseRangeTableEntries(nodeTree).filterKeys { it != targetVarno }
  if (sourceEntries.size != 1) return MergeAbsence.Unresolvable
  val (sourceVarno, sourceEntry) = sourceEntries.entries.single()
  val targetName = resolveTableName(connection, targetRelid) ?: return MergeAbsence.Unresolvable
  val sourceNames = mergeSourceRelationNameCandidates(connection, nodeTreeParser, nodeTree, sourceEntry)
    ?: return MergeAbsence.Unresolvable
  val mapping = explainMergeSideNullability(connection, sql, targetName, sourceNames)
    ?: return MergeAbsence.Unresolvable
  return MergeAbsence.Resolved(
    buildMap {
      put(targetVarno, mapping.targetCanBeAbsent)
      put(sourceVarno, mapping.sourceCanBeAbsent)
    },
  )
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
private fun mergeSourceRelationNameCandidates(
  connection: Connection,
  nodeTreeParser: PgNodeTreeParser,
  nodeTree: String,
  sourceEntry: RangeTableEntry,
): Set<String>? = when (sourceEntry) {
  is RangeTableEntry.Relation -> resolveTableName(connection, sourceEntry.relid)?.let(::setOf)
  is RangeTableEntry.Cte -> buildSet {
    add(sourceEntry.reference.name)
    resolveInlinedBaseRelationName(connection, nodeTreeParser, nodeTree, sourceEntry.reference.name)?.let(::add)
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
private fun resolveInlinedBaseRelationName(
  connection: Connection,
  nodeTreeParser: PgNodeTreeParser,
  nodeTree: String,
  cteName: String,
): String? {
  val cteDefinition = nodeTreeParser.parseCteList(nodeTree).find { it.name == cteName } ?: return null
  val onlyEntry = nodeTreeParser.parseRangeTableEntries(cteDefinition.queryBlock).values.singleOrNull()
  return (onlyEntry as? RangeTableEntry.Relation)?.let { resolveTableName(connection, it.relid) }
}

/**
 * The bare (unqualified) table name for [relid], via `pg_class.relname` — used to attribute an
 * `EXPLAIN` plan's `"Relation Name"` fields (always the real table name, never an alias) back to
 * a specific `:rtable` entry.
 *
 * @return `null` if [relid] cannot be resolved; the caller must fall back to its own safe
 *   default rather than guess.
 */
private fun resolveTableName(connection: Connection, relid: Int): String? = try {
  connection.prepareStatement("SELECT relname FROM pg_catalog.pg_class WHERE oid = ?").use { preparedStatement ->
    preparedStatement.setInt(1, relid)
    preparedStatement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getString(1) else null }
  }
} catch (_: SQLException) {
  null
}
