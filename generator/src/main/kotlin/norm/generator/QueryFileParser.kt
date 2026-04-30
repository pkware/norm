package norm.generator

/**
 * A query parsed from a SQL file.
 *
 * @param name Developer-assigned name from the `-- name:` annotation.
 * @param command The command type (`:one`, `:many`, `:exec`, `:execrows`).
 * @param sql The SQL text, with `?` positional parameters. If the original SQL used `:name`-style named
 *   parameters, they have been converted to `?` form.
 * @param comments Comment lines preceding the query annotation, used for KDoc generation.
 * @param namedParameters Map from 1-based positional parameter number to the developer-chosen name. Empty when the
 *   query uses `?` positional parameters directly.
 * @param sourceLine 1-based line number of the `-- name:` annotation in the source file. `0` if unknown
 *   (for example, for synthesized CRUD queries).
 * @param sourceFile Path to the SQL file this query was parsed from. Empty string if unknown (for example,
 *   for synthesized CRUD queries).
 * @param isSynthesizedInsert `true` if this is a CRUD-generated INSERT query (from [CrudQuerySynthesizer.synthesizeInsert]),
 *   `false` for all other queries including hand-written and other synthesized CRUD queries.
 */
public data class ParsedQuery(
  val name: String,
  val command: String,
  val sql: String,
  val comments: List<String>,
  val namedParameters: Map<Int, String> = emptyMap(),
  val sourceLine: Int = 0,
  val sourceFile: String = "",
  val isSynthesizedInsert: Boolean = false,
)

