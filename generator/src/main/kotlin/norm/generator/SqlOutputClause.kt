package norm.generator

import norm.generator.libpgquery.KeywordKind
import norm.generator.libpgquery.ParseTreeNames
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber
import norm.generator.libpgquery.locationFieldNames

private const val AS_KEYWORD = "AS"
private const val UNICODE_ESCAPE_KEYWORD = "UESCAPE"
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
      val tokens = scanLeadingTokens(expression).filterNot(::isComment)
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
 * Parses the output list of a SQL statement into individual items.
 *
 * Paired positionally against `java.sql.ResultSetMetaData` columns. The list comes from the statement of [sql] that
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
 * @return Items strictly before the first star, if any; the full list if there is no star or it's a single star item.
 *   Empty when [sql] has no output list in its text, as with `VALUES`, `TABLE` and `EXPLAIN`, when PostgreSQL's
 *   grammar rejects [sql], or when the end of the last item's expression cannot be confirmed. Both consumers degrade
 *   safely for a missing item, falling back to `ResultSetMetaData.getColumnName`, so no wrong original name is
 *   reported.
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun parseSelectItems(sql: String): List<SelectItem> = parseOutputItemsWithAlias(sql).map { it.selectItem }

/**
 * Parses [sql]'s output clause with [parseSelectItems] and keeps the result only when it lines up
 * one-to-one with the result set's [columnCount] columns.
 *
 * [parseSelectItems] cannot confirm its own item count. A star spelling it does not recognize expands
 * to several columns and would shift every later item onto the wrong column.
 *
 * @return The parsed items, or empty when parsing found none or their count differs from [columnCount].
 */
internal fun alignSelectItems(sql: String, columnCount: Int): List<SelectItem> {
  val items = parseSelectItems(sql)
  return if (items.size == columnCount) items else emptyList()
}

/**
 * Whether the statement of [sql] that produces the result set is a `UNION`, `INTERSECT` or `EXCEPT`.
 *
 * [parseSelectItems] then returns only the items of the leftmost branch. The other branches may compute the same
 * result column differently, although the column takes its name from the first branch.
 *
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun hasTopLevelSetOperation(sql: String): Boolean =
  parseOrNull(replaceParameterPlaceholdersKeepingOffsets(sql))?.let(::findOutputStatement)?.isSetOperation ?: false

/**
 * A single item from [parseOutputItemsWithAlias], pairing the [selectItem] with the alias that follows its
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
 * The shared parsing core behind both [parseSelectItems] and [resolveNodeTreeProvenanceExpression]
 * (which needs [OutputItemWithAlias.alias] to cross-validate a CTE body item's own name against
 * the node tree's authoritative `:resname`). Finds the list [parseSelectItems] describes and
 * applies the same star truncation.
 *
 * Each item starts at its parse tree node's location. An item ends at the token before the comma that precedes the
 * next item. The last item's expression ends at the first token end where replacing the expression with a constant
 * leaves the rest of the parse tree unchanged. The alias that the tree records for it is the next token, or the one
 * after `AS`.
 *
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun parseOutputItemsWithAlias(sql: String): List<OutputItemWithAlias> {
  val rewritten = replaceParameterPlaceholdersKeepingOffsets(sql)
  val tree = parseOrNull(rewritten) ?: return emptyList()
  val statement = findOutputStatement(tree) ?: return emptyList()
  if (statement.targets.isEmpty()) return emptyList()
  val tokens = scanLeadingTokens(sql).filterNot(::isComment)
  val items = outputItems(sql, rewritten, tokens, tree, statement) ?: return emptyList()

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

private fun isComment(token: ScanToken): Boolean =
  token.token == TokenNumber.LINE_COMMENT || token.token == TokenNumber.BLOCK_COMMENT

private fun firstTokenIndex(tokens: List<ScanToken>, target: JsonValue.JsonObject): Int? {
  val location = target.intField(ParseTreeNames.LOCATION) ?: return null
  if (location < 0) return null
  return tokens.binarySearchBy(location) { it.start }.takeIf { it >= 0 }
}

/** An item that is not the last ends at the token before the comma, so its alias is the token or tokens at its end. */
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

  val aliasFirstToken = aliasStartToken(sql, tokens, lastToken)
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
 * that text parses, the value of the last item is the constant, and the tree otherwise equals the original apart from
 * locations.
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
    val isConstant = findOutputStatement(probeTree)?.targets?.lastOrNull()?.let { isConstantAt(it, start + 1) } == true
    if (isConstant && withoutItemValueAndLocations(probeTree, start + 1) == expectedTree) {
      return finalItemExtent(sql, tokens, statement.targets.last(), firstToken, index)
    }
  }
  return null
}

