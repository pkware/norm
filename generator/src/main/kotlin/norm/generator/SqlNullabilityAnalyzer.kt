package norm.generator

/**
 * Analyzes SQL SELECT clauses to determine which result column aliases are guaranteed non-null.
 *
 * The JDBC driver reports `columnNullableUnknown` for all computed expressions (EXISTS, COUNT,
 * function calls, crosstab columns, etc.). This class uses SQL pattern matching and PostgreSQL
 * function strictness metadata to identify expressions that can never produce `null`.
 *
 * @param functionOverloads Metadata about PostgreSQL functions from `pg_proc`, used to check
 *   whether functions are strict (return null only when given null input).
 */
internal class SqlNullabilityAnalyzer(private val functionOverloads: Map<String, List<FunctionOverload>>) {

  /**
   * Finds column aliases in the SELECT clause whose expressions are inherently non-null.
   *
   * An expression is non-null when:
   * 1. It starts with a known non-null SQL construct: `EXISTS(...)`, `COUNT(...)`, `COALESCE(...)`
   * 2. It is a call to a **strict** function (i.e., `pg_proc.proisstrict = true`) where all
   *    arguments are themselves non-null expressions. Strict functions return `null` only when
   *    given `null` input, so non-null inputs guarantee a non-null result. This is applied
   *    recursively for nested calls like `encode(digest(?, ?), ?)`.
   *
   * A `?` parameter is considered non-null when the corresponding Kotlin parameter is non-null
   * (as determined by [parameterNotNull]). This allows strict function calls with non-null
   * parameters to be correctly typed as non-null.
   *
   * This method extracts the top-level SELECT clause (handling nested subqueries in expressions
   * like `SELECT EXISTS(SELECT ...)`) and returns the set of aliases whose expressions match.
   *
   * @param sql The full SQL query text.
   * @param parameterNotNull Map from 1-based parameter number to whether the parameter is
   *   non-nullable. Parameters not in this map are assumed nullable.
   * @return Set of column aliases (after `AS`) whose expressions are provably non-null.
   */
  fun findNonNullAliases(sql: String, parameterNotNull: Map<Int, Boolean> = emptyMap()): Set<String> {
    val selectClause = extractTopLevelSelectClause(sql) ?: return emptySet()
    if (selectClause.trim() == "*") return emptySet()

    // Build a set of char positions for non-null ? parameters
    val nonNullParamPositions = buildNonNullParameterPositions(sql, parameterNotNull)

    val aliases = mutableSetOf<String>()
    // Track offset within the full SQL to correctly map ? positions.
    // selectClause is a substring of sql starting at selectClauseStart.
    val selectClauseStart = sql.indexOf(selectClause)
    var itemOffset = selectClauseStart
    for (item in splitAtTopLevel(selectClause, ',')) {
      val trimmed = item.trim()
      val asMatch = AS_ALIAS.find(trimmed)
      if (asMatch != null) {
        val alias = asMatch.groupValues[1]
        val expression = trimmed.substring(0, asMatch.range.first).trim()
        val leadingWhitespace = item.length - item.trimStart().length
        val expressionStart = itemOffset + leadingWhitespace
        if (isNonNullExpression(expression, nonNullParamPositions, expressionStart)) {
          aliases.add(alias)
        }
      }
      itemOffset += item.length + 1 // +1 for comma
    }
    return aliases
  }

