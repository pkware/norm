package norm.generator

import norm.generator.libpgquery.KeywordKind
import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryException
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber

/**
 * A query parsed from a SQL file.
 *
 * @property name Developer-assigned name from the `-- name:` annotation.
 * @property command How the query should be executed. See [Command].
 * @property sql The SQL text, with `?` positional parameters. If the original SQL used `:name`-style named
 *   parameters, they have been converted to `?` form.
 * @property comments Comment lines preceding the query annotation, used for KDoc generation.
 * @property namedParameters Map from 1-based positional parameter number to the developer-chosen name. Empty when the
 *   query uses `?` positional parameters directly.
 * @property sourceLine 1-based line number of the `-- name:` annotation in the source file. `0` if unknown
 *   (for example, for synthesized CRUD queries).
 * @property sourceFile Path to the SQL file this query was parsed from. Empty string if unknown (for example,
 *   for synthesized CRUD queries).
 * @property isSynthesizedInsert `true` if this is a CRUD-generated INSERT query (from
 * [CrudQuerySynthesizer.synthesizeInsert]),
 *   `false` for all other queries including hand-written and other synthesized CRUD queries.
 * @property overridableDefaultParameterPositions The 1-based positions (within [sql]'s `?` placeholders)
 *   that [CrudQuerySynthesizer.synthesizeInsert] marked as bound to a column with a server-side
 *   `DEFAULT` the caller may override. Empty for every query except a CRUD-synthesized INSERT that
 *   has at least one such column — in particular, always empty for hand-written queries, since only
 *   [CrudQuerySynthesizer] populates this.
 * @property batchSql [sql] with its `RETURNING` clause removed, for a CRUD-synthesized INSERT that has
 *   one. `null` for every other query, including a synthesized INSERT with no `RETURNING` clause and
 *   every hand-written query — only [CrudQuerySynthesizer.synthesizeInsert] populates this.
 */
public data class ParsedQuery(
  val name: String,
  val command: Command,
  val sql: String,
  val comments: List<String>,
  val namedParameters: Map<Int, String> = emptyMap(),
  val sourceLine: Int = 0,
  val sourceFile: String = "",
  val isSynthesizedInsert: Boolean = false,
  val overridableDefaultParameterPositions: Set<Int> = emptySet(),
  val batchSql: String? = null,
)

/**
 * Parses SQL files containing annotated queries in the format:
 * ```sql
 * -- Optional comment
 * -- name: queryName :command
 * SELECT ...;
 * ```
 *
 * The file is scanned with PostgreSQL's lexer. An annotation is a `--` comment that is the first token on its line
 * and matches `-- name: <name> :<command>`. The same text inside a string, a quoted identifier, a dollar quote, or a
 * block comment is not an annotation.
 *
 * A query's SQL starts on the line after its annotation. Its terminator is the first `;` that ends a statement
 * and has no token after it on its line except comments and further `;`. When several `;` follow each other, the last
 * one is the terminator. The SQL ends at the terminator and excludes it. Without a terminator, the SQL runs to the next
 * annotation or the end of the file, minus one trailing `;`.
 *
 * After the terminator, a `--` comment that starts its own line is a doc comment of the next query. A block comment or
 * a `;` discards the doc comments before it. Any other token is an error. A token made only of whitespace characters,
 * such as a non-breaking space, counts as whitespace.
 *
 * ## Named Parameters
 *
 * Queries may use `:paramName` named parameters, which are converted to `?` positional parameters:
 * ```sql
 * -- name: updateUser :exec
 * UPDATE users SET name = :name, age = :age WHERE id = :id;
 * ```
 *
 * Named parameters are converted to `?` placeholders in order of appearance. Each occurrence of
 * a named parameter produces its own `?` — the same name used multiple times creates multiple
 * bind slots. Mixing `:name` and `?` styles in a single query is not allowed.
 *
 * A `:name` that appears inside a string literal, quoted identifier, dollar-quoted string, or
 * comment is left untouched.
 */
public object QueryFileParser {

  private const val LEXER_WHITESPACE = " \t\n\r\u000C\u000B"

  private val NAME_ANNOTATION = Regex("""^--\s*name:\s*(\S+)\s+:(\S+)\s*$""")

