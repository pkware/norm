package norm.generator

import norm.generator.libpgquery.OutputStatement
import norm.generator.libpgquery.ParseResult
import norm.generator.libpgquery.ResultTarget
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber
import norm.generator.libpgquery.isComment
import norm.generator.libpgquery.isKeyword

internal const val AS_KEYWORD = "AS"

/**
 * A parsed item from a SQL SELECT or RETURNING list.
 *
 * @property expression The item's source text from its first token to the end of its expression, without a trailing
 *   `AS` or alias (e.g. `COUNT(*)`, `author.name`, `book.title`). Comments between its tokens are kept.
 * @property columnName The name of a simple column reference as PostgreSQL's parser stores it, or `null` for a computed
 *   expression. An unquoted name is folded to lowercase. A quoted name has its quotes removed and each doubled `""`
 *   collapsed to `"`. On PostgreSQL 18, `ResultSetMetaData.getColumnName` for `SELECT "He""llo" FROM (SELECT 1 AS
 *   "He""llo") s` reports `He"llo`, which this property equals.
 * @property tableName The table qualifier of a qualified reference (e.g. `author` in `author.name`, or `My Table` in
 *   `"My Table".name`), under the same convention as [columnName]. `null` for an unqualified reference or a computed
 *   expression.
 * @property isStar Whether the item is `*`, `table.*`, or a composite expansion such as `(f(x)).*`. A star expands to
 *   as many columns as the expanded relation or composite has. The text does not give that number.
 * @property normalizedExpression [expression] rebuilt from its non-comment tokens, for embedding in generated
 *   documentation. Tokens that touch in the source stay joined. Every other gap, whether whitespace or a comment,
 *   becomes one space, except after `(` and before `)`. The same holds between the quoted segments of one string
 *   literal, which PostgreSQL reads as a single token (see [joinStringContinuation]).
 */
internal data class SelectItem(
  val expression: String,
  val columnName: String?,
  val tableName: String?,
  val isStar: Boolean,
  val normalizedExpression: String,
)

/**
 * Takes the [SelectItem] of each [ParsedSql.outputItems] entry of [parsed] and keeps them only when they line up
 * one-to-one with the result set's [columnCount] columns.
 *
 * The items cannot confirm their own count. A star spelling they do not recognize expands to several columns and
 * would shift every later item onto the wrong column.
 *
 * @return The items, or empty when parsing found none or their count differs from [columnCount].
 */
internal fun alignSelectItems(parsed: ParsedSql, columnCount: Int): List<SelectItem> {
  val items = parsed.outputItems.map { it.selectItem }
  return if (items.size == columnCount) items else emptyList()
}

/**
 * Whether the statement of [parsed] that produces the result set is a `UNION`, `INTERSECT` or `EXCEPT`.
 *
 * [ParsedSql.outputItems] then holds only the items of the leftmost branch. The other branches may compute the same
 * result column differently, although the column takes its name from the first branch.
 */
internal fun hasTopLevelSetOperation(parsed: ParsedSql): Boolean = parsed.outputStatement?.isSetOperation ?: false

/**
 * A single item of [ParsedSql.outputItems], pairing the [selectItem] with the alias that follows its
 * expression, if any.
 *
 * [alias] and [SelectItem.columnName] are independent. `description AS description_upper` has both,
 * `UPPER(description) AS description_upper` has only an alias, and `description` has only a column name.
 *
 * @property selectItem The parsed expression/columnName/tableName, alias already removed.
 * @property alias The alias as PostgreSQL's parser stores it in `ResTarget.name`, or `null` if the item has no alias.
 *   An unquoted alias is folded to lowercase. A quoted alias has its quotes removed and each doubled `""` collapsed to
 *   `"`. A Unicode-escaped alias, `U&"d!0061t" UESCAPE '!'`, has its escapes resolved.
 * @property isAliasExplicit Whether [alias] follows an unquoted `AS`. `false` for an implicit alias (`UPPER(a) y`) and
 *   when [alias] is `null`.
 */
internal data class OutputItemWithAlias(val selectItem: SelectItem, val alias: String?, val isAliasExplicit: Boolean)

/**
 * The output items of the statement of [ParsedSql.tree] that produces the result set, paired positionally against
 * `java.sql.ResultSetMetaData` columns.
 *
 * The statement is the first `SELECT`, `VALUES`, `TABLE`, set operation or data-modifying statement with `RETURNING`.
 * Its `WITH` clause, nested `SELECT` and keyword-like text in literals and comments are therefore not mistaken for the
 * list. A set operation yields the list of its leftmost branch.
 *
 * Handles:
 * - Simple columns: `title` gives expression `title` and [SelectItem.columnName] `title`.
 * - Qualified columns: `book.title` gives expression `book.title`, column name `title` and [SelectItem.tableName]
 *   `book`.
 * - Aliased columns: `author.name AS author_name` gives expression `author.name`, column name `name`, table name
 *   `author` and [OutputItemWithAlias.alias] `author_name`.
 * - Computed expressions: `COUNT(*) AS book_count` gives expression `COUNT(*)` and no column name.
 * - Star projections: `*` gives expression `*`, no column name and [SelectItem.isStar] `true`.
 *
 * A star item expands to however many columns the starred relation has, shifting every later item onto the wrong
 * metadata column. An item before a star is unaffected by that unknown width, so only the first star and everything
 * after it is dropped. A lone star is left alone, and items at or after a star fall back to metadata unresolved.
 *
 * Each item starts at its parse tree node's location. An item ends at the token before the comma that precedes the
 * next item. The last item's expression ends at the first token end where replacing the expression with a constant
 * leaves the rest of the parse tree unchanged. The alias that the tree records for it is the next token, or the one
 * after `AS`.
 *
 * Empty when the text has no output list, as with `VALUES`, `TABLE` and `EXPLAIN`, when PostgreSQL's grammar rejects
 * it, or when the end of the last item's expression cannot be confirmed. Both consumers degrade safely for a missing
 * item, falling back to `ResultSetMetaData.getColumnName`, so no wrong original name is reported.
 */
