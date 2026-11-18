package norm.generator

import norm.generator.libpgquery.KeywordKind
import norm.generator.libpgquery.LEXER_WHITESPACE
import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryResult
import norm.generator.libpgquery.OutputStatement
import norm.generator.libpgquery.ParseResult
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.StatementNode
import norm.generator.libpgquery.TokenNumber
import norm.generator.libpgquery.isComment
import norm.generator.libpgquery.isKeyword

private const val UNICODE_ESCAPE_KEYWORD = "UESCAPE"

/**
 * A SQL text with the lexer tokens, parser form, parse tree and output items that its analyses read. Each is computed
 * at most once and shared by every consumer of the text.
 *
 * Construction scans the text and throws [IllegalArgumentException] if it contains a NUL character. Instances are not
 * thread-safe.
 *
 * @property sql the text, with its `?` placeholders.
 * @property tokens the lexer tokens of [sql], as [scanLeadingTokens] returns them.
 * @property rejection the lexer's rejection of [sql], or `null` when the lexer accepted all of it. [tokens] stop at
 *   [LibPgQueryResult.Rejected.cursorIndex].
 */
internal class ParsedSql private constructor(val sql: String, scanned: ScannedText) {

  val tokens: List<ScanToken> = scanned.tokens

  val rejection: LibPgQueryResult.Rejected? = scanned.rejection

  /**
   * [tokens] without comments, with each `U&"..." UESCAPE '...'` run as one [TokenNumber.UNICODE_IDENTIFIER] token.
   * PostgreSQL's `base_yylex` in `parser.c` merges the three tokens the same way.
   */
  val significantTokens: List<ScanToken> by lazy(LazyThreadSafetyMode.NONE) {
    mergeUnicodeEscapes(sql, tokens.filterNot { it.isComment() })
  }

  /**
   * [sql] in the form PostgreSQL's parser accepts, with the length of [sql] and the offset of every other token kept.
   *
   * Each `?` placeholder is replaced by `0`, and each [namedParameters] run by spaces and a `0` at its last character.
   * In an operator token, pgjdbc's `??` escape becomes `?` and spaces at the end of the token restore its length. Text
   * outside operator tokens and named parameters is unchanged, as is text from the point where the lexer rejects [sql]
   * onward.
   */
  val parserForm: String by lazy(LazyThreadSafetyMode.NONE) { parserFormReplacing(namedParameters(this)) }

  /** The parse tree of [parserForm], or `null` when PostgreSQL's grammar rejects it. */
  val tree: ParseResult? by lazy(LazyThreadSafetyMode.NONE) { LibPgQuery.parse(parserForm).valueOrNull() }

  private val parts = mutableMapOf<IntRange, ParsedSql>()

  private val outputItemsByStatement = mutableMapOf<OutputStatement, List<OutputItem>>()

  constructor(sql: String) : this(sql, scanText(sql))

  private constructor(sql: String, tokens: List<ScanToken>) : this(sql, ScannedText(tokens, rejection = null))

  /**
   * The output items of [statement], a statement node of [tree] such as the body of a CTE. Items are cut at the first
   * star as [outputItems] describes. Asking for the same node again returns the same list.
   *
   * Locations in the tree index the whole of [sql], so a CTE body is read from the text of the statement that declares
   * it.
   */
  fun outputItemsOf(statement: StatementNode): List<OutputItem> =
    statement.outputStatement?.let { outputItemsOf(it) }.orEmpty()

  /** The output items of [output], a result set of [tree], cut at the first star. Computed once per [output]. */
  fun outputItemsOf(output: OutputStatement): List<OutputItem> =
    outputItemsByStatement.getOrPut(output) { parseOutputItems(this, output) }

  /**
   * [parserForm] with exactly [parameters] replaced as named parameters. [namedParameters] parses the result to decide
   * which candidates inside a slice or a JSON object are parameters.
   */
  internal fun parserFormReplacing(parameters: Collection<NamedParameter>): String =
    parserFormOf(sql, tokens, parameters)

