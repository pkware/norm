package norm.generator

import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryException
import norm.generator.libpgquery.ParseTreeNames
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
internal fun splitStatements(sql: String): List<String> {
  val spans = try {
    splitStatementSpans(sql)
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
internal fun explainedStatementStart(sql: String): Int {
  val tree = try {
    LibPgQuery.parse(replaceParameterPlaceholdersKeepingOffsets(sql))
  } catch (_: LibPgQueryException) {
    return 0
  }
  val explain = tree.objectArrayField(ParseTreeNames.STATEMENTS)
    .firstOrNull()
    ?.objectField(ParseTreeNames.STATEMENT)
    ?.objectField(ParseTreeNames.EXPLAIN_STATEMENT) ?: return 0
  val optionCount = explain.objectArrayField(ParseTreeNames.EXPLAIN_OPTIONS).size
  val tokens = scanLeadingTokens(sql).filter {
    it.token != TokenNumber.LINE_COMMENT && it.token != TokenNumber.BLOCK_COMMENT
  }
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
 * Tells whether the first significant token of [sql] opens a statement that SQL `PREPARE` accepts. SQL `PREPARE`
 * takes fewer statements than the protocol-level prepare of the JDBC driver, which also takes `EXPLAIN` and other
 * utility statements.
 */
internal fun startsPreparableStatement(sql: String): Boolean {
  val cursor = SqlTokenCursor(sql, 0)
  while (true) {
    when (val span = cursor.advanceSignificant() ?: return false) {
      is SqlSpan.Word -> return sql.substring(span.from, span.to).uppercase() in PREPARABLE_KEYWORDS
      is SqlSpan.Opaque -> return false
      is SqlSpan.Char -> if (sql[span.index] != '(') return false
    }
  }
}

/**
 * Returns [sql] once without each `INTO` clause, in order.
 *
 * In a statement that `PREPARE` accepts, `gram.y` writes `INTO` after `INSERT`, after `MERGE`, or as the
 * `into_clause` of a `SELECT`. The word before `INTO` does not tell them apart, because `insert` and `merge` can name
 * a column. Removing the `INTO` of an `INSERT` or `MERGE` leaves SQL that PostgreSQL rejects.
 *
 * A clause ends with its table name. `OptTempTableName` puts the reserved word `TABLE` and unreserved option words
 * before the name. A name part is a word that is not reserved, a quoted identifier, or any word after a `.`. A
 * Unicode name `U&"..."` may carry `UESCAPE '...'`. Any other token ends the clause.
 *
 * @param reservedWords the lowercase keywords that cannot start a name, as `pg_get_keywords()` lists them.
 */
internal fun withoutEachIntoClause(sql: String, reservedWords: Set<String>): Sequence<String> = sequence {
  val cursor = SqlTokenCursor(sql, 0)
  while (true) {
    val span = cursor.advanceSignificant() ?: break
    if (span.word(sql) == "INTO") {
      val start = (span as SqlSpan.Word).from
      yield(sql.removeRange(start, intoClauseEnd(sql, SqlTokenCursor(sql, span.to), span.to, reservedWords)))
    }
  }
}

private fun intoClauseEnd(sql: String, cursor: SqlTokenCursor, intoEnd: Int, reservedWords: Set<String>): Int {
  var end = intoEnd
  var afterDot = false
  var afterUescape = false
  while (true) {
    val span = cursor.advanceSignificant() ?: return end
    val namePartEnd = when (span) {
      is SqlSpan.Opaque -> span.to.takeIf { sql[span.from] == '"' || (afterUescape && sql[span.from] == '\'') }
      is SqlSpan.Word -> span.to.takeIf {
        afterDot ||
          span.word(sql) == "TABLE" ||
          isUnreserved(span, sql, reservedWords)
      }
      is SqlSpan.Char -> (span.index + 1).takeIf { opensUnicodeIdentifier(sql, span.index, end) }
    }
    when {
      namePartEnd != null -> {
        end = namePartEnd
        afterDot = false
        afterUescape = span.word(sql) == "UESCAPE"
      }
      span is SqlSpan.Char && sql[span.index] == '.' && !afterDot -> afterDot = true
      else -> return end
    }
  }
}

/** Tells whether the `&` at [index] joins a `U` that ends at [wordEnd] to a quoted name, as in `U&"d\0061ta"`. */
private fun opensUnicodeIdentifier(sql: String, index: Int, wordEnd: Int): Boolean =
  sql[index] == '&' && wordEnd == index && sql[index - 1].uppercaseChar() == 'U' && sql.startsWith("\"", index + 1)

private fun isUnreserved(word: SqlSpan.Word, sql: String, reservedWords: Set<String>): Boolean =
  sql.substring(word.from, word.to).lowercase() !in reservedWords

private fun SqlSpan.word(sql: String): String? = (this as? SqlSpan.Word)?.let {
  sql.substring(it.from, it.to).uppercase()
}

/** Lists the words that start a statement SQL `PREPARE` accepts, including `WITH`, which starts a query. */
private val PREPARABLE_KEYWORDS = setOf("SELECT", "VALUES", "TABLE", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE")
