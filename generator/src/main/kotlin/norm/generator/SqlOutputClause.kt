package norm.generator

import norm.generator.libpgquery.ParseTreeNames
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber
import norm.generator.libpgquery.isComment
import norm.generator.libpgquery.isKeyword
import norm.generator.libpgquery.locationFieldNames

private const val AS_KEYWORD = "AS"
private const val NO_LOCATION = -1

/** The literal that stands in for an item's expression while the end of the expression is searched. */
private const val PROBE_CONSTANT = "1"

/**
 * A parsed item from a SQL SELECT or RETURNING list.
 *
 * @property expression The item's source text from its first token to the end of its expression, without a trailing
 *   `AS` or alias (e.g. `COUNT(*)`, `author.name`, `book.title`). Comments between its tokens are kept.
 * @property columnName The column's logical name for a simple column reference — `null` for a
 *   computed expression. For a quoted reference (`"My Col"`, `"He""llo"`), this is the identifier
 *   PostgreSQL itself resolves to: surrounding quotes removed and any doubled `""` escape
 *   collapsed to the single literal `"` it represents. On PostgreSQL 18,
 *   `ResultSetMetaData.getColumnName` for `SELECT "He""llo" FROM (SELECT 1 AS "He""llo") s`
 *   reports `He"llo` — no quotes, escape already collapsed — which this property must agree with.
 *   Never the raw, quote-decorated source text; see [isColumnNameQuoted] for how the
 *   quoted/unquoted distinction is preserved instead.
 * @property tableName The table qualifier's logical name for a qualified reference (e.g. `author`
 *   in `author.name`, or `My Table` in `"My Table".name`) — same logical-value convention as
 *   [columnName], and `null` for an unqualified reference or a computed expression.
 * @property isColumnNameQuoted Whether [columnName] came from a quoted source identifier — `false`
 *   when [columnName] is `null`. [columnName] carries no quotes that would otherwise
 *   signal whether PostgreSQL folds it (quoted: case preserved) or not (unquoted: folded to
 *   lowercase), so a caller doing that folding must consult this flag.
 * @property isTableNameQuoted Whether [tableName] came from a quoted source identifier — same
 *   convention as [isColumnNameQuoted], `false` when [tableName] is `null`.
 * @property isStar Whether the item is `*`, `table.*`, or a composite expansion such as `(f(x)).*`. A star expands to
 *   as many columns as the expanded relation or composite has. The text does not give that number.
 */
internal data class SelectItem(
  val expression: String,
  val columnName: String?,
  val tableName: String?,
  val isColumnNameQuoted: Boolean = false,
  val isTableNameQuoted: Boolean = false,
  val isStar: Boolean = false,
) {

  /**
   * [expression] rebuilt from its non-comment tokens, for embedding in generated documentation.
   *
   * Tokens that touch in the source stay joined. Every other gap, whether whitespace or a comment, becomes one space,
   * except after `(` and before `)`. The same holds between the quoted segments of one string literal, which
   * PostgreSQL reads as a single token (see [joinStringContinuation]).
   */
  val normalizedExpression: String
    get() {
      val tokens = scanLeadingTokens(expression).filterNot(ScanToken::isComment)
      return buildString {
        for ((index, token) in tokens.withIndex()) {
          val previous = tokens.getOrNull(index - 1)
          val hasGap = previous != null && token.start != previous.end
          val isHugging = previous?.token == TokenNumber.OPEN_PARENTHESIS ||
            token.token == TokenNumber.CLOSE_PARENTHESIS
          if (hasGap && !isHugging) append(' ')
          append(joinStringContinuation(expression.substring(token.start, token.end)))
        }
      }
    }
}

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
internal fun hasTopLevelSetOperation(parsed: ParsedSql): Boolean =
  parsed.tree?.let(::findOutputStatement)?.isSetOperation ?: false