/**
 * Parses SQL files containing annotated queries in the format:
 * ```sql
 * -- Optional comment
 * -- name: queryName :command
 * SELECT ...;
 * ```
 *
 * Each query is delimited by a `-- name:` annotation line. Comment lines immediately preceding
 * the annotation are captured as documentation. The SQL body continues until the next annotation
 * or end of file.
 *
 * ## Named Parameters
 *
 * Queries may use `:paramName` named parameters instead of `?` positional parameters:
 * ```sql
 * -- name: updateUser :exec
 * UPDATE users SET name = :name, age = :age WHERE id = :id;
 * ```
 *
 * Named parameters are converted to `?` placeholders in order of appearance. Each occurrence of
 * a named parameter produces its own `?` — the same name used multiple times creates multiple
 * bind slots. Mixing `:name` and `?` styles in a single query is not allowed.
 *
 * Named parameters inside single-quoted string literals are left untouched.
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
   * @throws IllegalArgumentException if a `-- name:` annotation has an invalid format, or if a
   *   query mixes named and positional parameter styles.
   */
  public fun parse(content: String, sourceFile: String = ""): List<ParsedQuery> {
    val lines = content.lines()
    val queries = mutableListOf<ParsedQuery>()

    // Accumulate comment lines that precede the next annotation
    val pendingComments = mutableListOf<String>()
    var currentName: String? = null
    var currentCommand: String? = null
    var currentComments = listOf<String>()
    var currentSourceLine = 0
    val currentSqlLines = mutableListOf<String>()

    // Once the SQL body ends (line ending with `;`), we switch back to accumulating
    // pending comments for the next annotation.
    var sqlBodyComplete = false

    for ((lineIndex, line) in lines.withIndex()) {
      val trimmed = line.trim()
      val match = NAME_ANNOTATION.matchEntire(trimmed)
      if (match != null) {
        // Flush previous query if one exists
        if (currentName != null) {
          queries.add(
            buildQuery(currentName, currentCommand!!, currentSqlLines, currentComments, currentSourceLine, sourceFile),
          )
          currentSqlLines.clear()
        }

        // Start new query
        currentName = match.groupValues[1]
        currentCommand = match.groupValues[2]
        currentComments = pendingComments.toList()
        currentSourceLine = lineIndex + 1
        pendingComments.clear()
        sqlBodyComplete = false
      } else if (currentName == null || sqlBodyComplete) {
        // Before any annotation, or between queries — accumulate comments
        if (trimmed.startsWith("--")) {
          pendingComments.add(extractCommentText(trimmed))
        } else if (trimmed.isNotBlank()) {
          // Non-comment, non-blank line — reset pending comments
          pendingComments.clear()
        }
      } else {
        // Inside a query body
        currentSqlLines.add(line)
        if (trimmed.endsWith(";")) {
          sqlBodyComplete = true
        }
      }
    }

    // Flush last query
    if (currentName != null) {
      queries.add(
        buildQuery(currentName, currentCommand!!, currentSqlLines, currentComments, currentSourceLine, sourceFile),
      )
    }

    return queries
  }

  private fun buildQuery(
    name: String,
    command: String,
    sqlLines: List<String>,
    comments: List<String>,
    sourceLine: Int,
    sourceFile: String,
  ): ParsedQuery {
    val rawSql = sqlLines
      .joinToString("\n")
      .trim()
      .removeSuffix(";")
      .trim()

    val (sql, namedParameters) = convertNamedParameters(rawSql)

    return ParsedQuery(
      name = name,
      command = ":$command",
      sql = sql,
      comments = comments,
      namedParameters = namedParameters,
      sourceLine = sourceLine,
      sourceFile = sourceFile,
    )
  }

  /**
   * Converts `:paramName` named parameters to `?` positional placeholders.
   *
   * Scans the SQL character by character to correctly handle:
   * - `::` cast operators (e.g., `value::integer`) — skipped
   * - Single-quoted string literals (e.g., `':notaparam'`) — skipped, including `''` escapes
   * - SQL comments (e.g., `-- comment`) — skipped
   *
   * Each occurrence of a named parameter produces its own `?` placeholder with its own 1-based
   * position number. The same name appearing multiple times creates multiple bind slots.
   *
   * @return A pair of (converted SQL, position-to-name map). If the SQL has no named parameters,
   *   returns the original SQL with an empty map.
   * @throws IllegalArgumentException if the query mixes `:name` and `?` parameter styles.
   */
  private fun convertNamedParameters(sql: String): Pair<String, Map<Int, String>> {
    val numberToName = mutableMapOf<Int, String>()
    var nextNumber = 1
    val result = StringBuilder()
    var i = 0

    while (i < sql.length) {
      val c = sql[i]

      if (c == '\'') {
        // Skip single-quoted string literals
        val closeIndex = findClosingQuote(sql, i)
        result.append(sql, i, closeIndex + 1)
        i = closeIndex + 1
      } else if (c == '-' && i + 1 < sql.length && sql[i + 1] == '-') {
        // Skip -- line comments
        val eol = sql.indexOf('\n', i)
        if (eol < 0) {
          result.append(sql, i, sql.length)
          i = sql.length
        } else {
          result.append(sql, i, eol)
          i = eol
        }
      } else if (c == ':' && i + 1 < sql.length && sql[i + 1] == ':') {
        // Double colon (cast) — pass through both characters
        result.append("::")
        i += 2
      } else if (c == ':' && i + 1 < sql.length && isIdentifierStart(sql[i + 1])) {
        // Named parameter — each occurrence gets its own ? placeholder
        val nameStart = i + 1
        var nameEnd = nameStart
        while (nameEnd < sql.length && isIdentifierPart(sql[nameEnd])) {
          nameEnd++
        }
        val paramName = sql.substring(nameStart, nameEnd)
        val position = nextNumber++
        numberToName[position] = paramName
        result.append('?')
        i = nameEnd
      } else {
        result.append(c)
        i++
      }
    }

    if (numberToName.isEmpty()) {
      return sql to emptyMap()
    }

    // Check for mixed styles: named params found, but ? positional params also present in the original SQL
    require('?' !in sql) {
      "Cannot mix named (:param) and positional (?) parameters in the same query"
    }

    return result.toString() to numberToName
  }

  /**
   * Finds the closing single quote for a string literal starting at [start].
   * Handles `''` escape sequences (two consecutive single quotes inside a literal).
   */
  private fun findClosingQuote(sql: String, start: Int): Int {
    var i = start + 1
    while (i < sql.length) {
      if (sql[i] == '\'') {
        // Check for '' escape
        if (i + 1 < sql.length && sql[i + 1] == '\'') {
          i += 2
          continue
        }
        return i
      }
      i++
    }
    // Unterminated string — return end of string
    return sql.length - 1
  }

  private fun isIdentifierStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_'

  private fun isIdentifierPart(c: Char): Boolean = isIdentifierStart(c) || c in '0'..'9'

  private fun extractCommentText(commentLine: String): String = commentLine.removePrefix("--").trim()
}