  /**
   * The [ParsedSql] of `sql.substring(start, end)`, which is this one when the range covers all of [sql]. Asking for
   * the same range again returns the same value.
   *
   * The part takes its tokens from this one, shifted by `-start`, when the lexer accepted all of [sql], neither [start]
   * nor [end] lies inside a token, and [end] is the end of [sql] or the index of a `;` or a character of
   * [LEXER_WHITESPACE]. Otherwise it scans the substring.
   */
  fun part(start: Int, end: Int): ParsedSql = if (start == 0 && end == sql.length) {
    this
  } else {
    parts.getOrPut(start until end) {
      val text = sql.substring(start, end)
      if (canCutTokens(start, end)) ParsedSql(text, cutTokens(start, end)) else ParsedSql(text)
    }
  }

  private fun canCutTokens(start: Int, end: Int): Boolean = rejection == null &&
    !isInsideToken(start) &&
    !isInsideToken(end) &&
    (end == sql.length || sql[end] == ';' || sql[end] in LEXER_WHITESPACE)

  private fun isInsideToken(position: Int): Boolean {
    val found = tokens.binarySearchBy(position) { it.start }
    return found < 0 && tokens.getOrNull(-found - 2)?.let { position < it.end } == true
  }

  private fun cutTokens(start: Int, end: Int): List<ScanToken> {
    val found = tokens.binarySearchBy(start) { it.start }
    val first = if (found >= 0) found else -found - 1
    return tokens.subList(first, tokens.size)
      .takeWhile { it.end <= end }
      .map { it.copy(start = it.start - start, end = it.end - start) }
  }
}

/**
 * Lexer tokens of a text.
 *
 * @property rejection the lexer's rejection of the text, or `null` when it accepted all of it. [tokens] stop where the
 *   lexer rejected the text.
 */
private class ScannedText(val tokens: List<ScanToken>, val rejection: LibPgQueryResult.Rejected?)

private fun parserFormOf(sql: String, tokens: List<ScanToken>, namedParameters: Collection<NamedParameter>): String {
  val characters = sql.toCharArray()
  for (token in tokens.filter { it.token == TokenNumber.OPERATOR }) {
    var written = token.start
    var read = token.start
    forEachQuestionMark(sql, token) { index, isEscape ->
      while (read < index) characters[written++] = sql[read++]
      characters[written++] = if (isEscape) '?' else '0'
      read = index + if (isEscape) 2 else 1
    }
    while (read < token.end) characters[written++] = sql[read++]
    characters.fill(' ', written, token.end)
  }
  for (parameter in namedParameters) {
    characters.fill(' ', parameter.colon.start, parameter.name.end)
    characters[parameter.name.end - 1] = '0'
  }
  return String(characters)
}

private fun mergeUnicodeEscapes(sql: String, tokens: List<ScanToken>): List<ScanToken> {
  val merged = mutableListOf<ScanToken>()
  var index = 0
  while (index < tokens.size) {
    val token = tokens[index]
    val keyword = tokens.getOrNull(index + 1)
    val string = tokens.getOrNull(index + 2)
    if (token.token == TokenNumber.UNICODE_IDENTIFIER &&
      keyword != null &&
      isKeyword(sql, keyword, UNICODE_ESCAPE_KEYWORD) &&
      string?.token == TokenNumber.STRING
    ) {
      merged += ScanToken(token.start, string.end, TokenNumber.UNICODE_IDENTIFIER, KeywordKind.NONE)
      index += 3
    } else {
      merged += token
      index++
    }
  }
  return merged
}

/**
 * Splits [sql] into PostgreSQL lexer tokens, stopping where the lexer rejects the text.
 *
 * When the lexer reports an error at a position, the tokens before that position are returned and the text from
 * there on has no tokens. An error without a position leaves the whole text without tokens.
 *
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun scanLeadingTokens(sql: String): List<ScanToken> = scanText(sql).tokens

private fun scanText(sql: String): ScannedText = when (val result = LibPgQuery.scan(sql)) {
  is LibPgQueryResult.Accepted -> ScannedText(result.value, rejection = null)
  is LibPgQueryResult.Rejected -> {
    val cursorIndex = result.cursorIndex
    val leading = if (cursorIndex == null || cursorIndex !in sql.indices) {
      emptyList()
    } else {
      scanLeadingTokens(sql.take(cursorIndex))
    }
    ScannedText(leading, rejection = result)
  }
}