internal val ParsedSql.outputItems: List<OutputItemWithAlias>
  get() = outputStatement?.let { outputItemsOf(it.node) }.orEmpty()

/** Reads the items of [output], which [parsed] found in its own tree, and cuts them at the first star. */
internal fun parseOutputItems(parsed: ParsedSql, output: OutputStatement): List<OutputItemWithAlias> {
  val tree = parsed.tree ?: return emptyList()
  if (output.targets.isEmpty()) return emptyList()
  val items = outputItems(parsed, tree, output) ?: return emptyList()

  val firstStarIndex = items.indexOfFirst { it.selectItem.isStar }
  return if (firstStarIndex < 0 || items.size == 1) items else items.take(firstStarIndex)
}

private fun outputItems(parsed: ParsedSql, tree: ParseResult, statement: OutputStatement): List<OutputItemWithAlias>? {
  val sql = parsed.sql
  val tokens = parsed.significantTokens
  val targets = statement.targets
  val firstTokens = targets.map { target -> firstTokenIndex(tokens, target) ?: return null }
  return targets.indices.map { index ->
    val extent = if (index < targets.lastIndex) {
      extentBeforeNextItem(sql, tokens, targets[index], firstTokens[index], firstTokens[index + 1])
    } else {
      extentOfFinalItem(sql, parsed.parserForm, tokens, tree, statement, firstTokens[index])
    }
    buildItem(parsed, targets[index], extent ?: return null)
  }
}

/**
 * The tokens of one item.
 *
 * @property firstToken the first token of the expression.
 * @property expressionLastToken the last token of the expression.
 * @property isAliasExplicit whether the token before the alias is `AS`.
 */
internal class ItemExtent(val firstToken: Int, val expressionLastToken: Int, val isAliasExplicit: Boolean)

private fun firstTokenIndex(tokens: List<ScanToken>, target: ResultTarget): Int? {
  val location = target.location ?: return null
  if (location < 0) return null
  return tokens.binarySearchBy(location) { it.start }.takeIf { it >= 0 }
}

/** An item that is not the last ends at the token before the comma, so its alias is the token at its end. */
private fun extentBeforeNextItem(
  sql: String,
  tokens: List<ScanToken>,
  target: ResultTarget,
  firstToken: Int,
  nextFirstToken: Int,
): ItemExtent? {
  val commaIndex = nextFirstToken - 1
  if (commaIndex - 1 < firstToken || tokens[commaIndex].token != TokenNumber.COMMA) return null
  val lastToken = commaIndex - 1
  if (target.alias == null) return ItemExtent(firstToken, lastToken, false)

  val aliasFirstToken = lastToken
  val isAliasExplicit = aliasFirstToken - 1 > firstToken && isKeyword(sql, tokens[aliasFirstToken - 1], AS_KEYWORD)
  val expressionLastToken = if (isAliasExplicit) aliasFirstToken - 2 else aliasFirstToken - 1
  if (expressionLastToken < firstToken) return null
  return ItemExtent(firstToken, expressionLastToken, isAliasExplicit)
}

private fun buildItem(parsed: ParsedSql, target: ResultTarget, extent: ItemExtent): OutputItemWithAlias {
  val tokens = parsed.significantTokens
  val start = tokens[extent.firstToken].start
  val end = tokens[extent.expressionLastToken].end
  val expression = parsed.sql.substring(start, end)
  val selectItem = selectItem(expression, target, normalizedExpression(parsed, start, end))
  return OutputItemWithAlias(selectItem, target.alias, extent.isAliasExplicit)
}

/** The text of [ParsedSql.sql] from [start] to [end] as [SelectItem.normalizedExpression] describes it. */
private fun normalizedExpression(parsed: ParsedSql, start: Int, end: Int): String {
  val tokens = parsed.tokens.filter { it.start >= start && it.end <= end && !it.isComment() }
  return buildString {
    for ((index, token) in tokens.withIndex()) {
      val previous = tokens.getOrNull(index - 1)
      val hasGap = previous != null && token.start != previous.end
      val isHugging = previous?.token == TokenNumber.OPEN_PARENTHESIS || token.token == TokenNumber.CLOSE_PARENTHESIS
      if (hasGap && !isHugging) append(' ')
      append(joinStringContinuation(parsed.sql.substring(token.start, token.end)))
    }
  }
}

private fun selectItem(expression: String, target: ResultTarget, normalizedExpression: String): SelectItem {
  val names = target.columnReferenceNames
  return SelectItem(
    expression = expression,
    columnName = names?.last(),
    tableName = if (names?.size == 2) names.first() else null,
    isStar = target.isStar,
    normalizedExpression = normalizedExpression,
  )
}
