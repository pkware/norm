package norm.generator

import norm.generator.libpgquery.KeywordKind
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
 * Each query is delimited by a `-- name:` annotation line. Comment lines immediately preceding
 * the annotation are captured as documentation. The SQL body continues until the next annotation
 * or end of file.
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
   *   command that doesn't match any [Command]'s SQL form (for example `:foo`), or if a query
   *   mixes named and positional parameter styles, or if a query's SQL contains a NUL character.
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

    val (sql, namedParameters) = convertNamedParameters(rawSql, scanQuery(name, rawSql, sourceLine))

    return ParsedQuery(
      name = name,
      command = resolveCommand(name, command, sourceLine),
      sql = sql,
      comments = comments,
      namedParameters = namedParameters,
      sourceLine = sourceLine,
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

  /**
   * Splits [sql] into lexer tokens for [convertNamedParameters].
   *
   * @throws IllegalArgumentException if [sql] contains a NUL character, naming the query at [sourceLine].
   */
  private fun scanQuery(name: String, sql: String, sourceLine: Int): List<ScanToken> = try {
    scanLeadingTokens(sql)
  } catch (exception: IllegalArgumentException) {
    throw IllegalArgumentException("Query '$name' at line $sourceLine contains a NUL character.", exception)
  }

  private fun extractCommentText(commentLine: String): String = commentLine.removePrefix("--").trim()
}