/** The alias follows the expression, after `AS` when that is written, and spans three tokens when it has `UESCAPE`. */
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

private fun isConstantAt(target: JsonValue.JsonObject, location: Int): Boolean {
  val value = target.objectField(ParseTreeNames.VALUE) ?: return false
  val constant = value.objectField(ParseTreeNames.CONSTANT)
  return value.fields.size == 1 && constant?.intField(ParseTreeNames.LOCATION) == location
}

/**
 * [value] with the `val` of the item that starts at [location] replaced by `null` and every location field removed,
 * so that trees of texts of different length compare equal when only that value differs.
 */
private fun withoutItemValueAndLocations(value: JsonValue, location: Int): JsonValue = when (value) {
  is JsonValue.JsonObject -> {
    val isItem = value.intField(ParseTreeNames.LOCATION) == location && value.fields.containsKey(ParseTreeNames.VALUE)
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
  val alias = extent.aliasFirstToken?.let { aliasFirst ->
    val aliasLast = aliasLastToken(sql, tokens, aliasFirst)
    sql.substring(tokens[aliasFirst].start, tokens[aliasLast].end)
  }
  return OutputItemWithAlias(selectItem, alias, extent.isAliasExplicit)
}

/** A Unicode-escaped alias spans `U&"..."`, `UESCAPE` and the escape string, which the lexer reports as three tokens. */
private fun aliasLastToken(sql: String, tokens: List<ScanToken>, aliasFirstToken: Int): Int {
  val hasEscapeClause = tokens[aliasFirstToken].token == TokenNumber.UNICODE_IDENTIFIER &&
    aliasFirstToken + 2 < tokens.size &&
    isKeyword(sql, tokens[aliasFirstToken + 1], UNICODE_ESCAPE_KEYWORD) &&
    tokens[aliasFirstToken + 2].token == TokenNumber.STRING
  return if (hasEscapeClause) aliasFirstToken + 2 else aliasFirstToken
}

/** The first token of the alias that ends at [lastToken], which is three tokens back for a `UESCAPE` alias. */
private fun aliasStartToken(sql: String, tokens: List<ScanToken>, lastToken: Int): Int {
  val isEscapeClause = lastToken >= 2 &&
    tokens[lastToken].token == TokenNumber.STRING &&
    isKeyword(sql, tokens[lastToken - 1], UNICODE_ESCAPE_KEYWORD) &&
    tokens[lastToken - 2].token == TokenNumber.UNICODE_IDENTIFIER
  return if (isEscapeClause) lastToken - 2 else lastToken
}

private fun isKeyword(sql: String, token: ScanToken, keyword: String): Boolean =
  token.keywordKind != KeywordKind.NONE &&
    token.end - token.start == keyword.length &&
    sql.regionMatches(token.start, keyword, 0, keyword.length, ignoreCase = true)

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

  val nameTokens = identifierTokens(sql, expressionTokens)
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

/** The tokens that spell identifiers, with a Unicode-escaped identifier and its `UESCAPE` clause as one token. */
private fun identifierTokens(sql: String, tokens: List<ScanToken>): List<ScanToken> {
  val identifiers = mutableListOf<ScanToken>()
  var index = 0
  while (index < tokens.size) {
    val token = tokens[index]
    index++
    when (token.token) {
      TokenNumber.OPEN_PARENTHESIS, TokenNumber.CLOSE_PARENTHESIS, TokenNumber.PERIOD -> Unit
      TokenNumber.UNICODE_IDENTIFIER -> {
        identifiers += token
        val hasEscapeClause = index + 1 < tokens.size &&
          isKeyword(sql, tokens[index], UNICODE_ESCAPE_KEYWORD) &&
          tokens[index + 1].token == TokenNumber.STRING
        if (hasEscapeClause) index += 2
      }
      else -> identifiers += token
    }
  }
  return identifiers
}

private fun isQuotedToken(sql: String, token: ScanToken): Boolean =
  token.token == TokenNumber.UNICODE_IDENTIFIER || sql[token.start] == '"'
