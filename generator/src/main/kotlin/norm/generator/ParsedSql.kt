package norm.generator

import norm.generator.libpgquery.KeywordKind
import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryException
import norm.generator.libpgquery.ParseTreeNames
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber
import norm.generator.libpgquery.isComment
import norm.generator.libpgquery.isKeyword
import java.util.IdentityHashMap

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

  /**
   * [sql] in the form PostgreSQL's parser accepts, with the length of [sql] and the offset of every other token kept.
   *
   * Each `?` placeholder is replaced by `0`, and each [namedParameters] run by spaces and a `0` at its last character.
   * In an operator token, pgjdbc's `??` escape becomes `?` and spaces at the end of the token restore its length. Text
   * outside operator tokens and named parameters is unchanged, as is text from the point where the lexer rejects [sql]
   * onward.
   */
  val parserForm: String by lazy { parserFormReplacing(namedParameters(this)) }

  /** The parse tree of [parserForm], or `null` when PostgreSQL's grammar rejects it. */
  val tree: JsonValue.JsonObject? by lazy { parseOrNull(parserForm) }

  private val parts = mutableMapOf<IntRange, ParsedSql>()

  /** The cache behind [outputItems] and [outputItemsOf], keyed by the identity of the statement node. */
  internal val outputItemsByStatement = IdentityHashMap<JsonValue.JsonObject, List<OutputItemWithAlias>>()

  /**
   * [parserForm] with exactly [parameters] replaced as named parameters. [namedParameters] parses the result to decide
   * which candidates inside a slice or a JSON object are parameters.
   */
  internal fun parserFormReplacing(parameters: Collection<NamedParameter>): String =
    parserFormOf(sql, tokens, parameters)

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

private fun parserFormOf(sql: String, tokens: List<ScanToken>, namedParameters: Collection<NamedParameter>): String {
  val characters = sql.toCharArray()
  for (token in operatorTokens(tokens)) {
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

/**
 * Parses [sql] with PostgreSQL's grammar.
 *
 * @return the parse tree, or `null` when the grammar rejects [sql].
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun parseOrNull(sql: String): JsonValue.JsonObject? = try {
  LibPgQuery.parse(sql)
} catch (_: LibPgQueryException) {
  null
}

/**
 * Returns the first statement of [tree]: the object at `stmts[0].stmt`, which holds one field named for the statement
 * kind, such as `SelectStmt`.
 *
 * @return the object, or `null` when [tree] has no statement.
 */
internal fun firstStatement(tree: JsonValue.JsonObject): JsonValue.JsonObject? =
  tree.objectArrayField(ParseTreeNames.STATEMENTS).firstOrNull()?.objectField(ParseTreeNames.STATEMENT)

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
