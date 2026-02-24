package norm.generator

/** Matches `$1`, `$2`, etc. — PostgreSQL positional parameters. */
internal val POSITIONAL_PARAM = Regex("""\$\d+""")

/** Matches `func_name(` to find the start of function calls. */
internal val FUNCTION_CALL_START = Regex("""(\w+)\(""")

/** SQL keywords to exclude when matching function calls. */
internal val SQL_KEYWORDS = setOf(
  "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
  "DELETE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "ON",
  "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "EXCEPT",
  "INTERSECT", "AS", "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN",
  "CASE", "WHEN", "THEN", "ELSE", "END", "CAST", "IS", "LIKE", "ILIKE",
  "CALL", "DO", "WITH", "RETURNING", "CONFLICT",
)

/**
 * Splits text on a delimiter character, respecting nested parentheses.
 *
 * For `"EXISTS(...) AS valid, col1"` split on `,`, returns `["EXISTS(...) AS valid", "col1"]`.
 */
internal fun splitAtTopLevel(text: String, delimiter: Char): List<String> {
  val items = mutableListOf<String>()
  var depth = 0
  var start = 0
  for ((i, ch) in text.withIndex()) {
    when (ch) {
      '(' -> depth++
      ')' -> depth--
      delimiter -> if (depth == 0) {
        items.add(text.substring(start, i))
        start = i + 1
      }
    }
  }
  items.add(text.substring(start))
  return items
}
