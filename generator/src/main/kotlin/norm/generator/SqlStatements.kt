package norm.generator

import norm.generator.libpgquery.KeywordKind
import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryException
import norm.generator.libpgquery.ParseTreeNames
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.SplitStatement
import norm.generator.libpgquery.TokenNumber

/**
 * Splits [sql] at each top-level `;` with PostgreSQL's splitter and returns each statement with its text in [sql].
 * A part with no keyword, such as `1`, yields no statement, and neither does text with unbalanced parentheses, such as
 * `SELECT 1); SELECT ?`. A `BEGIN ATOMIC` function body stays whole, as the JDBC driver keeps it.
 *
 * Text that the lexer rejects, such as an unterminated string, is one part.
 *
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun splitStatements(sql: String): List<String> =
  splitStatements(sql, ::replaceParameterPlaceholdersKeepingOffsets)

internal fun splitStatements(parsed: ParsedSql): List<String> = splitStatements(parsed.sql) { parsed.parserForm }

private fun splitStatements(sql: String, rewriteForParser: (String) -> String): List<String> {
  val spans = try {
    splitStatementSpans(sql, rewriteForParser)
  } catch (_: LibPgQueryException) {
    return listOf(sql)
  }
  return spans.map { sql.substring(it.start, it.end) }
}

/**
 * Splits [sql] at top-level `;` with PostgreSQL's lexer. When that finds several statements, the parser splits the
 * text again, which keeps a `BEGIN ATOMIC` body as one statement. The lexer's statements stand when the parser rejects
 * the text. Each statement starts where the lexer's statement containing its first token starts, which keeps the
 * whitespace and comments before it.
 *
 * @param rewriteForParser turns [sql] into text of the same length that the parser accepts. The default replaces `?`
 *   placeholders.
 * @return the statements, with the offsets of [sql].
 * @throws LibPgQueryException if the lexer rejects [sql].
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun splitStatementSpans(
  sql: String,
  rewriteForParser: (String) -> String = ::replaceParameterPlaceholdersKeepingOffsets,
): List<SplitStatement> {
  val spans = LibPgQuery.split(sql)
  if (spans.size < 2) return spans
  return try {
    LibPgQuery.splitWithParser(rewriteForParser(sql)).map { statement ->
      SplitStatement(spans.lastOrNull { it.start <= statement.start }?.start ?: statement.start, statement.end)
    }
  } catch (_: LibPgQueryException) {
    spans
  }
}

/**
 * Returns the index where the statement that a leading `EXPLAIN` wraps begins, or `0` when [sql] does not start with
 * `EXPLAIN` or PostgreSQL rejects it. Only whitespace and comments lie between the index and the wrapped statement.
 *
 * The parse tree of [sql] gives the number of options. Each legacy word, `ANALYZE` or `VERBOSE`, is one token and one
 * option. A parenthesized option list ends at its first `)`, because no option value holds a parenthesis. The index
 * is the position of a `(` that follows `EXPLAIN` when there are no options, and otherwise the end of the last token
 * that `EXPLAIN` consumes.
 *
 * The options hold no placeholder, so the parameter numbers of the wrapped statement are those of [sql].
 *
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun explainedStatementStart(sql: String): Int = explainedStatementStart(ParsedSql(sql))

internal fun explainedStatementStart(parsed: ParsedSql): Int {
  val tree = parsed.tree ?: return 0
  val explain = tree.objectArrayField(ParseTreeNames.STATEMENTS)
    .firstOrNull()
    ?.objectField(ParseTreeNames.STATEMENT)
    ?.objectField(ParseTreeNames.EXPLAIN_STATEMENT) ?: return 0
  val optionCount = explain.objectArrayField(ParseTreeNames.EXPLAIN_OPTIONS).size
  val tokens = parsed.significantTokens
  val explainIndex = tokens.indexOfFirst { it.token != TokenNumber.SEMICOLON }
  if (explainIndex < 0) return 0
  val next = tokens.getOrNull(explainIndex + 1)
  return when {
    next?.token == TokenNumber.OPEN_PARENTHESIS && optionCount == 0 -> next.start
    next?.token == TokenNumber.OPEN_PARENTHESIS ->
      tokens.firstOrNull { it.token == TokenNumber.CLOSE_PARENTHESIS }?.end ?: 0
    else -> tokens.getOrNull(explainIndex + optionCount)?.end ?: 0
  }
}

/**
 * Tells whether the first token of [sql] that is not a comment or `(` is the keyword that opens a statement SQL
 * `PREPARE` accepts: `SELECT`, `VALUES`, `TABLE`, `WITH`, `INSERT`, `UPDATE`, `DELETE`, or `MERGE`. SQL `PREPARE` takes
 * fewer statements than the protocol-level prepare of the JDBC driver, which also takes `EXPLAIN` and other utility
 * statements.
 *
 * The answer comes from the lexer alone, so it does not depend on whether PostgreSQL parses the rest of [sql].
 *
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun startsPreparableStatement(sql: String): Boolean = startsPreparableStatement(ParsedSql(sql))

internal fun startsPreparableStatement(parsed: ParsedSql): Boolean {
  val first = parsed.significantTokens.firstOrNull { it.token != TokenNumber.OPEN_PARENTHESIS } ?: return false
  return PREPARABLE_KEYWORDS.any { isKeyword(parsed.sql, first, it) }
}

/**
 * Removes the `INTO` clause of a `SELECT ... INTO` from [sql], from the `INTO` keyword through the table name. An
 * `INSERT INTO` or `MERGE INTO` has no such clause. In a set operation the clause belongs to the leftmost `SELECT`.
 *
 * The parse tree gives the location of the table name and the number of its parts. The lexer tokens give the end of
 * the name and the start of the clause. A `U&"..."` part ends with its `UESCAPE` string. Only keywords such as
 * `TEMPORARY`, `UNLOGGED` or `TABLE` lie between `INTO` and the name.
 *
 * @return the text with the clause removed, or `null` when PostgreSQL rejects [sql] or [sql] has no clause.
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 * @throws IllegalStateException if the tokens do not match the parse tree.
 */