  /**
   * Parses the content of a SQL file into a list of [ParsedQuery] instances.
   *
   * @param content The full text content of a SQL file.
   * @param sourceFile Path to the SQL file being parsed, stored on each [ParsedQuery] for diagnostics.
   *   Empty string if the source path is unknown.
   * @return Parsed queries in the order they appear in the file.
   * @throws IllegalArgumentException if a `-- name:` annotation names a command that doesn't match any
   *   [Command]'s SQL form (for example `:foo`), if a query mixes named and positional parameter styles, if the file
   *   contains a NUL character or text that PostgreSQL's lexer rejects, such as an unterminated string, or if a
   *   token other than a comment or `;` follows a query's terminating `;`. The message names the line and, when the
   *   text belongs to one, the query.
   */
  public fun parse(content: String, sourceFile: String = ""): List<ParsedQuery> {
    val text = content.lines().joinToString("\n")
    val lines = LineIndex(text)
    val tokens = scanFile(text, lines)
    val annotations = findAnnotations(text, tokens, lines)
    val pendingComments = mutableListOf<String>()
    val preambleEnd = annotations.firstOrNull()?.start ?: text.length
    collectComments(text, tokens.filter { it.start < preambleEnd }, pendingComments) { pendingComments.clear() }

    return annotations.mapIndexed { index, annotation ->
      val bodyStart = text.indexOf('\n', annotation.end).let { if (it < 0) text.length else it + 1 }
      val bodyEnd = annotations.getOrNull(index + 1)?.let { text.lastIndexOf('\n', it.start - 1) + 1 } ?: text.length
      val bodyTokens = tokens.filter { it.start in bodyStart until bodyEnd }
      val comments = pendingComments.toList()
      pendingComments.clear()
      val terminator = findTerminator(text, lines, bodyStart, bodyEnd, bodyTokens)
      val rawSql = if (terminator == null) {
        text.substring(bodyStart, bodyEnd).trim().removeSuffix(";").trim()
      } else {
        collectComments(text, bodyTokens.filter { it.start > terminator }, pendingComments) { token ->
          throw IllegalArgumentException(
            "Query '${annotation.name}' at line ${annotation.line} has SQL on line ${lines.lineOf(token.start)} " +
              "after the ';' that ends it. Start a new query with a '-- name:' annotation.",
          )
        }
        text.substring(bodyStart, terminator).trim()
      }
      buildQuery(annotation, rawSql, comments, sourceFile)
    }
  }

  private class QueryAnnotation(val name: String, val command: String, val line: Int, val start: Int, val end: Int)

  private class LineIndex(text: String) {
    private val newlines = text.indices.filter { text[it] == '\n' }.toIntArray()

    /** Returns the 1-based line that holds [index]. */
    fun lineOf(index: Int): Int {
      val found = newlines.binarySearch(index)
      return 1 + if (found >= 0) found else -found - 1
    }
  }

  /**
   * Scans [text] with PostgreSQL's lexer.
   *
   * @throws IllegalArgumentException if [text] contains a NUL character or the lexer rejects it. The message names the
   *   line and the query.
   */
  private fun scanFile(text: String, lines: LineIndex): List<ScanToken> {
    val nulIndex = text.indexOf('\u0000')
    require(nulIndex < 0) {
      "${locate(text, lines, nulIndex)} contains a NUL character on line ${lines.lineOf(nulIndex)}."
    }
    return try {
      LibPgQuery.scan(text)
    } catch (exception: LibPgQueryException) {
      val cursorIndex = exception.cursorIndex?.takeIf { it in text.indices }
      val where = if (cursorIndex == null) "The query file" else locate(text, lines, cursorIndex)
      val line = cursorIndex?.let { lines.lineOf(it).toString() } ?: "unknown"
      throw IllegalArgumentException(
        "$where holds text that PostgreSQL's lexer rejects on line $line: ${exception.message}.",
        exception,
      )
    }
  }

  /** Names the query whose annotation is the last one before [index], or the text before the first annotation. */
  private fun locate(text: String, lines: LineIndex, index: Int): String {
    val annotation = findAnnotations(text, scanLeadingTokens(text.substring(0, index)), lines).lastOrNull()
    return if (annotation == null) {
      "The text before the first annotation"
    } else {
      "Query '${annotation.name}' at line ${annotation.line}"
    }
  }

