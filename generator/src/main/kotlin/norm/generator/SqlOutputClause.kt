package norm.generator

import norm.generator.libpgquery.OutputStatement
import norm.generator.libpgquery.ResultTarget
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber
import norm.generator.libpgquery.isComment
import norm.generator.libpgquery.isKeyword

/**
 * Takes the [ParsedSql.outputItems] of [parsed] and keeps them only when they line up one-to-one with the result set's
 * [columnCount] columns.
 *
 * The items cannot confirm their own count. A star spelling they do not recognize expands to several columns and
 * would shift every later item onto the wrong column.
 *
 * @return The items, or empty when parsing found none or their count differs from [columnCount].
 */
internal fun alignSelectItems(parsed: ParsedSql, columnCount: Int): List<OutputItem> {
  val items = parsed.outputItems
  return if (items.size == columnCount) items else emptyList()
}

/**
 * The output items of the statement of [ParsedSql.tree] that produces the result set, paired positionally against
 * `java.sql.ResultSetMetaData` columns.
 *
 * The statement is the first `SELECT`, `VALUES`, `TABLE`, set operation or data-modifying statement with `RETURNING`.
 * Its `WITH` clause, nested `SELECT` and keyword-like text in literals and comments are therefore not mistaken for the
 * list. A set operation yields the list of its leftmost branch.
 *
 * Handles:
 * - Simple columns: `title` gives expression `title` and [OutputItem.columnName] `title`.
 * - Qualified columns: `book.title` gives expression `book.title`, column name `title` and [OutputItem.tableName]
 *   `book`.
 * - Aliased columns: `author.name AS author_name` gives expression `author.name`, column name `name`, table name
 *   `author` and [OutputItem.alias] `author_name`.
 * - Computed expressions: `COUNT(*) AS book_count` gives expression `COUNT(*)` and no column name.
 * - Star projections: `*` gives expression `*`, no column name and [OutputItem.isStar] `true`.
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
internal val ParsedSql.outputItems: List<OutputItem>
  get() = tree?.outputStatement?.let { outputItemsOf(it) }.orEmpty()

/**
 * Reads the items of [output], which [parsed] found in its own tree, and cuts them at the first star. Empty when the
 * extent of an item cannot be confirmed.
 */
internal fun parseOutputItems(parsed: ParsedSql, output: OutputStatement): List<OutputItem> {
  val targets = output.targets
  if (targets.isEmpty()) return emptyList()
  val sql = parsed.sql
  val tokens = parsed.significantTokens
  val firstTokens = targets.map { target -> firstTokenIndex(tokens, target) ?: return emptyList() }
  val items = targets.indices.map { index ->
    val extent = if (index < targets.lastIndex) {
      extentBeforeNextItem(sql, tokens, targets[index], firstTokens[index], firstTokens[index + 1])
    } else {
      extentOfFinalItem(parsed, output, firstTokens[index])
    }
    buildItem(parsed, targets[index], firstTokens[index], extent ?: return emptyList())
  }

  val firstStarIndex = items.indexOfFirst { it.isStar }
  return if (firstStarIndex < 0 || items.size == 1) items else items.take(firstStarIndex)
}

private fun firstTokenIndex(tokens: List<ScanToken>, target: ResultTarget): Int? {
  val location = target.location ?: return null
  if (location < 0) return null
  return tokens.binarySearchBy(location) { it.start }.takeIf { it >= 0 }
}

private fun buildItem(parsed: ParsedSql, target: ResultTarget, firstToken: Int, expressionLastToken: Int): OutputItem {
  val tokens = parsed.significantTokens
  val start = tokens[firstToken].start
  val end = tokens[expressionLastToken].end
  val expression = parsed.sql.substring(start, end)
  val names = target.columnReferenceNames
  return OutputItem(
    expression = expression,
    columnName = names?.last(),
    tableName = if (names?.size == 2) names.first() else null,
    isStar = target.isStar,
    normalizedExpression = normalizedExpression(parsed, start, end),
    alias = target.alias,
    isAliasExplicit = target.alias != null && isKeyword(parsed.sql, tokens[expressionLastToken + 1], AS_KEYWORD),
  )
}

/** The text of [ParsedSql.sql] from [start] to [end] as [OutputItem.normalizedExpression] describes it. */
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