internal fun withoutIntoClause(sql: String): String? = withoutIntoClause(ParsedSql(sql))

internal fun withoutIntoClause(parsed: ParsedSql): String? {
  val sql = parsed.sql
  val tree = parsed.tree ?: return null
  val relation = tree.objectArrayField(ParseTreeNames.STATEMENTS)
    .firstOrNull()
    ?.objectField(ParseTreeNames.STATEMENT)
    ?.objectField(ParseTreeNames.SELECT_STATEMENT)
    ?.let(::leftmostLeaf)
    ?.objectField(ParseTreeNames.INTO_CLAUSE)
    ?.objectField(ParseTreeNames.INTO_RELATION) ?: return null
  val tokens = parsed.significantTokens
  val nameStart = tokens.indexOfFirst { it.start == relation.intField(ParseTreeNames.LOCATION) }
  check(nameStart >= 0) { "No token starts the INTO table name" }
  val partCount = INTO_NAME_PARTS.count { relation.stringField(it) != null }
  var nameEnd = nameStart
  repeat(partCount - 1) {
    nameEnd = nameEndToken(sql, tokens, nameEnd)
    check(tokens.getOrNull(nameEnd + 1)?.token == TokenNumber.PERIOD) { "No period follows a part of the INTO name" }
    nameEnd += 2
  }
  nameEnd = nameEndToken(sql, tokens, nameEnd)
  var intoIndex = nameStart - 1
  while (intoIndex >= 0 && !isKeyword(sql, tokens[intoIndex], INTO_KEYWORD)) {
    check(tokens[intoIndex].keywordKind != KeywordKind.NONE) { "A non-keyword token precedes the INTO table name" }
    intoIndex--
  }
  check(intoIndex >= 0) { "No INTO keyword precedes the INTO table name" }
  return sql.removeRange(tokens[intoIndex].start, tokens[nameEnd].end)
}

/** Returns the index of the last token of the name part at [index]. A `U&"..."` part ends with its `UESCAPE` string. */
private fun nameEndToken(sql: String, tokens: List<ScanToken>, index: Int): Int {
  val hasEscapeClause = tokens[index].token == TokenNumber.UNICODE_IDENTIFIER &&
    tokens.getOrNull(index + 1)?.let { isKeyword(sql, it, UNICODE_ESCAPE_KEYWORD) } == true &&
    tokens.getOrNull(index + 2)?.token == TokenNumber.STRING
  return if (hasEscapeClause) index + 2 else index
}

private const val INTO_KEYWORD = "INTO"

private val INTO_NAME_PARTS =
  listOf(ParseTreeNames.CATALOG_NAME, ParseTreeNames.SCHEMA_NAME, ParseTreeNames.RELATION_NAME)

/** The keywords that open a statement SQL `PREPARE` accepts, including `WITH`, which opens a query. */
private val PREPARABLE_KEYWORDS = listOf("SELECT", "VALUES", "TABLE", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE")