  private fun findAnnotations(text: String, tokens: List<ScanToken>, lines: LineIndex): List<QueryAnnotation> =
    tokens.mapNotNull { token ->
      if (token.token != TokenNumber.LINE_COMMENT || !startsLine(text, token)) return@mapNotNull null
      val match = NAME_ANNOTATION.matchEntire(text.substring(token.start, token.end).trim()) ?: return@mapNotNull null
      QueryAnnotation(match.groupValues[1], match.groupValues[2], lines.lineOf(token.start), token.start, token.end)
    }

  private fun startsLine(text: String, token: ScanToken): Boolean {
    val lineStart = text.lastIndexOf('\n', token.start - 1) + 1
    return (lineStart until token.start).all { text[it].isWhitespace() }
  }

  /**
   * Returns the index of the `;` that ends a query whose body is `text[bodyStart, bodyEnd)`, or `null` when the body
   * has none.
   *
   * The terminator is the first `;` that ends a statement and has no token after it on its line except comments and
   * further `;`. When several `;` follow each other, the last one is the terminator. Statements come from
   * [splitStatementSpans], with each named parameter shown to the parser as `0`.
   */
  private fun findTerminator(
    text: String,
    lines: LineIndex,
    bodyStart: Int,
    bodyEnd: Int,
    tokens: List<ScanToken>,
  ): Int? {
    val body = text.substring(bodyStart, bodyEnd)
    val statements = try {
      splitStatementSpans(withLexerWhitespace(body), ::rewriteForParser)
    } catch (_: LibPgQueryException) {
      splitStatementSpans(body, ::rewriteForParser)
    }
    for (statement in statements) {
      val end = bodyStart + statement.end
      if (end >= bodyEnd || text[end] != ';') continue
      val line = lines.lineOf(end)
      val following = tokens.filter { it.start > end && !it.isComment() && !it.isBlank(text) }
      val repeated = following.takeWhile { it.token == TokenNumber.SEMICOLON && lines.lineOf(it.start) == line }
      val next = following.getOrNull(repeated.size)
      if (next == null || lines.lineOf(next.start) > line) return repeated.lastOrNull()?.start ?: end
    }
    return null
  }

  /**
   * Returns [sql] with each character that Kotlin counts as whitespace and PostgreSQL's lexer does not, such as a
   * non-breaking space, replaced by a space. PostgreSQL reads such a character as part of an identifier, which
   * would hide the keyword that starts a statement.
   *
   * Returns [sql] itself when the replacement would change a token other than an identifier, as when a non-breaking
   * space sits inside the tag of a dollar quote.
   */
  private fun withLexerWhitespace(sql: String): String {
    val replaced = String(CharArray(sql.length) { if (isOutsideLexerWhitespace(sql[it])) ' ' else sql[it] })
    if (replaced == sql) return sql
    val replacedTokens = scanLeadingTokens(replaced).toSet()
    val keepsTokens = scanLeadingTokens(sql).filter { it.token != TokenNumber.IDENTIFIER }.all { it in replacedTokens }
    return if (keepsTokens) replaced else sql
  }

  private fun isOutsideLexerWhitespace(character: Char): Boolean =
    character.isWhitespace() && character !in LEXER_WHITESPACE

  /** Returns [sql] with each placeholder and named parameter replaced by `0` and spaces, keeping every offset. */
  private fun rewriteForParser(sql: String): String {
    val characters = replaceParameterPlaceholdersKeepingOffsets(sql).toCharArray()
    val tokens = scanLeadingTokens(sql)
    for ((index, token) in tokens.withIndex()) {
      if (token.token != TokenNumber.COLON) continue
      val name = tokens.getOrNull(index + 1)?.takeIf { it.start == token.end && isParameterName(sql, it) } ?: continue
      characters.fill(' ', token.start, name.end)
      characters[token.start] = '0'
    }
    return String(characters)
  }

  /**
   * Walks the tokens after a query's `;` or before the first annotation. Each `--` comment that starts its line is
   * added to [pendingComments], and a block comment or `;` clears them. Whitespace tokens are skipped, and
   * [onOtherToken] receives the rest.
   */
  private fun collectComments(
    text: String,
    tokens: List<ScanToken>,
    pendingComments: MutableList<String>,
    onOtherToken: (ScanToken) -> Unit,
  ) {
    for (token in tokens) {
      when (token.token) {
        TokenNumber.LINE_COMMENT ->
          if (startsLine(text, token)) pendingComments.add(extractCommentText(text.substring(token.start, token.end)))
        TokenNumber.BLOCK_COMMENT, TokenNumber.SEMICOLON -> pendingComments.clear()
        else -> if (!token.isBlank(text)) onOtherToken(token)
      }
    }
  }