/**
 * A single item of [ParsedSql.outputItems], pairing the [selectItem] with the alias that follows its
 * expression, if any.
 *
 * Kept separate from [SelectItem] because [SelectItem.columnName] already carries a meaning for
 * simple column references — reusing it for an alias would conflate "this item is a bare column
 * reference" with "this item has an alias", which are different facts a computed expression can
 * have independently (`UPPER(description) AS description_upper` has an alias but is not a bare
 * column reference; `description AS description_upper` is a bare column reference and has an
 * alias).
 *
 * @property selectItem The parsed expression/columnName/tableName, alias already removed.
 * @property alias The alias exactly as written, quotes included, or `null` if the item has no alias. A Unicode-escaped
 *   alias keeps its `UESCAPE` clause: `U&"d!0061t" UESCAPE '!'`.
 * @property isAliasExplicit Whether [alias] follows an unquoted `AS`. `false` for an implicit alias (`UPPER(a) y`) and
 *   when [alias] is `null`.
 */
internal data class OutputItemWithAlias(val selectItem: SelectItem, val alias: String?, val isAliasExplicit: Boolean)

/**
 * Parses the output list of a SQL statement into individual items, each with its alias.
 *
 * Paired positionally against `java.sql.ResultSetMetaData` columns. The list comes from the statement of [parsed] that
 * produces the result set: the first `SELECT`, `VALUES`, `TABLE`, set operation or data-modifying statement with
 * `RETURNING`, as found in PostgreSQL's parse tree. Its `WITH` clause, nested `SELECT` and keyword-like text in
 * literals and comments are therefore not mistaken for the list. A set operation yields the list of its leftmost
 * branch.
 *
 * Handles:
 * - Simple columns: `title` → expression=`title`, columnName=`title`
 * - Qualified columns: `book.title` → expression=`book.title`, columnName=`title`, tableName=`book`
 * - Aliased columns: `author.name AS author_name` → expression=`author.name`, columnName=`name`, tableName=`author`
 * - Computed expressions: `COUNT(*) AS book_count` → expression=`COUNT(*)`, columnName=`null`
 * - Star projections: `*` → expression=`*`, columnName=`null`, isStar=`true`
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
 * Computes the items from the tokens and parse tree of [parsed] on every call. [ParsedSql.outputItems] holds the
 * result for consumers.
 *
 * @return Items strictly before the first star, if any; the full list if there is no star or it's a single star item.
 *   Empty when [parsed] has no output list in its text, as with `VALUES`, `TABLE` and `EXPLAIN`, when PostgreSQL's
 *   grammar rejects it, or when the end of the last item's expression cannot be confirmed. Both consumers degrade
 *   safely for a missing item, falling back to `ResultSetMetaData.getColumnName`, so no wrong original name is
 *   reported.
 */
internal fun parseOutputItemsWithAlias(parsed: ParsedSql): List<OutputItemWithAlias> {
  val statementNode = parsed.tree?.let(::findOutputStatementNode) ?: return emptyList()
  return parseOutputItems(parsed, statementNode)
}

/**
 * The items of [statementNode], a statement node of [ParsedSql.tree] in the shape [outputStatementOf] reads, such as a
 * [CteDefinition.ctequery]. Items are cut at the first star as [parseOutputItemsWithAlias] describes.
 * [ParsedSql.outputItemsOf] holds the result for consumers.
 *
 * Locations in the tree index the whole of [ParsedSql.sql], so a CTE body is read from the text of the statement that
 * declares it.
 */
internal fun parseOutputItems(parsed: ParsedSql, statementNode: JsonValue.JsonObject): List<OutputItemWithAlias> {
  val tree = parsed.tree ?: return emptyList()
  val statement = outputStatementOf(statementNode) ?: return emptyList()
  if (statement.targets.isEmpty()) return emptyList()
  val items =
    outputItems(parsed.sql, parsed.parserForm, parsed.significantTokens, tree, statement) ?: return emptyList()

  val firstStarIndex = items.indexOfFirst { it.selectItem.isStar }
  return if (firstStarIndex < 0 || items.size == 1) items else items.take(firstStarIndex)
}

