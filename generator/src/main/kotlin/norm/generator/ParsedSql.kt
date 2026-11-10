package norm.generator

import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber

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

  /** [tokens] without comments. */
  val significantTokens: List<ScanToken> by lazy {
    tokens.filter { it.token != TokenNumber.LINE_COMMENT && it.token != TokenNumber.BLOCK_COMMENT }
  }

  /** [sql] as [replaceParameterPlaceholdersKeepingOffsets] returns it. */
  val parserForm: String by lazy { parserFormOf(sql, tokens) }

  /** The parse tree of [parserForm], or `null` when PostgreSQL's grammar rejects it. */
  val tree: JsonValue.JsonObject? by lazy { parseOrNull(parserForm) }

  /** The result of [parseOutputItemsWithAlias] for this text. */
  val outputItems: List<OutputItemWithAlias> by lazy { parseOutputItemsWithAlias(this) }

  private val parts = mutableMapOf<IntRange, ParsedSql>()

  /** The [ParsedSql] of `sql.substring(start, end)`. Asking for the same range again returns the same value. */
  fun part(start: Int, end: Int): ParsedSql = parts.getOrPut(start until end) { ParsedSql(sql.substring(start, end)) }
}
