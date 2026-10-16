package norm.generator

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
 * Each query starts at a `-- name:` annotation line. Comment lines immediately preceding
 * the annotation are captured as documentation.
 *
 * The body ends at the first top-level `;` followed by only whitespace and comments up to a line break. A
 * block comment can carry that line break onto a later line. Top level
 * excludes string literals, quoted identifiers, dollar-quoted strings, comments, and parentheses. It also
 * excludes the `BEGIN ... END` part of a `CREATE FUNCTION` or `CREATE PROCEDURE`.
 *
 * A body with no such `;` ends before the final `;` of the first `--` comment that ends in one.
 * A body with neither ends at the next annotation line or end of file.
 *
 * A string, quoted identifier, dollar-quoted string, or block comment that never closes makes the body end
 * at the first line ending in `;`, or at the next annotation line.
 *
 * Text between queries is read one line at a time. A line of SQL after the first query is an error.
 * Blank lines and comments are not SQL. A line holding only `;` characters and a trailing comment is not SQL
 * either. Lines inside a block comment and lines containing `/*` or `*/` are exempt. Block comments nest,
 * as in PostgreSQL. An annotation inside a block comment that opens on a line after the body turns the error
 * off for the rest of the file. So does a body ending in a token that never closes. A block comment opening on
 * the terminator's line belongs to that line, so annotations inside it are ignored.
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

  private val NAME_ANNOTATION = Regex("""^--\s*name:\s*(\S+)\s+:(\S+)\s*$""")

  /**
   * Parses the content of a SQL file into a list of [ParsedQuery] instances.
   *
   * @param content The full text content of a SQL file.
   * @param sourceFile Path to the SQL file being parsed, stored on each [ParsedQuery] for diagnostics.
   *   Empty string if the source path is unknown.
   * @return Parsed queries in the order they appear in the file.
   * @throws IllegalArgumentException if a `-- name:` annotation has an invalid format, names a
   *   command that doesn't match any [Command]'s SQL form (for example `:foo`), if a query
   *   mixes named and positional parameter styles, or if a line of SQL between queries lies outside
   *   every block comment, as described above.
   */
  public fun parse(content: String, sourceFile: String = ""): List<ParsedQuery> {
    val source = SourceText(content.lines())
    val queries = mutableListOf<ParsedQuery>()
    val pendingComments = mutableListOf<String>()
    var previous: QueryHeader? = null
    var blockCommentDepth = 0
    var isStrayTextAllowed = false
    var lineIndex = 0

    while (lineIndex < source.lines.size) {
      val trimmed = source.lines[lineIndex].trim()
      val annotation = NAME_ANNOTATION.matchEntire(trimmed)
      if (annotation != null) {
        val header = QueryHeader(
          name = annotation.groupValues[1],
          command = annotation.groupValues[2],
          comments = pendingComments.toList(),
          sourceLine = lineIndex + 1,
        )
        pendingComments.clear()
        if (blockCommentDepth > 0) isStrayTextAllowed = true
        val bodyStart = source.lineStart(lineIndex + 1)
        val end = findBodyEnd(source, lineIndex)
        queries.add(buildQuery(header, source.text.substring(bodyStart, end.bodyEnd), sourceFile))
        previous = header
        if (end.endsInOpenToken) isStrayTextAllowed = true
        lineIndex = end.nextLine
        continue
      }

      val depthBefore = blockCommentDepth
      blockCommentDepth = blockCommentDepthAfter(source.lines[lineIndex], blockCommentDepth)
      if (trimmed.startsWith("--")) {
        pendingComments.add(extractCommentText(trimmed))
      } else if (trimmed.isNotEmpty()) {
        pendingComments.clear()
        val mayBeComment = depthBefore > 0 || "/*" in trimmed || "*/" in trimmed
        if (previous != null && !mayBeComment && !isStrayTextAllowed && !isSemicolonsAndComment(trimmed)) {
          throw IllegalArgumentException(unexpectedTextMessage(previous, lineIndex + 1, trimmed))
        }
      }
      lineIndex++
    }

    return queries
  }

  /**
   * Returns the PostgreSQL block-comment nesting depth after [line], starting from [depth]. A `--` comment
   * outside a block comment hides the rest of the line.
   */
  private fun blockCommentDepthAfter(line: String, depth: Int): Int {
    var current = depth
    var index = 0
    while (index < line.length) {
      if (current == 0 && line.startsWith("--", index)) break
      if (line.startsWith("/*", index)) {
        current++
        index += 2
      } else if (current > 0 && line.startsWith("*/", index)) {
        current--
        index += 2
      } else {
        index++
      }
    }
    return current
  }

  private fun isSemicolonsAndComment(trimmed: String): Boolean {
    val rest = trimmed.trimStart { it == ';' || it.isWhitespace() }
    return rest.isEmpty() || rest.startsWith("--")
  }

  private fun unexpectedTextMessage(previous: QueryHeader, lineNumber: Int, text: String): String =
    "Query '${previous.name}' at line ${previous.sourceLine} is followed by text on line $lineNumber " +
      "that is neither a comment nor a '-- name:' annotation: '$text'. End each query with a single ';'."

  /**
   * Finds where the body of the query annotated on [annotationLine] ends, following the rules in the
   * [QueryFileParser] KDoc. A token that never closes hands the decision to [findBodyEndByLines].
   */
  private fun findBodyEnd(source: SourceText, annotationLine: Int): BodyEnd {
    val cursor = SqlTokenCursor(source.text, source.lineStart(annotationLine + 1))
    val scan = BodyScan(source, annotationLine)
    while (true) {
      val span = cursor.advance() ?: break
      val end = scan.accept(span)
      if (end != null) return end
    }
    return scan.endAtCommentCutOr(BodyEnd(source.text.length, source.lines.size))
  }

  private class BodyScan(private val source: SourceText, private val annotationLine: Int) {
    private val text = source.text
    private val tracker = RoutineBodyTracker()
    private var commentCut = -1

    /** Returns where the body ends if [span] ends it, or `null` if the body continues. */
    fun accept(span: SqlSpan): BodyEnd? = when (span) {
      is SqlSpan.Word -> {
        tracker.onWord(text.substring(span.from, span.to))
        null
      }
      is SqlSpan.Char -> acceptCharacter(span.index)
      is SqlSpan.Opaque -> {
        tracker.onOpaque(span.isComment)
        if (span.isComment && text[span.from] == '-') acceptLineComment(span) else acceptToken(span)
      }
    }

    fun endAtCommentCutOr(fallback: BodyEnd): BodyEnd =
      if (commentCut >= 0) BodyEnd(commentCut, source.lineIndexOf(commentCut) + 1) else fallback

    private fun acceptCharacter(index: Int): BodyEnd? {
      if (!tracker.onCharacter(text[index])) return null
      val lineEnd = endOfTrailingComments(text, index + 1) ?: return null
      return BodyEnd(index, source.lineIndexOf(lineEnd) + 1)
    }

    private fun acceptToken(span: SqlSpan.Opaque): BodyEnd? = if (isUnterminated(text, span.from, span.to)) {
      findBodyEndByLines(source, annotationLine).copy(endsInOpenToken = true)
    } else {
      null
    }

    private fun acceptLineComment(span: SqlSpan.Opaque): BodyEnd? {
      val comment = text.substring(span.from, span.to).trimEnd()
      val lineIndex = source.lineIndexOf(span.from)
      val startsLine = text.substring(source.lineStart(lineIndex), span.from).isBlank()
      if (startsLine && NAME_ANNOTATION.matches(comment.trim())) {
        return endAtCommentCutOr(BodyEnd(source.lineStart(lineIndex), lineIndex))
      }
      if (commentCut < 0 && comment.endsWith(";")) commentCut = span.from + comment.length - 1
      return null
    }
  }

  /**
   * Ends a body at the first line whose trimmed text ends in `;`, dropping that `;`, or before the
   * next annotation line.
   */
  private fun findBodyEndByLines(source: SourceText, annotationLine: Int): BodyEnd {
    for (lineIndex in annotationLine + 1 until source.lines.size) {
      val line = source.lines[lineIndex]
      if (NAME_ANNOTATION.matches(line.trim())) return BodyEnd(source.lineStart(lineIndex), lineIndex)
      if (line.trim().endsWith(";")) {
        return BodyEnd(source.lineStart(lineIndex) + line.trimEnd().length - 1, lineIndex + 1)
      }
    }
    return BodyEnd(source.text.length, source.lines.size)
  }

  /**
   * Skips whitespace and closed comments from [start] and returns the index of the next line break or end
   * of [text]. A block comment can carry the scan onto later lines. Returns `null` if anything else comes
   * first.
   */
  private fun endOfTrailingComments(text: String, start: Int): Int? {
    var index = start
    while (index < text.length && text[index] != '\n') {
      if (text.startsWith("--", index)) return text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
      if (text.startsWith("/*", index)) {
        val afterComment = skipBlockComment(text, index)
        if (isUnterminated(text, index, afterComment)) return null
        index = afterComment
      } else if (text[index].isWhitespace()) {
        index++
      } else {
        return null
      }
    }
    return index
  }

  /**
   * Returns `true` if the string, quoted identifier, dollar quote, or block comment that [skipLexicalToken]
   * reads from [from] to [to] runs to the end of [text] without closing. Appending a line break moves the end
   * of such a token and leaves a closed one in place.
   */
  private fun isUnterminated(text: String, from: Int, to: Int): Boolean =
    to == text.length && skipLexicalToken(text + "\n", from) != to

  private data class BodyEnd(val bodyEnd: Int, val nextLine: Int, val endsInOpenToken: Boolean = false)

  private class SourceText(val lines: List<String>) {
    val text: String = lines.joinToString("\n")
    private val lineStarts = IntArray(lines.size)

    init {
      var offset = 0
      for ((index, line) in lines.withIndex()) {
        lineStarts[index] = offset
        offset += line.length + 1
      }
    }

    fun lineStart(lineIndex: Int): Int = if (lineIndex < lines.size) lineStarts[lineIndex] else text.length

    fun lineIndexOf(offset: Int): Int {
      val found = lineStarts.binarySearch(offset)
      return if (found >= 0) found else -found - 2
    }
  }

  /**
   * Follows `BEGIN ... END` nesting in a `CREATE [OR REPLACE] FUNCTION|PROCEDURE` statement, so a `;`
   * inside a SQL-standard function body (`BEGIN ATOMIC ... END`) does not end the query.
   *
   * The statement's first four identifiers decide whether it is a routine definition, as in
   * `psqlscan_track_identifier` in PostgreSQL's `psqlscan.l`. Identifiers inside parentheses are ignored.
   * `BEGIN` opens a nesting level only when `ATOMIC` follows it, matching the `opt_routine_body` rule in
   * `gram.y`. A bare `begin` can name a function or a table.
   * `END` closes a level. `CASE` counts as `BEGIN ATOMIC` only inside an open level.
   */
  private class RoutineBodyTracker {
    private val initialIdentifiers = CharArray(INITIAL_IDENTIFIER_COUNT)
    private var identifierCount = 0
    private var parenthesisDepth = 0
    private var beginDepth = 0
    private var isAfterBegin = false

    /** Returns `true` if [character] is a `;` outside parentheses and `BEGIN ... END` levels. */
    fun onCharacter(character: Char): Boolean {
      if (!character.isWhitespace()) isAfterBegin = false
      if (character == '(') {
        parenthesisDepth++
      } else if (character == ')' && parenthesisDepth > 0) {
        parenthesisDepth--
      }
      return character == ';' && parenthesisDepth == 0 && beginDepth == 0
    }

    /**
     * Records a string, quoted identifier, dollar quote, or comment. Only a comment may sit between `BEGIN`
     * and `ATOMIC`.
     */
    fun onOpaque(isComment: Boolean) {
      if (!isComment) isAfterBegin = false
    }

    fun onWord(word: String) {
      val followsBegin = isAfterBegin
      isAfterBegin = false
      if (parenthesisDepth != 0 || !isIdentifierStartChar(word[0])) return
      if (identifierCount < INITIAL_IDENTIFIER_COUNT) {
        if (word.lowercase() in INITIAL_KEYWORDS) initialIdentifiers[identifierCount] = word[0].lowercaseChar()
        identifierCount++
      }
      if (!isRoutineDefinition()) return
      when (word.lowercase()) {
        "begin" -> isAfterBegin = true
        "atomic" -> if (followsBegin) beginDepth++
        "case" -> if (beginDepth >= 1) beginDepth++
        "end" -> if (beginDepth > 0) beginDepth--
      }
    }

    private fun isRoutineDefinition(): Boolean {
      val (first, second, third, fourth) = initialIdentifiers
      return first == 'c' &&
        (second == 'f' || second == 'p' || (second == 'o' && third == 'r' && (fourth == 'f' || fourth == 'p')))
    }

    private companion object {
      const val INITIAL_IDENTIFIER_COUNT = 4
      val INITIAL_KEYWORDS = setOf("create", "function", "procedure", "or", "replace")
    }
  }

  private class QueryHeader(val name: String, val command: String, val comments: List<String>, val sourceLine: Int)

  private fun buildQuery(header: QueryHeader, body: String, sourceFile: String): ParsedQuery {
    val (sql, namedParameters) = convertNamedParameters(body.trim())

    return ParsedQuery(
      name = header.name,
      command = resolveCommand(header.name, header.command, header.sourceLine),
      sql = sql,
      comments = header.comments,
      namedParameters = namedParameters,
      sourceLine = header.sourceLine,
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
   * Scans the SQL one lexical token at a time via [skipLexicalToken], so a `:name`-shaped run
   * inside a string literal, quoted identifier, dollar-quoted string, or comment is copied through
   * verbatim. Outside those tokens:
   * - `::` cast operators (e.g., `value::integer`) are passed through unconverted.
   * - A `:` immediately followed by an ASCII `[A-Za-z_]` character (see [isNamedParameterStartCharacter])
   *   is a named parameter and becomes `?`; the name itself continues through
   *   [isNamedParameterCharacter].
   *
   * Each occurrence of a named parameter produces its own `?` placeholder with its own 1-based
   * position number. The same name appearing multiple times creates multiple bind slots.
   *
   * Every `?` encountered outside a lexical token — meaning it was written directly into the SQL,
   * not produced by this conversion — is counted so the mixed-style guard below can tell a
   * genuine positional `?` apart from one merely sitting inside a string literal like `'really?'`.
   * A `??` is pgjdbc's escape for a literal `?`, as in [placeholderPositions], and is not counted.
   *
   * @return A pair of (converted SQL, position-to-name map). If the SQL has no named parameters,
   *   returns the original SQL with an empty map.
   * @throws IllegalArgumentException if the query mixes `:name` and `?` parameter styles.
   */
  private fun convertNamedParameters(sql: String): Pair<String, Map<Int, String>> {
    val numberToName = mutableMapOf<Int, String>()
    var nextNumber = 1
    var positionalParameterCount = 0
    val result = StringBuilder()
    var index = 0

    while (index < sql.length) {
      val afterToken = skipLexicalToken(sql, index)
      if (afterToken != index) {
        result.append(sql, index, afterToken)
        index = afterToken
        continue
      }
      val character = sql[index]
      if (isEscapedQuestionMark(sql, index)) {
        // pgjdbc's `??` escape is one literal `?`, not a positional placeholder.
        result.append("??")
        index += 2
      } else if (character == ':' && index + 1 < sql.length && sql[index + 1] == ':') {
        // Double colon (cast) — pass through both characters
        result.append("::")
        index += 2
      } else if (character == ':' && index + 1 < sql.length && isNamedParameterStartCharacter(sql[index + 1])) {
        // Named parameter — each occurrence gets its own ? placeholder
        val nameStart = index + 1
        var nameEnd = nameStart
        while (nameEnd < sql.length && isNamedParameterCharacter(sql[nameEnd])) {
          nameEnd++
        }
        val paramName = sql.substring(nameStart, nameEnd)
        val position = nextNumber++
        numberToName[position] = paramName
        result.append('?')
        index = nameEnd
      } else {
        if (character == '?') positionalParameterCount++
        result.append(character)
        index++
      }
    }

    if (numberToName.isEmpty()) {
      return sql to emptyMap()
    }

    // Check for mixed styles: named params found, but a genuine positional ? also appears outside
    // any lexical token — a ? inside a string literal like 'really?' does not count (see
    // positionalParameterCount's accumulation above).
    require(positionalParameterCount == 0) {
      "Cannot mix named (:param) and positional (?) parameters in the same query"
    }

    return result.toString() to numberToName
  }

  private fun isNamedParameterStartCharacter(character: Char): Boolean =
    character in 'a'..'z' || character in 'A'..'Z' || character == '_'

  private fun isNamedParameterCharacter(character: Char): Boolean =
    isNamedParameterStartCharacter(character) || character in '0'..'9'

  private fun extractCommentText(commentLine: String): String = commentLine.removePrefix("--").trim()
}