private fun outputItems(
  sql: String,
  rewritten: String,
  tokens: List<ScanToken>,
  tree: JsonValue.JsonObject,
  statement: OutputStatement,
): List<OutputItemWithAlias>? {
  val targets = statement.targets
  val firstTokens = targets.map { target -> firstTokenIndex(tokens, target) ?: return null }
  return targets.indices.map { index ->
    val extent = if (index < targets.lastIndex) {
      extentBeforeNextItem(sql, tokens, targets[index], firstTokens[index], firstTokens[index + 1])
    } else {
      extentOfFinalItem(sql, rewritten, tokens, tree, statement, firstTokens[index])
    }
    buildItem(sql, tokens, targets[index], extent ?: return null)
  }
}

/**
 * The tokens of one item.
 *
 * @property firstToken the first token of the expression.
 * @property expressionLastToken the last token of the expression.
 * @property aliasFirstToken the first token of the alias, or `null` when the item has none.
 * @property isAliasExplicit whether the token before the alias is `AS`.
 */
private class ItemExtent(
  val firstToken: Int,
  val expressionLastToken: Int,
  val aliasFirstToken: Int?,
  val isAliasExplicit: Boolean,
)

private fun firstTokenIndex(tokens: List<ScanToken>, target: JsonValue.JsonObject): Int? {
  val location = target.intField(ParseTreeNames.LOCATION) ?: return null
  if (location < 0) return null
  return tokens.binarySearchBy(location) { it.start }.takeIf { it >= 0 }
}

/** An item that is not the last ends at the token before the comma, so its alias is the token at its end. */
private fun extentBeforeNextItem(
  sql: String,
  tokens: List<ScanToken>,
  target: JsonValue.JsonObject,
  firstToken: Int,
  nextFirstToken: Int,
): ItemExtent? {
  val commaIndex = nextFirstToken - 1
  if (commaIndex - 1 < firstToken || tokens[commaIndex].token != TokenNumber.COMMA) return null
  val lastToken = commaIndex - 1
  if (target.stringField(ParseTreeNames.ALIAS) == null) return ItemExtent(firstToken, lastToken, null, false)

  val aliasFirstToken = lastToken
  val isAliasExplicit = aliasFirstToken - 1 > firstToken && isKeyword(sql, tokens[aliasFirstToken - 1], AS_KEYWORD)
  val expressionLastToken = if (isAliasExplicit) aliasFirstToken - 2 else aliasFirstToken - 1
  if (expressionLastToken < firstToken) return null
  return ItemExtent(firstToken, expressionLastToken, aliasFirstToken, isAliasExplicit)
}

/**
 * Finds where the expression of the final item ends.
 *
 * Each token end at the item's own nesting depth, from the token of its largest location on, is a candidate. The text
 * from the item's start to the candidate is replaced by [PROBE_CONSTANT] between spaces. The candidate is the end when
 * that text parses, the item that starts at the constant has it as its value, and the tree otherwise equals the original
 * apart from locations.
 *
 * A candidate inside the expression, such as before `without time zone`, gives text that does not parse or gives the
 * item another value. A candidate after the expression replaces tokens that the tree records, such as the alias or
 * the `FROM` keyword, so the trees differ.
 */
