package norm.generator

/** An [OutputItem] with no alias. */
internal fun outputItem(
  expression: String,
  columnName: String?,
  tableName: String?,
  isStar: Boolean,
  normalizedExpression: String,
): OutputItem =
  OutputItem(expression, columnName, tableName, isStar, normalizedExpression, alias = null, isAliasExplicit = false)

/** This item with its alias removed, for comparing the parts of an item that do not depend on the alias. */
internal fun OutputItem.withoutAlias(): OutputItem = copy(alias = null, isAliasExplicit = false)