  /**
   * Determines whether a SQL expression is guaranteed to produce a non-null result.
   *
   * Returns `true` for:
   * - Known non-null constructs: `EXISTS(...)`, `COUNT(...)`, `COALESCE(...)`
   * - `?` parameters whose corresponding Kotlin parameter is non-null
   * - Strict function calls where every argument is also a non-null expression (recursive)
   * - String literals (`'...'`) and numeric literals
   *
   * @param nonNullPositions Set of char positions in the full SQL where `?` is known non-null.
   * @param globalOffset The offset of [expression] within the full SQL string, used to look up
   *   `?` positions in [nonNullPositions].
   */
  private fun isNonNullExpression(expression: String, nonNullPositions: Set<Int>, globalOffset: Int): Boolean {
    val leadingWhitespace = expression.length - expression.trimStart().length
    val trimmed = expression.trim()
    val upper = trimmed.uppercase()

    if (NON_NULL_EXPRESSIONS.any { upper.startsWith(it) }) return true

    // Check if the expression is a single ? parameter that is non-null
    if (trimmed == "?") {
      return (globalOffset + leadingWhitespace) in nonNullPositions
    }

    if (trimmed.startsWith("'") && trimmed.endsWith("'")) return true
    if (trimmed.toDoubleOrNull() != null) return true

    // Check for strict function calls: func(arg1, arg2, ...)
    val functionMatch = FUNCTION_CALL_START.find(trimmed) ?: return false
    if (functionMatch.range.first != 0) return false // expression doesn't start with the function call
    val functionName = functionMatch.groupValues[1].lowercase()
    if (functionName.uppercase() in SQL_KEYWORDS) return false

    val overloads = functionOverloads[functionName] ?: return false

    // Extract the argument text inside the outermost parentheses
    val openParenthesis = functionMatch.range.last
    val closeParenthesis = findMatchingCloseParenthesis(trimmed, openParenthesis)
    if (closeParenthesis < 0) return false
    // Verify the function call spans the entire expression (no trailing text)
    if (closeParenthesis != trimmed.length - 1) return false

    val argumentsText = trimmed.substring(openParenthesis + 1, closeParenthesis)
    val args = splitAtTopLevel(argumentsText, ',')

    val overload = findOverload(overloads, args.size)

    // If function is strict and all arguments are non-null, the result is non-null.
    // Each argument's global offset is computed so ? positions can be looked up correctly.
    if (overload?.isStrict != true) return false
    var argOffset = globalOffset + leadingWhitespace + openParenthesis + 1
    for (arg in args) {
      if (!isNonNullExpression(arg, nonNullPositions, argOffset)) return false
      argOffset += arg.length + 1 // +1 for comma
    }
    return true
  }

  /**
   * Builds a set of char positions in [sql] where `?` placeholders correspond to non-null parameters.
   */
  private fun buildNonNullParameterPositions(sql: String, parameterNotNull: Map<Int, Boolean>): Set<Int> {
    if (parameterNotNull.isEmpty()) return emptySet()
    val positions = mutableSetOf<Int>()
    var paramNumber = 0
    for (i in sql.indices) {
      if (sql[i] == '?') {
        paramNumber++
        if (parameterNotNull[paramNumber] == true) {
          positions.add(i)
        }
      }
    }
    return positions
  }

  /**
   * Extracts the top-level SELECT clause from SQL, correctly handling nested subqueries.
   *
   * For `SELECT EXISTS(SELECT 1 FROM t) AS valid`, returns `"EXISTS(SELECT 1 FROM t) AS valid"`
   * rather than stopping at the inner `FROM`.
   *
   * Tracks parenthesis depth so that `FROM` inside subqueries is ignored. Only a `FROM` at
   * depth 0 (or end of statement) terminates the select clause.
   */
  private fun extractTopLevelSelectClause(sql: String): String? {
    val upper = sql.uppercase()
    val selectIndex = upper.indexOf("SELECT ")
    if (selectIndex < 0) return null
    val start = selectIndex + "SELECT ".length

    var depth = 0
    var i = start
    while (i < sql.length) {
      when (sql[i]) {
        '(' -> depth++
        ')' -> depth--
      }
      // Look for top-level FROM keyword (not inside parentheses)
      if (depth == 0 && i + 5 <= sql.length) {
        val remaining = upper.substring(i)
        if (remaining.startsWith("FROM ") || remaining.startsWith("FROM\n") || remaining.startsWith("FROM\t")) {
          return sql.substring(start, i).trim()
        }
      }
      i++
    }
    // No FROM found (e.g., `SELECT COALESCE(?, 'default') AS val`)
    return sql.substring(start).trimEnd(';', ' ', '\n', '\t')
  }

  /**
   * Checks whether a SQL expression is a known non-null construct based on its prefix.
   *
   * This is a lightweight check that identifies top-level constructs like `EXISTS(...)`,
   * `COUNT(...)`, and `COALESCE(...)` without requiring an `AS` alias. It does not perform
   * the full recursive strictness analysis that [findNonNullAliases] does for aliased expressions.
   *
   * Useful as a fallback in [JdbcAnalyzer.buildResultColumns] for un-aliased SELECT items
   * where [findNonNullAliases] cannot help (it requires `AS alias` syntax).
   */
  fun isKnownNonNullExpression(expression: String): Boolean {
    val upper = expression.trim().uppercase()
    return NON_NULL_EXPRESSIONS.any { upper.startsWith(it) }
  }

  private companion object {
    /** Matches `AS alias` at the end of a select expression. */
    private val AS_ALIAS = Regex("""\bAS\s+(\w+)\s*$""", RegexOption.IGNORE_CASE)

    /** SQL expressions that are inherently non-null regardless of input. */
    private val NON_NULL_EXPRESSIONS = listOf("EXISTS(", "COUNT(", "COALESCE(")
  }
}