private fun extentOfFinalItem(
  sql: String,
  rewritten: String,
  tokens: List<ScanToken>,
  tree: JsonValue.JsonObject,
  statement: OutputStatement,
  firstToken: Int,
): ItemExtent? {
  val start = tokens[firstToken].start
  val searchStart = searchStartIndex(tokens, statement.targets.last(), firstToken)
  val expectedTree = withoutItemValueAndLocations(tree, start)
  var depth = 0
  for (index in firstToken until tokens.size) {
    depth += nestingChange(tokens[index])
    if (depth < 0) return null
    if (depth != 0 || index < searchStart) continue
    val end = tokens[index].end
    val probe = rewritten.substring(0, start) + " $PROBE_CONSTANT " + rewritten.substring(end)
    val probeTree = parseOrNull(probe) ?: continue
    val isConstant = findItemAt(probeTree, start + 1)?.let { hasConstantValue(it, start + 1) } == true
    if (isConstant && withoutItemValueAndLocations(probeTree, start + 1) == expectedTree) {
      return finalItemExtent(sql, tokens, statement.targets.last(), firstToken, index)
    }
  }
  return null
}

/** The alias follows the expression, after `AS` when that is written. */
private fun finalItemExtent(
  sql: String,
  tokens: List<ScanToken>,
  target: JsonValue.JsonObject,
  firstToken: Int,
  expressionLastToken: Int,
): ItemExtent? {
  if (target.stringField(ParseTreeNames.ALIAS) == null) return ItemExtent(firstToken, expressionLastToken, null, false)

  val afterExpression = expressionLastToken + 1
  if (afterExpression >= tokens.size) return null
  val isAliasExplicit = isKeyword(sql, tokens[afterExpression], AS_KEYWORD)
  val aliasFirstToken = if (isAliasExplicit) afterExpression + 1 else afterExpression
  if (aliasFirstToken >= tokens.size) return null
  return ItemExtent(firstToken, expressionLastToken, aliasFirstToken, isAliasExplicit)
}

private fun hasConstantValue(item: JsonValue.JsonObject, location: Int): Boolean {
  val value = item.objectField(ParseTreeNames.VALUE) ?: return false
  val constant = value.objectField(ParseTreeNames.CONSTANT)
  return value.fields.size == 1 && constant?.intField(ParseTreeNames.LOCATION) == location
}

private fun isItemAt(value: JsonValue.JsonObject, location: Int): Boolean =
  value.intField(ParseTreeNames.LOCATION) == location && value.fields.containsKey(ParseTreeNames.VALUE)

/** The first node of [value] that [isItemAt] accepts, in document order, or `null` when there is none. */
private fun findItemAt(value: JsonValue, location: Int): JsonValue.JsonObject? = when (value) {
  is JsonValue.JsonObject ->
    value.takeIf { isItemAt(it, location) } ?: value.fields.values.firstNotNullOfOrNull { findItemAt(it, location) }
  is JsonValue.JsonArray -> value.items.firstNotNullOfOrNull { findItemAt(it, location) }
  else -> null
}

/**
 * [value] with the `val` of the item that starts at [location] replaced by `null` and every location field removed,
 * so that trees of texts of different length compare equal when only that value differs.
 */
private fun withoutItemValueAndLocations(value: JsonValue, location: Int): JsonValue = when (value) {
  is JsonValue.JsonObject -> {
    val isItem = isItemAt(value, location)
    JsonValue.JsonObject(
      value.fields.filterKeys { it !in locationFieldNames }.mapValues { (key, child) ->
        if (isItem && key == ParseTreeNames.VALUE) {
          JsonValue.JsonScalar("null")
        } else {
          withoutItemValueAndLocations(child, location)
        }
      },
    )
  }
  is JsonValue.JsonArray -> JsonValue.JsonArray(value.items.map { withoutItemValueAndLocations(it, location) })
  else -> value
}

private fun nestingChange(token: ScanToken): Int = when (token.token) {
  TokenNumber.OPEN_PARENTHESIS, TokenNumber.OPEN_BRACKET -> 1
  TokenNumber.CLOSE_PARENTHESIS, TokenNumber.CLOSE_BRACKET -> -1
  else -> 0
}

