package norm.generator

import norm.generator.libpgquery.KeywordKind
import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryException
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber
import norm.generator.libpgquery.isKeyword

/**
 * Replaces each `?` parameter placeholder in [parsed] with [replacement] applied to its 0-based
 * parameter index.
 *
 * PostgreSQL views cannot contain parameter placeholders. This function replaces each `?` that
 * represents a parameter, which allows the query to be used as a view body for node tree analysis.
 * Callers use it both to substitute a literal `"NULL"` (outer join nullability is determined by
 * join structure, not parameter values) and to substitute typed non-null sentinel literals, for a
 * strict function whose result nullability depends on every argument being non-null.
 *
 * Placeholders are located in the operator tokens of PostgreSQL's lexer, so a `?` inside a string literal,
 * `E''` escape string, quoted identifier, dollar-quoted string, or comment is left alone. Inside an operator token,
 * pgjdbc reads `??` as one literal `?`, the escape for the `jsonb` operators. It is emitted as a single `?` and is
 * not a placeholder. Text from the point where the lexer rejects [ParsedSql.sql] onward is left alone.
 *
 * @param parsed The SQL text with `?` parameter placeholders.
 * @param replacement Given a placeholder's 0-based parameter index, returns the SQL text to substitute for it.
 * @return The SQL with each `?` replaced by [replacement]'s result for it.
 */
internal fun replaceParameterPlaceholders(parsed: ParsedSql, replacement: (parameterIndex: Int) -> String): String {
  val sql = parsed.sql
  val result = StringBuilder(sql.length + 16)
  var copiedUpTo = 0
  var parameterIndex = 0
  forEachQuestionMark(sql, parsed.tokens) { index, isEscape ->
    result.append(sql, copiedUpTo, index)
    result.append(if (isEscape) "?" else replacement(parameterIndex++))
    copiedUpTo = index + if (isEscape) 2 else 1
  }
  return result.append(sql, copiedUpTo, sql.length).toString()
}

/**
 * Returns the 0-based char index of every `?` in [parsed] that is a genuine positional parameter
 * placeholder. [replaceParameterPlaceholders] uses the same rules, so the two functions agree on
 * what counts as a placeholder: a `?` inside a string literal, `E''` escape string, quoted
 * identifier, dollar-quoted string, line comment, or block comment is not a placeholder and is
 * excluded, as are both `?` of pgjdbc's `??` escape.
 *
 * @return The placeholder positions, in ascending order.
 */
internal fun placeholderPositions(parsed: ParsedSql): IntArray {
  val positions = mutableListOf<Int>()
  forEachQuestionMark(parsed.sql, parsed.tokens) { index, isEscape -> if (!isEscape) positions.add(index) }
  return positions.toIntArray()
}

/**
 * A `:name` named parameter.
 *
 * @property colon the `:` token.
 * @property name the identifier or keyword token that follows [colon] with no gap.
 */
internal class NamedParameter(val colon: ScanToken, val name: ScanToken)

/**
 * Finds the `:name` named parameters in [parsed], in source order.
 *
 * A candidate is a `:` token immediately followed by an identifier or keyword token that does not start with `"`. The
 * `::` and `:=` operators are separate tokens and never start one. A `:name`-shaped run inside a string literal, quoted
 * identifier, dollar-quoted string or comment is part of a larger token, so it is not found. Text from the point where
 * the lexer rejects [ParsedSql.sql] onward has no tokens.
 *
 * A bare `:` is also the grammar's slice separator in `arr[lo:hi]` and the key separator in `JSON_OBJECT(k:v)`. Those
 * are the only two productions of PostgreSQL 18's `gram.y` that hold one, and the key separator occurs only after
 * `JSON_OBJECT` or `JSON_OBJECTAGG`. A candidate with no open `[` and no earlier such keyword is a parameter.
 *
 * The parser decides the other candidates. All of them start as parameters, written as in [ParsedSql.parserForm]. When
 * the grammar rejects the text at the `0` of one of them, that candidate is kept as written and the text is parsed
 * again. A rejection anywhere else, or one without a position, ends the search. The candidates still written as
 * parameters then are parameters. The search parses at most one more time than there are such candidates.
 */
internal fun namedParameters(parsed: ParsedSql): List<NamedParameter> {
  val candidates = parameterCandidates(parsed)
  val certain = candidates.filterNot { it.isUndecided }.map { it.parameter }
  var undecided = candidates.filter { it.isUndecided }.map { it.parameter }
  while (undecided.isNotEmpty()) {
    val rejectionIndex = try {
      LibPgQuery.parse(parsed.parserFormReplacing(certain + undecided))
      break
    } catch (exception: LibPgQueryException) {
      exception.cursorIndex
    }
    val rejected = undecided.firstOrNull { it.name.end - 1 == rejectionIndex } ?: break
    undecided = undecided - rejected
  }
  return (certain + undecided).sortedBy { it.colon.start }
}

/**
 * A `:name` run.
 *
 * @property isUndecided whether the text before it leaves a slice or JSON object separator possible.
 */
private class ParameterCandidate(val parameter: NamedParameter, val isUndecided: Boolean)

private fun parameterCandidates(parsed: ParsedSql): List<ParameterCandidate> {
  val tokens = parsed.tokens
  val candidates = mutableListOf<ParameterCandidate>()
  var bracketDepth = 0
  var hasJsonObjectKeyword = false
  for ((index, token) in tokens.withIndex()) {
    when {
      token.token == TokenNumber.OPEN_BRACKET -> bracketDepth++
      token.token == TokenNumber.CLOSE_BRACKET -> bracketDepth = maxOf(0, bracketDepth - 1)
      JSON_OBJECT_KEYWORDS.any { isKeyword(parsed.sql, token, it) } -> hasJsonObjectKeyword = true
      token.token == TokenNumber.COLON -> {
        val name = tokens.getOrNull(index + 1)?.takeIf { it.start == token.end && isParameterName(parsed.sql, it) }
        if (name != null) {
          candidates += ParameterCandidate(NamedParameter(token, name), bracketDepth > 0 || hasJsonObjectKeyword)
        }
      }
    }
  }
  return candidates
}

/** The keywords after which `gram.y` accepts `a_expr ':' json_value_expr`, in its `json_name_and_value` rule. */
private val JSON_OBJECT_KEYWORDS = listOf("JSON_OBJECT", "JSON_OBJECTAGG")

private fun isParameterName(sql: String, token: ScanToken): Boolean =
  (token.token == TokenNumber.IDENTIFIER || token.keywordKind != KeywordKind.NONE) && sql[token.start] != '"'

internal fun operatorTokens(tokens: List<ScanToken>): List<ScanToken> = tokens.filter {
  it.token == TokenNumber.OPERATOR
}

internal inline fun forEachQuestionMark(
  sql: String,
  token: ScanToken,
  action: (index: Int, isEscape: Boolean) -> Unit,
) {
  var index = token.start
  while (index < token.end) {
    if (sql[index] != '?') {
      index++
      continue
    }
    val isEscape = index + 1 < token.end && sql[index + 1] == '?'
    action(index, isEscape)
    index += if (isEscape) 2 else 1
  }
}

private inline fun forEachQuestionMark(
  sql: String,
  tokens: List<ScanToken>,
  action: (index: Int, isEscape: Boolean) -> Unit,
) {
  for (token in operatorTokens(tokens)) forEachQuestionMark(sql, token, action)
}
