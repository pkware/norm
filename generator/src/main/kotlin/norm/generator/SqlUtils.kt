package norm.generator

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
 * Finds the index of the closing parenthesis that matches an opening `(` at [openParenthesisIndex].
 *
 * @param text The string to search.
 * @param openParenthesisIndex The index of the opening `(`. The search starts at `openParenthesisIndex + 1`.
 * @return The index of the matching `)`, or `-1` if unbalanced.
 */
internal fun findMatchingCloseParenthesis(text: String, openParenthesisIndex: Int): Int {
  var depth = 1
  var i = openParenthesisIndex + 1
  while (i < text.length && depth > 0) {
    when (text[i]) {
      '(' -> depth++
      ')' -> depth--
    }
    i++
  }
  return if (depth == 0) i - 1 else -1
}

/**
 * Finds the best matching [FunctionOverload] for a call with [argCount] arguments.
 *
 * Prefers an exact match by argument count. Falls back to overloads with more arguments
 * (default parameters) or overloads with no named arguments (variadic/generic).
 */
internal fun findOverload(overloads: List<FunctionOverload>, argCount: Int): FunctionOverload? =
  overloads.find { it.argNames.size == argCount || it.argNames.isEmpty() }
    ?: overloads.find { it.argNames.size >= argCount }

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

/**
 * A parsed item from a SQL SELECT clause.
 *
 * @property expression The full SQL expression text (e.g. `COUNT(*)`, `author.name`, `book.title`).
 * @property columnName The original column name for simple column references. `null` for computed expressions.
 * @property tableName The table qualifier for qualified column references (e.g. `author` in `author.name`).
 *   `null` for unqualified references and computed expressions.
 */
internal data class SelectItem(
  val expression: String,
  val columnName: String?,
  val tableName: String?,
)

/**
 * Parses the SELECT clause of a SQL statement to extract individual select items.
 *
 * Handles:
 * - Simple columns: `title` → expression=`title`, columnName=`title`
 * - Qualified columns: `book.title` → expression=`book.title`, columnName=`title`, tableName=`book`
 * - Aliased columns: `author.name AS author_name` → expression=`author.name`, columnName=`name`, tableName=`author`
 * - Computed expressions: `COUNT(*) AS book_count` → expression=`COUNT(*)`, columnName=`null`
 * - Star projections: `*` → expression=`*`, columnName=`null`
 *
 * @return A list of [SelectItem]s in the order they appear, or an empty list if the SELECT clause cannot be parsed.
 */
internal fun parseSelectItems(sql: String): List<SelectItem> {
  val selectIndex = sql.indexOf("SELECT", ignoreCase = true)
  if (selectIndex < 0) return emptyList()

  val afterSelect = selectIndex + "SELECT".length
  val fromIndex = findTopLevelKeyword(sql, "FROM", afterSelect)
  val selectClause = if (fromIndex >= 0) {
    sql.substring(afterSelect, fromIndex)
  } else {
    sql.substring(afterSelect)
  }

  return splitAtTopLevel(selectClause.trim(), ',').map { raw ->
    val item = raw.trim()
    val (expression, _) = extractAlias(item)
    parseColumnReference(expression)
  }
}

/**
 * Splits a select item into its expression and alias parts.
 *
 * Handles `expression AS alias` patterns, respecting parentheses so that
 * `CAST(x AS text) AS my_col` correctly identifies `my_col` as the alias.
 *
 * @return A pair of (expression, alias) where alias is `null` if there is no `AS` keyword.
 */
private fun extractAlias(item: String): Pair<String, String?> {
  // Find the last top-level AS keyword
  var depth = 0
  var lastAsIndex = -1
  var i = 0
  while (i < item.length) {
    when (item[i]) {
      '(' -> depth++
      ')' -> depth--
      'A', 'a' -> if (depth == 0 && i + 1 < item.length && (item[i + 1] == 'S' || item[i + 1] == 's')) {
        // Check it's the keyword AS (preceded and followed by whitespace)
        val before = i == 0 || item[i - 1].isWhitespace()
        val after = i + 2 >= item.length || item[i + 2].isWhitespace()
        if (before && after) {
          lastAsIndex = i
        }
      }
    }
    i++
  }
  return if (lastAsIndex >= 0) {
    item.substring(0, lastAsIndex).trim() to item.substring(lastAsIndex + 2).trim()
  } else {
    item to null
  }
}

/**
 * Parses a SQL expression into a [SelectItem], determining whether it's a simple
 * column reference (possibly qualified with a table name) or a computed expression.
 */
private fun parseColumnReference(expression: String): SelectItem {
  val trimmed = expression.trim()
  // A simple column reference is one or two identifiers separated by a dot, with no parentheses or operators
  val match = COLUMN_REFERENCE.matchEntire(trimmed)
  return if (match != null) {
    val table = match.groups["table"]?.value
    val column = match.groups["column"]!!.value
    SelectItem(expression = trimmed, columnName = column, tableName = table)
  } else {
    SelectItem(expression = trimmed, columnName = null, tableName = null)
  }
}

/**
 * Finds a SQL keyword at the top level (not inside parentheses) in the given string.
 *
 * @return The index of the keyword, or `-1` if not found at the top level.
 */
private fun findTopLevelKeyword(sql: String, keyword: String, startIndex: Int): Int {
  var depth = 0
  var i = startIndex
  while (i <= sql.length - keyword.length) {
    when (sql[i]) {
      '(' -> { depth++; i++ }
      ')' -> { depth--; i++ }
      else -> {
        if (depth == 0 && sql.regionMatches(i, keyword, 0, keyword.length, ignoreCase = true)) {
          val before = i == 0 || !sql[i - 1].isLetterOrDigit()
          val after = i + keyword.length >= sql.length || !sql[i + keyword.length].isLetterOrDigit()
          if (before && after) return i
        }
        i++
      }
    }
  }
  return -1
}

/** Matches `table.column` or just `column` (identifier characters only, no parentheses or operators). */
private val COLUMN_REFERENCE = Regex("""(?:(?<table>\w+)\.)?(?<column>\w+)""")
