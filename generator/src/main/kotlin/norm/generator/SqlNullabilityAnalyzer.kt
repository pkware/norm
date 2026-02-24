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
   *    recursively for nested calls like `encode(digest($1, $2), $3)`.
   *
   * This method extracts the top-level SELECT clause (handling nested subqueries in expressions
   * like `SELECT EXISTS(SELECT ...)`) and returns the set of aliases whose expressions match.
   *
   * @param sql The full SQL query text.
   * @return Set of column aliases (after `AS`) whose expressions are provably non-null.
   */
  fun findNonNullAliases(sql: String): Set<String> {
    val selectClause = extractTopLevelSelectClause(sql) ?: return emptySet()
    if (selectClause.trim() == "*") return emptySet()

    val aliases = mutableSetOf<String>()
    for (item in splitAtTopLevel(selectClause, ',')) {
      val trimmed = item.trim()
      val asMatch = AS_ALIAS.find(trimmed)
      val alias = asMatch?.groupValues?.get(1) ?: continue
      val expression = trimmed.substring(0, asMatch.range.first).trim()
      if (isNonNullExpression(expression)) {
        aliases.add(alias)
      }
    }
    return aliases
  }

  /**
   * Determines whether a SQL expression is guaranteed to produce a non-null result.
   *
   * Returns `true` for:
   * - Known non-null constructs: `EXISTS(...)`, `COUNT(...)`, `COALESCE(...)`
   * - Positional parameters (`$1`, `$2`, ...) — parameters are always non-null
   * - Strict function calls where every argument is also a non-null expression (recursive)
   * - String literals (`'...'`) and numeric literals
   */
  private fun isNonNullExpression(expression: String): Boolean {
    val upper = expression.trim().uppercase()

    if (NON_NULL_EXPRESSIONS.any { upper.startsWith(it) }) return true
    if (POSITIONAL_PARAM.matches(expression.trim())) return true

    val trimmed = expression.trim()
    if (trimmed.startsWith("'") && trimmed.endsWith("'")) return true
    if (trimmed.toDoubleOrNull() != null) return true

    // Check for strict function calls: func(arg1, arg2, ...)
    val funcMatch = FUNCTION_CALL_START.find(trimmed) ?: return false
    if (funcMatch.range.first != 0) return false // expression doesn't start with the function call
    val funcName = funcMatch.groupValues[1].lowercase()
    if (funcName.uppercase() in SQL_KEYWORDS) return false

    val overloads = functionOverloads[funcName] ?: return false

    // Extract the argument text inside the outermost parentheses
    val argsStart = funcMatch.range.last + 1
    var depth = 1
    var i = argsStart
    while (i < trimmed.length && depth > 0) {
      when (trimmed[i]) {
        '(' -> depth++
        ')' -> depth--
      }
      i++
    }
    if (depth != 0) return false
    // Verify the function call spans the entire expression (no trailing text)
    if (i != trimmed.length) return false

    val argsText = trimmed.substring(argsStart, i - 1)
    val args = splitAtTopLevel(argsText, ',')

    // Find matching overload by arg count
    val overload = overloads.find { it.argNames.size == args.size || (it.argNames.isEmpty() && args.isNotEmpty()) }
      ?: overloads.find { it.argNames.size >= args.size }

    // If function is strict and all arguments are non-null, the result is non-null
    return overload?.isStrict == true && args.all { isNonNullExpression(it) }
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
    // No FROM found (e.g., `SELECT COALESCE($1, 'default') AS val`)
    return sql.substring(start).trimEnd(';', ' ', '\n', '\t')
  }

  private companion object {
    /** Matches `AS alias` at the end of a select expression. */
    private val AS_ALIAS = Regex("""\bAS\s+(\w+)\s*$""", RegexOption.IGNORE_CASE)

    /** SQL expressions that are inherently non-null regardless of input. */
    private val NON_NULL_EXPRESSIONS = listOf("EXISTS(", "COUNT(", "COALESCE(")
  }
}
