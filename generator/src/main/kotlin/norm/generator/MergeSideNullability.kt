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
 * A `MERGE`'s match-optionality is invisible to `:varnullingrels` (verified live — see
 * [PgCatalogLoader.mergeAbsentVarnos]'s KDoc): `WHEN NOT MATCHED BY SOURCE` and `WHEN NOT
 * MATCHED [BY TARGET] THEN INSERT` each mean one side of the `MERGE`'s underlying target/source
 * comparison may have no matching row, but PostgreSQL's `Var` nodes for either relation carry an
 * EMPTY nulling-relations set regardless. The planner, however, MUST decide how to execute that
 * comparison, and it always does so as an ordinary join whose TYPE exactly encodes this — verified
 * live on PostgreSQL 17 for all three shapes below, confirming a table-side inspection of the
 * `WHEN` clauses is unnecessary once the plan itself is asked:
 * - `WHEN MATCHED` only: `"Join Type": "Inner"` — both sides always present.
 * - `WHEN MATCHED` + `WHEN NOT MATCHED THEN INSERT`: `"Join Type": "Left"`, with the source
 *   relation as the preserved (outer) side and the target relation as the side that can be
 *   entirely absent (the row being inserted has no prior target row).
 * - Adding `WHEN NOT MATCHED BY SOURCE`: `"Join Type": "Full"` — either side can be absent.
 *
 * `EXPLAIN` (with or without `FORMAT JSON`, and regardless of the statement being a `MERGE`,
 * `INSERT`, `UPDATE`, or `DELETE`) NEVER executes the statement — verified live and pinned by
 * [ExplainAnalysisTest.`EXPLAIN never executes the statement it plans`].
 *
 * @param targetRelationName the REAL table name (not alias — resolved by the caller via the
 *   parsed query tree's own `:rtable`/`relid`, e.g. through [PgCatalogLoader]'s catalog lookups,
 *   never by re-parsing the SQL text) of the `MERGE`'s target relation
 * @param sourceRelationNames every name the `MERGE`'s source relation might be attributed under in
 *   the plan — normally a single REAL table name, but a CTE source offers TWO candidates (its own
 *   literal name, for a `MATERIALIZED` or otherwise non-inlined plan; and, when resolvable, the
 *   single base table its body inlines to) since nothing in the parsed query tree says which shape
 *   the planner will choose — see [PgCatalogLoader.mergeAbsentVarnos]'s own KDoc for how the
 *   caller builds this set. At most ONE candidate can ever actually appear in a given plan, so
 *   offering more than one never risks attributing the wrong side.
 * @return `null` when `EXPLAIN` fails, its JSON cannot be parsed, no plan node is UNIQUELY
 *   identifiable as the `ModifyTable` implementing this `MERGE` (see
 *   [findMergeModifyTableNode]'s KDoc), or NO join node within that node's own join tree (see
 *   [findOwnJoinNodes]'s KDoc) lets [targetRelationName] and exactly one name from
 *   [sourceRelationNames] each be attributed to exactly one, DIFFERENT side (e.g. the `USING`
 *   clause has more than one relation of its own, so no single join is simply target-vs-source) —
 *   the caller must treat `null` as "cannot determine", never as "neither side is nullable".
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
 * The plan node that IS the `MERGE` targeting [targetRelationName]: a `"ModifyTable"` node whose
 * `"Operation"` is `"Merge"` and whose OWN `"Relation Name"` equals [targetRelationName] —
 * PostgreSQL sets a `MERGE`'s `ModifyTable` node's `"Relation Name"` to its target table's real
 * name, verified live on PostgreSQL 16, 17, and 18. Searches the WHOLE plan at any depth, not just
 * the top level, so a `MERGE` nested inside a CTE is found the same way a top-level one is; a
 * `MERGE` elsewhere in the SAME plan targeting a DIFFERENT table (e.g. `WITH x AS (MERGE INTO
 * other ...) MERGE INTO target ...`) is excluded by the relation-name match, never confused with
 * this one.
 *
 * @return `null` when no such node exists, or when more than one does (e.g. two `MERGE`s in the
 *   same plan targeting the SAME table) — either way there is no single node this method can
 *   safely treat as THE merge being searched for.
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
 * Every join node reachable from [mergeModifyTableNode] while descending ONLY through children
 * whose `"Parent Relationship"` is `"Outer"` or `"Inner"` — i.e. the `MERGE`'s own join tree,
 * never an `"InitPlan"` or `"SubPlan"` sibling. An uncorrelated `EXISTS`/`IN` inside a `WHEN`
 * condition plans as an `"InitPlan"`; a correlated one plans as a `"SubPlan"` — both are
 * independent subplans PostgreSQL attaches alongside the `MERGE`'s real join, not part of it, and
 * EITHER can itself contain a join over the exact same target/source relation names (verified
 * live), which is why [explainMergeSideNullability] must not simply take the first join found
 * anywhere under the `ModifyTable` node.
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
