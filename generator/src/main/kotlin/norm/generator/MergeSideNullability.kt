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
 * @param sourceRelationName the REAL table name of the `MERGE`'s source relation
 * @return `null` when `EXPLAIN` fails, its JSON cannot be parsed, or NO join node anywhere in the
 *   plan lets [targetRelationName] and [sourceRelationName] each be attributed to exactly one,
 *   DIFFERENT side (e.g. the `USING` clause has more than one relation of its own, so no single
 *   join is simply target-vs-source) — the caller must treat `null` as "cannot determine", never
 *   as "neither side is nullable". EVERY join node in the plan is checked, not just the first —
 *   a `MERGE` nested in a CTE sits inside a larger overall plan that may contain other, unrelated
 *   joins the outer statement introduces (e.g. `SELECT ... FROM the_merge_cte JOIN other_table`),
 *   and the first join found by a naive top-down search need not be the `MERGE`'s own.
 */
internal fun explainMergeSideNullability(
  connection: Connection,
  @Language("PostgreSQL") sql: String,
  targetRelationName: String,
  sourceRelationName: String,
): MergeSideNullability? {
  val explainJsonText = try {
    connection.createStatement().use { stmt ->
      stmt.executeQuery("EXPLAIN (FORMAT JSON) $sql").use { rs ->
        if (!rs.next()) return null
        rs.getString(1)
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
  for (joinNode in findAllJoinNodes(planNode)) {
    val mapping = attributeJoinToSides(joinNode, targetRelationName, sourceRelationName)
    if (mapping != null) return mapping
  }
  return null
}

private fun attributeJoinToSides(
  joinNode: JsonValue.JsonObject,
  targetRelationName: String,
  sourceRelationName: String,
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
  val sourceIsOuter = sourceRelationName in outerRelationNames
  val sourceIsInner = sourceRelationName in innerRelationNames
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

/** Every descendant of [node] (including [node] itself) carrying a `"Join Type"` field. */
private fun findAllJoinNodes(node: JsonValue.JsonObject): List<JsonValue.JsonObject> = buildList {
  if (node.fields.containsKey("Join Type")) add(node)
  (node.fields["Plans"] as? JsonValue.JsonArray)?.items?.filterIsInstance<JsonValue.JsonObject>()?.forEach {
    addAll(findAllJoinNodes(it))
  }
}

/** Every `"Relation Name"` reachable from [node], including [node] itself, at any depth. */
private fun collectRelationNames(node: JsonValue.JsonObject): Set<String> {
  val names = mutableSetOf<String>()
  fun walk(current: JsonValue.JsonObject) {
    (current.fields["Relation Name"] as? JsonValue.JsonString)?.let { names.add(it.value) }
    (current.fields["Plans"] as? JsonValue.JsonArray)?.items?.filterIsInstance<JsonValue.JsonObject>()?.forEach(::walk)
  }
  walk(node)
  return names
}