  /** Tells whether this is an identifier token made only of whitespace characters, such as a non-breaking space. */
  private fun ScanToken.isBlank(text: String): Boolean =
    token == TokenNumber.IDENTIFIER && (start until end).all { text[it].isWhitespace() }

  private fun ScanToken.isComment(): Boolean = token == TokenNumber.LINE_COMMENT || token == TokenNumber.BLOCK_COMMENT

  private fun buildQuery(
    annotation: QueryAnnotation,
    rawSql: String,
    comments: List<String>,
    sourceFile: String,
  ): ParsedQuery {
    val (sql, namedParameters) = convertNamedParameters(rawSql, scanLeadingTokens(rawSql))

    return ParsedQuery(
      name = annotation.name,
      command = resolveCommand(annotation.name, annotation.command, annotation.line),
      sql = sql,
      comments = comments,
      namedParameters = namedParameters,
      sourceLine = annotation.line,
      sourceFile = sourceFile,
    )
  }

  /**
   * Resolves the text following the colon in a `-- name:` annotation (for example `one` from
   * `:one`) to a [Command].
   *
   * @throws IllegalArgumentException if [rawCommand] doesn't match any [Command]'s SQL form.
   */
  private fun resolveCommand(name: String, rawCommand: String, sourceLine: Int): Command {
    val annotation = ":$rawCommand"
    return try {
      Command.fromSql(annotation)
    } catch (exception: IllegalArgumentException) {
      throw IllegalArgumentException(
        "Query '$name' at line $sourceLine has an unrecognized command annotation '$annotation'.",
        exception,
      )
    }
  }

  /**
   * Converts `:paramName` named parameters to `?` positional placeholders.
   *
   * Finds parameters in the tokens of PostgreSQL's lexer, so a `:name`-shaped run inside a string literal,
   * quoted identifier, dollar-quoted string, or comment is copied through verbatim, as is text from the point
   * where the lexer rejects [sql] onward. A named parameter is a `:` token immediately followed by an identifier
   * or keyword token that does not start with `"`; the name is that token's text. The `::` and `:=` operators
   * are separate tokens and never start a parameter.
   *
   * Each occurrence of a named parameter produces its own `?` placeholder with its own 1-based
   * position number. The same name appearing multiple times creates multiple bind slots.
   *
   * The positional `?` written directly into the SQL are counted with [placeholderPositions], so the
   * mixed-style guard can tell a genuine positional `?` apart from one inside a string literal like
   * `'really?'` or one half of pgjdbc's `??` escape.
   *
   * @param tokens The lexer tokens of [sql], from [scanLeadingTokens].
   * @return A pair of (converted SQL, position-to-name map). If the SQL has no named parameters,
   *   returns the original SQL with an empty map.
   * @throws IllegalArgumentException if the query mixes `:name` and `?` parameter styles.
   */
  private fun convertNamedParameters(sql: String, tokens: List<ScanToken>): Pair<String, Map<Int, String>> {
    val numberToName = mutableMapOf<Int, String>()
    val result = StringBuilder()
    var copiedUpTo = 0

    for ((index, token) in tokens.withIndex()) {
      if (token.token != TokenNumber.COLON) continue
      val name = tokens.getOrNull(index + 1)?.takeIf { it.start == token.end && isParameterName(sql, it) } ?: continue
      result.append(sql, copiedUpTo, token.start).append('?')
      copiedUpTo = name.end
      numberToName[numberToName.size + 1] = sql.substring(name.start, name.end)
    }

    if (numberToName.isEmpty()) {
      return sql to emptyMap()
    }

    require(placeholderPositions(sql).isEmpty()) {
      "Cannot mix named (:param) and positional (?) parameters in the same query"
    }

    return result.append(sql, copiedUpTo, sql.length).toString() to numberToName
  }

  private fun isParameterName(sql: String, token: ScanToken): Boolean =
    (token.token == TokenNumber.IDENTIFIER || token.keywordKind != KeywordKind.NONE) && sql[token.start] != '"'

  private fun extractCommentText(commentLine: String): String = commentLine.removePrefix("--").trim()
}
