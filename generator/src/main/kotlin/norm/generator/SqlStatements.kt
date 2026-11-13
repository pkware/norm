package norm.generator

import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryResult
import norm.generator.libpgquery.SplitStatement

/**
 * Splits [parsed] at each top-level `;` with PostgreSQL's splitter and returns each statement as a [ParsedSql.part].
 * A part with no keyword, such as `1`, yields no statement, and neither does text with unbalanced parentheses, such as
 * `SELECT 1); SELECT ?`. A `BEGIN ATOMIC` function body stays whole, as the JDBC driver keeps it.
 *
 * Text that the lexer rejects, such as an unterminated string, is one part.
 */
internal fun splitStatements(parsed: ParsedSql): List<ParsedSql> {
  val spans = splitStatementSpans(parsed) ?: return listOf(parsed)
  return spans.map { parsed.part(it.start, it.end) }
}

/**
 * Splits [parsed] at top-level `;` with PostgreSQL's lexer. When that finds several statements, the parser splits
 * [ParsedSql.parserForm] again, which keeps a `BEGIN ATOMIC` body as one statement. The lexer's statements stand when
 * the parser rejects the text. Each statement starts where the lexer's statement containing its first token starts,
 * which keeps the whitespace and comments before it.
 *
 * @return the statements, with the offsets of [ParsedSql.sql], or `null` when the lexer rejects [ParsedSql.sql].
 */
internal fun splitStatementSpans(parsed: ParsedSql): List<SplitStatement>? {
  val spans = LibPgQuery.split(parsed.sql).valueOrNull() ?: return null
  if (spans.size < 2) return spans
  return when (val parserSpans = LibPgQuery.splitWithParser(parsed.parserForm)) {
    is LibPgQueryResult.Accepted -> parserSpans.value.map { statement ->
      SplitStatement(spans.lastOrNull { it.start <= statement.start }?.start ?: statement.start, statement.end)
    }
    is LibPgQueryResult.Rejected -> spans
  }
}
