package norm.generator

import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.ParseTreeNames
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber

/**
 * A CTE definition from a `WITH` clause.
 *
 * @property name The CTE name as PostgreSQL's parser stores it, equal to the name the server reports. A quoted name
 *   may need requoting to round-trip as SQL, so use [rawName] to build SQL.
 * @property rawName The CTE name exactly as written in the original SQL, with its double quotes if it has them.
 *   Safe to splice verbatim into a `FROM <rawName>` probe.
 * @property bodyOpenParenthesis Index of the `(` that opens the CTE body in the original SQL.
 * @property bodyCloseParenthesis Index of the `)` that closes the CTE body.
 */
internal data class CteDefinition(
  val name: String,
  val rawName: String,
  val bodyOpenParenthesis: Int,
  val bodyCloseParenthesis: Int,
)

/**
 * The `WITH` clause of a statement.
 *
 * @property definitions The CTEs in declaration order.
 * @property mainQueryStart Index in the original SQL of the first token after the last CTE body, skipping comments.
 *   The length of the SQL when no token follows.
 */
internal data class ParsedCteClause(val definitions: List<CteDefinition>, val mainQueryStart: Int)

/**
 * Reads the `WITH` clause of the first statement in [sql].
 *
 * PostgreSQL's parser supplies the CTE names and their order from the statement's own `withClause`, so a `WITH`
 * inside a CTE body or a subquery is not included. Scan tokens supply each body's parentheses, which the parse tree
 * does not locate. A `?` placeholder is accepted.
 *
 * @return The clause, or `null` when PostgreSQL's parser rejects [sql], [sql] contains a NUL character, or the first
 *   statement has no `WITH` clause.
 * @throws IllegalStateException if a parsed CTE has no name token at its location or no body parentheses.
 */
@Throws(IllegalStateException::class)
internal fun parseCteClause(sql: String): ParsedCteClause? {
  if ('\u0000' in sql) return null
  val rewritten = replaceParameterPlaceholdersKeepingOffsets(sql)
  val tree = parseOrNull(rewritten) ?: return null
  val commonTableExpressions = commonTableExpressions(tree)
  if (commonTableExpressions.isEmpty()) return null

  val tokens = LibPgQuery.scan(rewritten)
  var lastBodyClose = -1
  val definitions = commonTableExpressions.map { expression ->
    val nameIndex = tokens.indexOfFirst { it.start == expression.intField(ParseTreeNames.LOCATION) }
    check(nameIndex >= 0) { "No token starts at the location of a CTE name" }
    var bodyOpen = nextOpenParenthesis(tokens, nameIndex + 1)
    if (expression.fields.containsKey(ParseTreeNames.COMMON_TABLE_EXPRESSION_COLUMN_NAMES)) {
      bodyOpen = nextOpenParenthesis(tokens, matchingCloseParenthesis(tokens, bodyOpen) + 1)
    }
    lastBodyClose = matchingCloseParenthesis(tokens, bodyOpen)
    CteDefinition(
      name = checkNotNull(expression.stringField(ParseTreeNames.COMMON_TABLE_EXPRESSION_NAME)),
      rawName = sql.substring(tokens[nameIndex].start, tokens[nameIndex].end),
      bodyOpenParenthesis = tokens[bodyOpen].start,
      bodyCloseParenthesis = tokens[lastBodyClose].start,
    )
  }
  val mainQueryStart = tokens.drop(lastBodyClose + 1).firstOrNull { !it.isComment() }?.start ?: sql.length
  return ParsedCteClause(definitions, mainQueryStart)
}

private fun commonTableExpressions(tree: JsonValue.JsonObject): List<JsonValue.JsonObject> {
  val statement = tree.objectArrayField(ParseTreeNames.STATEMENTS).firstOrNull()
    ?.objectField(ParseTreeNames.STATEMENT)
    ?.fields?.values?.singleOrNull() as? JsonValue.JsonObject
  return statement?.objectField(ParseTreeNames.WITH_CLAUSE)
    ?.objectArrayField(ParseTreeNames.COMMON_TABLE_EXPRESSIONS)
    .orEmpty()
    .mapNotNull { it.objectField(ParseTreeNames.COMMON_TABLE_EXPRESSION) }
}

private fun ScanToken.isComment(): Boolean = token == TokenNumber.LINE_COMMENT || token == TokenNumber.BLOCK_COMMENT

private fun nextOpenParenthesis(tokens: List<ScanToken>, from: Int): Int {
  val index = (from until tokens.size).firstOrNull { tokens[it].token == TokenNumber.OPEN_PARENTHESIS }
  return checkNotNull(index) { "No opening parenthesis follows a CTE name" }
}

private fun matchingCloseParenthesis(tokens: List<ScanToken>, openIndex: Int): Int {
  var depth = 0
  for (index in openIndex until tokens.size) {
    when (tokens[index].token) {
      TokenNumber.OPEN_PARENTHESIS -> depth++
      TokenNumber.CLOSE_PARENTHESIS -> if (--depth == 0) return index
    }
  }
  error("No closing parenthesis matches the one at ${tokens[openIndex].start}")
}
