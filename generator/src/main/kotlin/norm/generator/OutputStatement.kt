package norm.generator

import norm.generator.libpgquery.ParseTreeNames

/**
 * The statement of a parsed text whose result set a query returns.
 *
 * @property targets the `ResTarget` nodes of its select list or `RETURNING` list, in source order. Empty when the
 *   statement has no such list in the source text, as with `VALUES`, `TABLE` and `EXPLAIN`.
 * @property isSetOperation whether the statement is a `UNION`, `INTERSECT` or `EXCEPT`. [targets] then belong to the
 *   leftmost branch.
 */
internal class OutputStatement(val targets: List<JsonValue.JsonObject>, val isSetOperation: Boolean)

private val DATA_MODIFYING_STATEMENTS = listOf(
  ParseTreeNames.INSERT_STATEMENT,
  ParseTreeNames.UPDATE_STATEMENT,
  ParseTreeNames.DELETE_STATEMENT,
  ParseTreeNames.MERGE_STATEMENT,
)

/**
 * Finds the first statement of [tree] that produces the query's result set.
 *
 * @return the statement, or `null` when [tree] has none. See [outputStatementOf] for which statements produce a result
 *   set.
 */
internal fun findOutputStatement(tree: JsonValue.JsonObject): OutputStatement? =
  findOutputStatementNode(tree)?.let(::outputStatementOf)

/**
 * Finds the node of the first statement of [tree] that produces the query's result set.
 *
 * @return the statement node, in the shape [outputStatementOf] reads, or `null` when [tree] has none.
 */
internal fun findOutputStatementNode(tree: JsonValue.JsonObject): JsonValue.JsonObject? =
  tree.objectArrayField(ParseTreeNames.STATEMENTS)
    .mapNotNull { it.objectField(ParseTreeNames.STATEMENT) }
    .firstOrNull { outputStatementOf(it) != null }

/**
 * Reads the result set of [statementNode].
 *
 * A `SELECT`, `VALUES`, `TABLE` or set operation produces one, as does a data-modifying statement with a `RETURNING`
 * list. An `EXPLAIN` has no targets, since its result set is the `QUERY PLAN` column. A data-modifying statement
 * without `RETURNING`, and every other kind of statement, produces none.
 *
 * @param statementNode an object with one field named for the statement kind, such as the `stmt` of a statement or a
 *   [CteDefinition.ctequery].
 * @return the statement, or `null` when it produces no result set.
 */
internal fun outputStatementOf(statementNode: JsonValue.JsonObject): OutputStatement? {
  statementNode.objectField(ParseTreeNames.SELECT_STATEMENT)?.let { return selectOutput(it) }
  if (statementNode.objectField(ParseTreeNames.EXPLAIN_STATEMENT) != null) return OutputStatement(emptyList(), false)
  for (kind in DATA_MODIFYING_STATEMENTS) {
    val returningItems = statementNode.objectField(kind)
      ?.objectField(ParseTreeNames.RETURNING_CLAUSE)
      ?.objectArrayField(ParseTreeNames.RETURNING_ITEMS)
      .orEmpty()
    if (returningItems.isNotEmpty()) return OutputStatement(resultTargets(returningItems), false)
  }
  return null
}

private fun selectOutput(select: JsonValue.JsonObject): OutputStatement {
  val isSetOperation = isSetOperation(select)
  val leaf = leftmostLeaf(select)
  val hasTargetList = leaf != null && !leaf.fields.containsKey(ParseTreeNames.VALUES_LISTS)
  val targets = if (hasTargetList) resultTargets(leaf.objectArrayField(ParseTreeNames.TARGET_LIST)) else emptyList()
  return OutputStatement(targets, isSetOperation)
}

internal fun leftmostLeaf(select: JsonValue.JsonObject): JsonValue.JsonObject? {
  var current = select
  while (isSetOperation(current)) current = current.objectField(ParseTreeNames.LEFT_BRANCH) ?: return null
  return current
}

private fun isSetOperation(select: JsonValue.JsonObject): Boolean {
  val operation = select.stringField(ParseTreeNames.SET_OPERATION)
  return operation != null && operation != ParseTreeNames.SET_OPERATION_NONE
}

private fun resultTargets(items: List<JsonValue.JsonObject>): List<JsonValue.JsonObject> =
  items.mapNotNull { it.objectField(ParseTreeNames.RESULT_TARGET) }
