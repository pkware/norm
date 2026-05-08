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
internal data class SelectItem(val expression: String, val columnName: String?, val tableName: String?)

/**
 * Parses the output clause of a SQL statement to extract individual items.
 *
 * Checks for a SELECT clause first, then falls back to a RETURNING clause for DML statements
 * (INSERT, UPDATE, DELETE). This ensures aliased columns in RETURNING clauses are resolved to
 * their original column names, which is needed for column-level type mapping lookups.
 *
 * Handles:
 * - Simple columns: `title` → expression=`title`, columnName=`title`
 * - Qualified columns: `book.title` → expression=`book.title`, columnName=`title`, tableName=`book`
 * - Aliased columns: `author.name AS author_name` → expression=`author.name`, columnName=`name`, tableName=`author`
 * - Computed expressions: `COUNT(*) AS book_count` → expression=`COUNT(*)`, columnName=`null`
 * - Star projections: `*` → expression=`*`, columnName=`null`
 *
 * @return A list of [SelectItem]s in the order they appear, or an empty list if neither a SELECT
 *   nor a RETURNING clause can be parsed.
 */
internal fun parseSelectItems(sql: String): List<SelectItem> {
  val selectIndex = sql.indexOf("SELECT", ignoreCase = true)
  val afterKeyword: Int
  val hasFromClause: Boolean
  if (selectIndex >= 0) {
    afterKeyword = selectIndex + "SELECT".length
    hasFromClause = true
  } else {
    val returningIndex = sql.indexOf("RETURNING", ignoreCase = true)
    if (returningIndex < 0) return emptyList()
    afterKeyword = returningIndex + "RETURNING".length
    // RETURNING clauses are terminal — no FROM keyword follows
    hasFromClause = false
  }

  val fromIndex = if (hasFromClause) findTopLevelKeyword(sql, "FROM", afterKeyword) else -1
  val rawClause = if (fromIndex >= 0) {
    sql.substring(afterKeyword, fromIndex)
  } else {
    sql.substring(afterKeyword)
  }

  return splitAtTopLevel(rawClause.trim().trimEnd(';'), ',').map { raw ->
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
internal fun findTopLevelKeyword(sql: String, keyword: String, startIndex: Int = 0): Int {
  var depth = 0
  var i = startIndex
  while (i <= sql.length - keyword.length) {
    when (sql[i]) {
      '(' -> {
        depth++
        i++
      }
      ')' -> {
        depth--
        i++
      }
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

/**
 * A parsed CTE definition from a `WITH` clause.
 *
 * @property name The CTE name.
 * @property bodyOpenParenthesis Index of `(` that opens the CTE body in the original SQL.
 * @property bodyCloseParenthesis Index of `)` that closes the CTE body.
 */
internal data class CteDefinition(val name: String, val bodyOpenParenthesis: Int, val bodyCloseParenthesis: Int)

/**
 * Result of parsing a SQL `WITH` clause.
 *
 * @property definitions The CTEs in declaration order.
 * @property mainQueryStart Index in the original SQL where the main query (after all CTEs) begins.
 */
internal data class ParsedCteClause(val definitions: List<CteDefinition>, val mainQueryStart: Int)

/**
 * Parses CTE definitions from a SQL `WITH` clause.
 *
 * Extracts each CTE's name and the character indices of its body parentheses so callers
 * can replace CTE bodies by simple string splicing. Handles `WITH RECURSIVE`, optional
 * column lists (`name(col1, col2) AS (...)`), and `[NOT] MATERIALIZED` hints.
 *
 * Skips single-line (`--`) and block (`/* */`) comments between CTE definitions.
 *
 * @return The parsed CTE clause, or `null` if the SQL does not start with `WITH`
 *   (after leading whitespace and comments).
 */
internal fun parseCteClause(sql: String): ParsedCteClause? {
  val withIndex = findTopLevelKeyword(sql, "WITH")
  if (withIndex < 0) return null

  // Make sure WITH is before any DML/SELECT keyword (it's the leading WITH, not WITH inside a subquery)
  for (keyword in listOf("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE")) {
    val keywordIndex = findTopLevelKeyword(sql, keyword)
    if (keywordIndex in 0 until withIndex) return null
  }

  var position = withIndex + 4
  position = skipWhitespaceAndComments(sql, position)

  // Skip optional RECURSIVE keyword (word-boundary check avoids matching CTE names like "recursive_cte")
  position = skipOptionalKeyword(sql, position, "RECURSIVE")

  val definitions = mutableListOf<CteDefinition>()

  while (position < sql.length) {
    val parsed = parseSingleCteDefinition(sql, position) ?: break
    definitions.add(parsed.first)
    position = skipWhitespaceAndComments(sql, parsed.second)
    if (position >= sql.length || sql[position] != ',') break
    position++ // skip the comma
  }

  return if (definitions.isEmpty()) {
    null
  } else {
    ParsedCteClause(definitions, position)
  }
}

/**
 * Parses a single CTE definition starting at [position].
 *
 * Expected syntax: `name [(col_list)] AS [NOT MATERIALIZED | MATERIALIZED] (body)`
 *
 * @return A pair of the parsed [CteDefinition] and the position after the closing `)`,
 *   or `null` if parsing fails at any step.
 */
private fun parseSingleCteDefinition(sql: String, startPosition: Int): Pair<CteDefinition, Int>? {
  var position = skipWhitespaceAndComments(sql, startPosition)

  // Read CTE name (identifier: word chars or double-quoted)
  val nameStart = position
  if (position < sql.length && sql[position] == '"') {
    position++ // skip opening quote
    while (position < sql.length && sql[position] != '"') position++
    if (position < sql.length) position++ // skip closing quote
  } else {
    while (position < sql.length && (sql[position].isLetterOrDigit() || sql[position] == '_')) position++
  }
  if (position == nameStart) return null
  val name = sql.substring(nameStart, position).trim('"')

  position = skipWhitespaceAndComments(sql, position)

  // Skip optional column list: name(col1, col2)
  if (position < sql.length && sql[position] == '(') {
    val closeParenthesis = findMatchingCloseParenthesis(sql, position)
    if (closeParenthesis < 0) return null
    position = closeParenthesis + 1
    position = skipWhitespaceAndComments(sql, position)
  }

  // Expect AS
  if (!sql.regionMatches(position, "AS", 0, 2, ignoreCase = true)) return null
  position += 2
  position = skipWhitespaceAndComments(sql, position)

  // Skip optional NOT MATERIALIZED or MATERIALIZED
  position = skipOptionalKeyword(sql, position, "NOT")
  position = skipOptionalKeyword(sql, position, "MATERIALIZED")

  // Expect ( — start of CTE body
  if (position >= sql.length || sql[position] != '(') return null
  val bodyOpen = position
  val bodyClose = findMatchingCloseParenthesis(sql, position)
  if (bodyClose < 0) return null

  return CteDefinition(name, bodyOpen, bodyClose) to (bodyClose + 1)
}

/**
 * Builds a SELECT statement equivalent to an `UPDATE ... FROM ... RETURNING` or
 * `DELETE ... USING ... RETURNING` statement, preserving the join structure so that
 * outer join nullability in the RETURNING columns is detectable via view analysis.
 *
 * For `UPDATE target SET ... FROM from_clause WHERE where_clause RETURNING cols`:
 * produces `SELECT cols FROM target, from_clause WHERE where_clause`.
 *
 * For `DELETE FROM target USING using_clause WHERE where_clause RETURNING cols`:
 * produces `SELECT cols FROM target, using_clause WHERE where_clause`.
 *
 * For DML without a FROM/USING clause (no join structure), or for INSERT/MERGE
 * (where RETURNING can only reference the target table), returns `null` — the caller
 * should use an empty nullability list since no outer joins are possible.
 *
 * @param sql The DML statement (without any CTE prefix).
 * @return An equivalent SELECT preserving the join structure, or `null` if the DML
 *   has no join structure that could affect RETURNING column nullability.
 */
internal fun convertDmlToSelect(sql: String): String? {
  val trimmedStart = skipWhitespaceAndComments(sql, 0)

  // UPDATE target SET ... [FROM ...] [WHERE ...] RETURNING ...
  if (sql.regionMatches(trimmedStart, "UPDATE", 0, 6, ignoreCase = true)) {
    val setIndex = findTopLevelKeyword(sql, "SET", trimmedStart + 6)
    if (setIndex < 0) return null
    val target = sql.substring(trimmedStart + 6, setIndex).trim()

    val fromIndex = findTopLevelKeyword(sql, "FROM", setIndex)
    if (fromIndex < 0) return null // No FROM clause → no join structure

    val returningIndex = findTopLevelKeyword(sql, "RETURNING", setIndex)
    if (returningIndex < 0) return null // No RETURNING → no result columns

    return buildSelectFromDml(sql, target, fromIndex + 4, returningIndex)
  }

  // DELETE FROM target [USING ...] [WHERE ...] RETURNING ...
  if (sql.regionMatches(trimmedStart, "DELETE", 0, 6, ignoreCase = true)) {
    val fromIndex = findTopLevelKeyword(sql, "FROM", trimmedStart + 6)
    if (fromIndex < 0) return null

    val usingIndex = findTopLevelKeyword(sql, "USING", fromIndex)
    if (usingIndex < 0) return null // No USING clause → no join structure

    val returningIndex = findTopLevelKeyword(sql, "RETURNING", fromIndex)
    if (returningIndex < 0) return null

    val target = sql.substring(fromIndex + 4, usingIndex).trim()
    return buildSelectFromDml(sql, target, usingIndex + 5, returningIndex)
  }

  // INSERT, MERGE, or other DML: no join structure in RETURNING
  return null
}

/**
 * Builds `SELECT <returning> FROM <target>, <joinClause> [WHERE <where>]` from the components
 * of an UPDATE FROM or DELETE USING statement.
 *
 * @param sql The full DML statement.
 * @param target The target table expression.
 * @param joinClauseStart Index in [sql] where the join clause text begins (after FROM/USING keyword).
 * @param returningIndex Index in [sql] where the RETURNING keyword starts.
 */
private fun buildSelectFromDml(sql: String, target: String, joinClauseStart: Int, returningIndex: Int): String {
  val whereIndex = findTopLevelKeyword(sql, "WHERE", joinClauseStart)

  val fromClause: String
  val whereClause: String?
  if (whereIndex in 0 until returningIndex) {
    fromClause = sql.substring(joinClauseStart, whereIndex).trim()
    whereClause = sql.substring(whereIndex + 5, returningIndex).trim()
  } else {
    fromClause = sql.substring(joinClauseStart, returningIndex).trim()
    whereClause = null
  }
  val returningClause = sql.substring(returningIndex + 9).trimEnd().trimEnd(';')

  return buildString {
    append("SELECT ").append(returningClause)
    append(" FROM ").append(target).append(", ").append(fromClause)
    if (whereClause != null) append(" WHERE ").append(whereClause)
  }
}

/**
 * If [keyword] appears at [position] as a complete word (followed by whitespace or end of string),
 * advances past it and any trailing whitespace/comments. Otherwise returns [position] unchanged.
 */
private fun skipOptionalKeyword(sql: String, position: Int, keyword: String): Int {
  if (!sql.regionMatches(position, keyword, 0, keyword.length, ignoreCase = true)) return position
  val afterKeyword = position + keyword.length
  if (afterKeyword < sql.length && !sql[afterKeyword].isWhitespace()) return position
  return skipWhitespaceAndComments(sql, afterKeyword)
}

/**
 * Advances past whitespace, single-line comments (`--`), and block comments (`/* */`).
 *
 * @return The index of the first non-whitespace, non-comment character at or after [start].
 */
internal fun skipWhitespaceAndComments(sql: String, start: Int): Int {
  var i = start
  while (i < sql.length) {
    when {
      sql[i].isWhitespace() -> i++
      sql[i] == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
        val eol = sql.indexOf('\n', i)
        i = if (eol < 0) sql.length else eol + 1
      }
      sql[i] == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
        val close = sql.indexOf("*/", i + 2)
        i = if (close < 0) sql.length else close + 2
      }
      else -> return i
    }
  }
  return i
}

/**
 * Replaces `?` parameter placeholders with `NULL` for use in view definitions.
 *
 * PostgreSQL views cannot contain parameter placeholders. This function replaces each `?` that
 * represents a parameter with `NULL`, which allows the query to be used as a view body for
 * node tree analysis. The replacement value does not affect nullability analysis — outer join
 * nullability is determined by join structure, not parameter values.
 *
 * Correctly skips `?` inside:
 * - Single-quoted string literals (`'really?'`), including `''` escape sequences
 * - Line comments (`-- why?`)
 * - Block comments (`/* what? */`)
 *
 * Note: Dollar-quoted string literals (`$$...$$`) are not handled. A `?` inside a dollar-quoted
 * string would be replaced with `NULL`. This is an acceptable limitation since dollar quoting is
 * only used in function bodies, not in WHERE clauses or other contexts where Norm analyzes queries.
 */
internal fun replaceParameterPlaceholders(sql: String): String {
  if ('?' !in sql) return sql
  // +20: NULL is 3 chars longer than ?, so +20 accommodates ~6 replacements before a reallocation.
  val result = StringBuilder(sql.length + 20)
  var i = 0
  while (i < sql.length) {
    when {
      sql[i] == '\'' -> {
        result.append('\'')
        i++
        while (i < sql.length) {
          if (sql[i] == '\'') {
            result.append('\'')
            i++
            if (i < sql.length && sql[i] == '\'') {
              result.append('\'')
              i++
            } else {
              break
            }
          } else {
            result.append(sql[i])
            i++
          }
        }
      }
      sql[i] == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
        val eol = sql.indexOf('\n', i)
        if (eol < 0) {
          result.append(sql, i, sql.length)
          i = sql.length
        } else {
          result.append(sql, i, eol + 1)
          i = eol + 1
        }
      }
      sql[i] == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
        val close = sql.indexOf("*/", i + 2)
        if (close < 0) {
          result.append(sql, i, sql.length)
          i = sql.length
        } else {
          result.append(sql, i, close + 2)
          i = close + 2
        }
      }
      sql[i] == '?' -> {
        result.append("NULL")
        i++
      }
      else -> {
        result.append(sql[i])
        i++
      }
    }
  }
  return result.toString()
}

/**
 * Replaces `?` parameter placeholders with typed non-null sentinel values.
 *
 * Each `?` is replaced with the corresponding sentinel from [sentinels] (consumed in order).
 * If there are more `?` placeholders than sentinels, excess `?` are replaced with `NULL`
 * (safe fallback). Question marks inside string literals and comments are left untouched.
 *
 * @param sql The SQL text with `?` parameter placeholders.
 * @param sentinels Non-null sentinel expressions in parameter order (e.g., `"0::int4"`, `"''::text"`).
 * @return The SQL with `?` replaced by sentinels.
 */
internal fun replaceParameterPlaceholdersWithSentinels(sql: String, sentinels: List<String>): String {
  if ('?' !in sql) return sql
  val result = StringBuilder(sql.length + sentinels.sumOf { it.length })
  var characterIndex = 0
  var sentinelIndex = 0
  while (characterIndex < sql.length) {
    when {
      sql[characterIndex] == '\'' -> {
        result.append('\'')
        characterIndex++
        while (characterIndex < sql.length) {
          if (sql[characterIndex] == '\'') {
            result.append('\'')
            characterIndex++
            if (characterIndex < sql.length && sql[characterIndex] == '\'') {
              result.append('\'')
              characterIndex++
            } else {
              break
            }
          } else {
            result.append(sql[characterIndex])
            characterIndex++
          }
        }
      }
      sql[characterIndex] == '-' && characterIndex + 1 < sql.length && sql[characterIndex + 1] == '-' -> {
        val endOfLine = sql.indexOf('\n', characterIndex)
        if (endOfLine < 0) {
          result.append(sql, characterIndex, sql.length)
          characterIndex = sql.length
        } else {
          result.append(sql, characterIndex, endOfLine + 1)
          characterIndex = endOfLine + 1
        }
      }
      sql[characterIndex] == '/' && characterIndex + 1 < sql.length && sql[characterIndex + 1] == '*' -> {
        val close = sql.indexOf("*/", characterIndex + 2)
        if (close < 0) {
          result.append(sql, characterIndex, sql.length)
          characterIndex = sql.length
        } else {
          result.append(sql, characterIndex, close + 2)
          characterIndex = close + 2
        }
      }
      sql[characterIndex] == '?' -> {
        result.append(sentinels.getOrElse(sentinelIndex) { "NULL" })
        sentinelIndex++
        characterIndex++
      }
      else -> {
        result.append(sql[characterIndex])
        characterIndex++
      }
    }
  }
  return result.toString()
}

/**
 * Returns a non-null SQL literal expression for the given PostgreSQL type name.
 *
 * Used to replace `?` parameter placeholders with typed non-null constants when creating
 * temporary views for nullability analysis. Strict functions like `digest(?, ?)` need non-null
 * inputs to correctly evaluate as non-null in the query's node tree.
 *
 * Falls back to `NULL::<typeName>` for unrecognized types, which is safe (produces a nullable
 * result — the conservative direction).
 *
 * @param typeName The PostgreSQL type name from `ParameterMetaData.getParameterTypeName()`.
 * @return A SQL expression that is a valid non-null literal of the given type.
 */
internal fun nonNullSentinel(typeName: String): String = when (typeName) {
  "int2", "int4", "int8", "float4", "float8", "numeric", "oid" -> "0::$typeName"
  "text", "varchar", "bpchar", "char", "name" -> "''::$typeName"
  "bool" -> "false::bool"
  "bytea" -> "'\\x00'::bytea"
  "date" -> "'2000-01-01'::date"
  "timestamp" -> "'2000-01-01'::timestamp"
  "timestamptz" -> "'2000-01-01'::timestamptz"
  "time" -> "'00:00:00'::time"
  "timetz" -> "'00:00:00'::timetz"
  "interval" -> "'0'::interval"
  "uuid" -> "'00000000-0000-0000-0000-000000000000'::uuid"
  "json" -> "'{}'::json"
  "jsonb" -> "'{}'::jsonb"
  "xml" -> "'<x/>'::xml"
  "inet", "cidr" -> "'0.0.0.0/0'::$typeName"
  "macaddr", "macaddr8" -> "'00:00:00:00:00:00'::$typeName"
  else -> if (typeName.startsWith("_")) {
    // Array types: _int4, _text, etc.
    "ARRAY[]::$typeName"
  } else {
    // Unknown type — fall back to NULL (safe: column becomes nullable).
    "NULL::$typeName"
  }
}
