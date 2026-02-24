package norm.generator

/**
 * A query parsed from a SQL file.
 *
 * @param name Developer-assigned name from the `-- name:` annotation.
 * @param command The command type (`:one`, `:many`, `:exec`, `:execrows`).
 * @param sql The SQL text, with `$1`-style positional parameters preserved.
 * @param comments Comment lines preceding the query annotation, used for KDoc generation.
 */
public data class ParsedQuery(val name: String, val command: String, val sql: String, val comments: List<String>)

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
 */
public object QueryFileParser {

  private val NAME_ANNOTATION = Regex("""^--\s*name:\s*(\S+)\s+:(\S+)\s*$""")

  /**
   * Parses the content of a SQL file into a list of [ParsedQuery] instances.
   *
   * @param content The full text content of a SQL file.
   * @return Parsed queries in the order they appear in the file.
   * @throws IllegalArgumentException if a `-- name:` annotation has an invalid format.
   */
  public fun parse(content: String): List<ParsedQuery> {
    val lines = content.lines()
    val queries = mutableListOf<ParsedQuery>()

    // Accumulate comment lines that precede the next annotation
    val pendingComments = mutableListOf<String>()
    var currentName: String? = null
    var currentCommand: String? = null
    var currentComments = listOf<String>()
    val currentSqlLines = mutableListOf<String>()

    // Once the SQL body ends (line ending with `;`), we switch back to accumulating
    // pending comments for the next annotation.
    var sqlBodyComplete = false

    for (line in lines) {
      val trimmed = line.trim()
      val match = NAME_ANNOTATION.matchEntire(trimmed)
      if (match != null) {
        // Flush previous query if one exists
        if (currentName != null) {
          queries.add(buildQuery(currentName, currentCommand!!, currentSqlLines, currentComments))
          currentSqlLines.clear()
        }

        // Start new query
        currentName = match.groupValues[1]
        currentCommand = match.groupValues[2]
        currentComments = pendingComments.toList()
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
      queries.add(buildQuery(currentName, currentCommand!!, currentSqlLines, currentComments))
    }

    return queries
  }

  private fun buildQuery(name: String, command: String, sqlLines: List<String>, comments: List<String>): ParsedQuery {
    val sql = sqlLines
      .joinToString("\n")
      .trim()
      .removeSuffix(";")
      .trim()

    return ParsedQuery(
      name = name,
      command = ":$command",
      sql = sql,
      comments = comments,
    )
  }

  private fun extractCommentText(commentLine: String): String = commentLine.removePrefix("--").trim()
}
