package norm.generator

/**
 * Sets [Column.computedExpression] on each of [columns], the result columns of the query [sql].
 *
 * A column gets an expression when it has no source table and its select item is neither a column
 * reference nor a `*` item. A top-level set operation leaves every column's expression `null`, since
 * [parseSelectItems] sees only the first branch. So does a select list that [alignSelectItems]
 * cannot align with [columns].
 *
 * @param sql the query text the [columns] were analyzed from.
 * @param columns the query's result columns, in result-set order.
 * @return [columns] with [Column.computedExpression] populated where the rules above allow.
 */
internal fun resolveComputedExpressions(sql: String, columns: List<Column>): List<Column> {
  val selectItems = alignSelectItems(sql, columns.size)
  val hasSetOperation = hasTopLevelSetOperation(sql)
  return columns.mapIndexed { index, column ->
    val selectItem = selectItems.getOrNull(index)
    val isComputed = column.table == null &&
      selectItem != null &&
      selectItem.columnName == null &&
      !hasSetOperation &&
      !isStarItem(selectItem.expression)
    val expression = if (isComputed) collapseCosmeticWhitespace(stripComments(selectItem.expression)) else null
    column.copy(computedExpression = expression)
  }
}
