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
 * A `MERGE`'s match-optionality is invisible to `:varnullingrels` (see
 * [PgCatalogLoader.mergeAbsentVarnos]): `WHEN NOT MATCHED BY SOURCE` and `WHEN NOT MATCHED [BY
 * TARGET] THEN INSERT` each mean one side of the underlying target/source comparison may have no
 * matching row, but PostgreSQL's `Var` nodes for either relation carry an empty nulling-relations set
 * regardless. The planner, however, executes that comparison as an ordinary join whose type encodes
 * this directly:
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
 *   the planner will choose (see [PgCatalogLoader.mergeAbsentVarnos]). At most one candidate can
 *   ever actually appear in a given plan, so offering more than one never risks attributing the
 *   wrong side.
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
    firstPlanEntry.fields["Plan"] as? JsonValue.JsonObject ?: return null
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
  val joinType = (joinNode.fields["Join Type"] as? JsonValue.JsonString)?.value ?: return null
  if (joinType != "Inner" && joinType != "Left" && joinType != "Right" && joinType != "Full") return null
  val childPlans = (joinNode.fields["Plans"] as? JsonValue.JsonArray)
    ?.items
    ?.filterIsInstance<JsonValue.JsonObject>()
    ?: return null
  if (childPlans.size != 2) return null
  val outerRelationNames = collectRelationNames(childPlans[0])
  val innerRelationNames = collectRelationNames(childPlans[1])
  val targetIsOuter = targetRelationName in outerRelationNames
  val targetIsInner = targetRelationName in innerRelationNames
  val sourceIsOuter = sourceRelationNames.any { it in outerRelationNames }
  val sourceIsInner = sourceRelationNames.any { it in innerRelationNames }
  // Each relation must appear on EXACTLY ONE side, and target/source must be on DIFFERENT sides —
  // otherwise this ISN'T the join being searched for (e.g. it's an unrelated join the outer
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
      val operation = (node.fields["Operation"] as? JsonValue.JsonString)?.value
      val relationName = (node.fields["Relation Name"] as? JsonValue.JsonString)?.value
      if (operation == "Merge" && relationName == targetRelationName) add(node)
      (node.fields["Plans"] as? JsonValue.JsonArray)?.items?.filterIsInstance<JsonValue.JsonObject>()?.forEach(::walk)
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
    (node.fields["Plans"] as? JsonValue.JsonArray)?.items?.filterIsInstance<JsonValue.JsonObject>()?.forEach { child ->
      val parentRelationship = (child.fields["Parent Relationship"] as? JsonValue.JsonString)?.value
      if (parentRelationship == "Outer" || parentRelationship == "Inner") walk(child)
    }
  }
  walk(mergeModifyTableNode)
}

/**
 * Every `"Relation Name"` (a base table or view) OR `"CTE Name"` (a `MATERIALIZED`, or otherwise
 * non-inlined, CTE's own `"CTE Scan"` node) reachable from [node], including [node] itself, at any
 * depth. Both fields are collected together because a CTE source can appear as EITHER, depending
 * on a planner decision the parsed query tree cannot predict — see [explainMergeSideNullability]'s
 * own KDoc for how its caller offers both candidate names to cover either shape.
 */
private fun collectRelationNames(node: JsonValue.JsonObject): Set<String> {
  val names = mutableSetOf<String>()
  fun walk(current: JsonValue.JsonObject) {
    (current.fields["Relation Name"] as? JsonValue.JsonString)?.let { names.add(it.value) }
    (current.fields["CTE Name"] as? JsonValue.JsonString)?.let { names.add(it.value) }
    (current.fields["Plans"] as? JsonValue.JsonArray)?.items?.filterIsInstance<JsonValue.JsonObject>()?.forEach(::walk)
  }
  walk(node)
  return names
}
