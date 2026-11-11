package norm.generator

import norm.generator.libpgquery.KeywordKind
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber
import norm.generator.libpgquery.isComment
import norm.generator.libpgquery.isKeyword

private const val UNICODE_ESCAPE_KEYWORD = "UESCAPE"

/**
 * A SQL text with the lexer tokens, parser form, parse tree and output items that its analyses read. Each is computed
 * at most once and shared by every consumer of the text.
 *
 * Construction scans [sql] and throws [IllegalArgumentException] if it contains a NUL character.
 *
 * @property sql the text, with its `?` placeholders.
 * @property tokens the lexer tokens of [sql], as [scanLeadingTokens] returns them.
 */
internal class ParsedSql(val sql: String) {
  val tokens: List<ScanToken> = scanLeadingTokens(sql)

  /**
   * [tokens] without comments, with each `U&"..." UESCAPE '...'` run as one [TokenNumber.UNICODE_IDENTIFIER] token.
   * PostgreSQL's `base_yylex` in `parser.c` merges the three tokens the same way.
   */
  val significantTokens: List<ScanToken> by lazy { mergeUnicodeEscapes(sql, tokens.filterNot { it.isComment() }) }

  /** [sql] as [parserFormOf] returns it. */
  val parserForm: String by lazy { parserFormOf(sql, tokens) }

  /** The parse tree of [parserForm], or `null` when PostgreSQL's grammar rejects it. */
  val tree: JsonValue.JsonObject? by lazy { parseOrNull(parserForm) }

  /** The result of [parseOutputItemsWithAlias] for this text. */
  val outputItems: List<OutputItemWithAlias> by lazy { parseOutputItemsWithAlias(this) }

  private val parts = mutableMapOf<IntRange, ParsedSql>()

  /**
   * The [ParsedSql] of `sql.substring(start, end)`, which is this one when the range covers all of [sql]. Asking for
   * the same range again returns the same value.
   */
  fun part(start: Int, end: Int): ParsedSql = if (start == 0 && end == sql.length) {
    this
  } else {
    parts.getOrPut(start until end) { ParsedSql(sql.substring(start, end)) }
  }
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