/** The item cannot end before the token of the largest location inside it, so the search starts there. */
private fun searchStartIndex(tokens: List<ScanToken>, target: JsonValue.JsonObject, firstToken: Int): Int {
  val location = largestLocation(target)
  val index = tokens.binarySearchBy(location, fromIndex = firstToken) { it.start }
  return if (index >= 0) index else firstToken
}

private fun largestLocation(value: JsonValue): Int = when (value) {
  is JsonValue.JsonObject -> maxOf(
    value.intField(ParseTreeNames.LOCATION) ?: NO_LOCATION,
    value.fields.values.maxOfOrNull(::largestLocation) ?: NO_LOCATION,
  )
  is JsonValue.JsonArray -> value.items.maxOfOrNull(::largestLocation) ?: NO_LOCATION
  else -> NO_LOCATION
}

private fun buildItem(
  sql: String,
  tokens: List<ScanToken>,
  target: JsonValue.JsonObject,
  extent: ItemExtent,
): OutputItemWithAlias {
  val expression = sql.substring(tokens[extent.firstToken].start, tokens[extent.expressionLastToken].end)
  val expressionTokens = tokens.subList(extent.firstToken, extent.expressionLastToken + 1)
  val selectItem = selectItem(sql, expressionTokens, expression, target)
  val alias = extent.aliasFirstToken?.let { sql.substring(tokens[it].start, tokens[it].end) }
  return OutputItemWithAlias(selectItem, alias, extent.isAliasExplicit)
}

private fun selectItem(
  sql: String,
  expressionTokens: List<ScanToken>,
  expression: String,
  target: JsonValue.JsonObject,
): SelectItem {
  val value = target.objectField(ParseTreeNames.VALUE)
  val isStar = value != null && isStar(value)
  val names = value?.let(::columnReferenceNames)
  if (names == null) return SelectItem(expression, columnName = null, tableName = null, isStar = isStar)

  val nameTokens = identifierTokens(expressionTokens)
  val hasNameTokens = nameTokens.size == names.size
  return SelectItem(
    expression = expression,
    columnName = names.last(),
    tableName = if (names.size == 2) names.first() else null,
    isColumnNameQuoted = hasNameTokens && isQuotedToken(sql, nameTokens.last()),
    isTableNameQuoted = hasNameTokens && names.size == 2 && isQuotedToken(sql, nameTokens.first()),
    isStar = isStar,
  )
}

private fun isStar(value: JsonValue.JsonObject): Boolean {
  val columnFields = value.objectField(ParseTreeNames.COLUMN_REFERENCE)?.objectArrayField(ParseTreeNames.FIELDS)
  if (columnFields != null) return columnFields.lastOrNull()?.objectField(ParseTreeNames.STAR) != null
  val indirection = value.objectField(ParseTreeNames.INDIRECTION_NODE)?.objectArrayField(ParseTreeNames.INDIRECTION)
  return indirection?.lastOrNull()?.objectField(ParseTreeNames.STAR) != null
}

/** The names of a one- or two-part column reference, or `null` for any other expression. */
private fun columnReferenceNames(value: JsonValue.JsonObject): List<String>? {
  val fields =
    value.objectField(ParseTreeNames.COLUMN_REFERENCE)?.objectArrayField(ParseTreeNames.FIELDS) ?: return null
  if (fields.size !in 1..2) return null
  return fields.map { it.objectField(ParseTreeNames.STRING)?.stringField(ParseTreeNames.STRING_VALUE) ?: return null }
}

/** The tokens that spell identifiers. */
private fun identifierTokens(tokens: List<ScanToken>): List<ScanToken> = tokens.filter {
  it.token !in setOf(TokenNumber.OPEN_PARENTHESIS, TokenNumber.CLOSE_PARENTHESIS, TokenNumber.PERIOD)
}

private fun isQuotedToken(sql: String, token: ScanToken): Boolean =
  token.token == TokenNumber.UNICODE_IDENTIFIER || sql[token.start] == '"'
