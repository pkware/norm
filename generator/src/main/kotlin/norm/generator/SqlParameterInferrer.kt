package norm.generator

/**
 * Merges the formal argument names of the function calls in the SQL text into what [ParameterNullabilityProbe]
 * inferred about each parameter.
 *
 * @property functionOverloads Metadata about PostgreSQL functions from `pg_proc`, used to resolve
 *   formal argument names for parameters passed to function calls.
 */
internal class SqlParameterInferrer(private val functionOverloads: Map<String, List<FunctionOverload>>) {

  /**
   * Adds function argument names to the parameters of [sql] that [ParameterNullabilityProbe] classified.
   *
   * A function argument name describes what the caller should provide, so it replaces the column name of a parameter.
   * For example, `UPDATE t SET note = regexp_replace(?, 'a', 'b')` names the `?` `string`, from the signature of
   * `regexp_replace`.
   *
   * @param classified The parameters of [sql] that the probe named or implied something about, keyed by 1-based
   *   parameter number.
   * @return A map from 1-based parameter number to inferred parameter info. A parameter absent from it has no name
   *   and no opinion on `null`.
   */
  fun inferParameterInfo(sql: String, classified: Map<Int, InferredParameter>): Map<Int, InferredParameter> {
    val functionNames = inferFunctionArgNames(sql, ParamIndex(sql))
    if (functionNames.isEmpty()) return classified
    val inferred = classified.toMutableMap()
    for ((number, name) in functionNames) {
      inferred[number] = (classified[number] ?: InferredParameter(null, null)).copy(name = name)
    }
    return inferred
  }

  /**
   * Determines which parameters are non-nullable by looking up inferred column names in the catalog.
   *
   * A parameter written to a column ([ParameterNullability.Inherit]) is non-nullable when the column is `NOT NULL`.
   * A parameter in a condition is non-nullable, since `col = NULL` never matches a row in SQL. A parameter without an
   * opinion on `null` is non-nullable.
   *
   * @return A map from 1-based parameter number to whether the parameter is non-nullable (`true` = `NOT NULL`).
   */
  fun resolveParameterNotNull(inferredParams: Map<Int, InferredParameter>, catalog: Catalog): Map<Int, Boolean> =
    inferredParams.mapValues { (_, inferred) ->
      when (val nullability = inferred.nullability) {
        ParameterNullability.Nullable -> false
        ParameterNullability.NonNull, null -> true
        is ParameterNullability.Inherit ->
          nullability.column.let { catalog.findColumn(it.table, it.column, it.schema) }?.notNull ?: true
      }
    }

  /**
   * Infers parameter names from function calls by looking up formal argument names in `pg_proc`.
   *
   * Parses `func(?, ...)` patterns from the SQL — including nested calls like
   * `encode(digest(?, ?), ?)` — and resolves each `?` to the corresponding
   * formal argument name from the function's `pg_proc` entry.
   *
   * For example, `digest(?, ?)` with `pg_proc` showing `proargnames = {data, type}`
   * yields `{1 → "data", 2 → "type"}`.
   *
   * When a parameter appears in multiple function calls (e.g., nested), the innermost
   * (first-matched) function wins.
   *
   * @return A map from 1-based parameter number to the formal argument name.
   */
  private fun inferFunctionArgNames(sql: String, paramIndex: ParamIndex): Map<Int, String> {
    val result = mutableMapOf<Int, String>()

    // Track how many times each function name appears, so repeated calls get a disambiguating suffix.
    // First call to crypt → "crypt", second → "crypt2", etc.
    val functionCallCounts = mutableMapOf<String, Int>()

    for (call in extractFunctionCalls(sql, paramIndex)) {
      val funcName = call.name.lowercase()
      val argExpressions = call.args

      // Look up overload metadata for this function.
      // If the function isn't in pg_proc at all, skip it (not a real function).
      val overloads = functionOverloads[funcName] ?: continue
      val overload = findOverload(overloads, argExpressions.size)
      val formalNames = overload?.argNames

      val callNumber = functionCallCounts.getOrDefault(funcName, 0) + 1
      functionCallCounts[funcName] = callNumber
      val callPrefix = if (callNumber == 1) funcName else "$funcName$callNumber"

      // For each argument expression, find positional parameters and assign a name.
      // Priority: pg_proc formal name > function name with arg position (e.g., crypt_param1).
      // When the same function is called multiple times, calls after the first get a numeric suffix
      // on the function name (e.g., crypt2_param1) so a developer can tell which call it belongs to.
      for ((argIndex, argExpr) in argExpressions.withIndex()) {
        val paramPositions = argExpr.paramPositions
        // Only assign the name if the argument contains a single ? (possibly with whitespace).
        // For complex expressions like `gen_salt('bf')`, there's no ? to name.
        if (paramPositions.size == 1) {
          val paramNum = paramIndex.paramNumberAt(paramPositions[0]) ?: continue
          if (paramNum !in result) {
            val formalName = formalNames?.getOrNull(argIndex)?.takeIf { it.isNotEmpty() }
            result[paramNum] = formalName ?: "${callPrefix}_param${argIndex + 1}"
          }
        }
      }
    }

    return result
  }

  /**
   * Extracts function calls from SQL, handling nested parentheses correctly.
   *
   * For `encode(digest(?, ?), ?)`, returns both:
   * - `"encode"` with args `["digest(?, ?)", "?"]`
   * - `"digest"` with args `["?", "?"]`
   *
   * SQL keywords like SELECT, FROM, WHERE, INSERT, VALUES, etc. are excluded.
   *
   * @return List of [FunctionCall] instances with argument expressions and their `?` positions.
   */
  private fun extractFunctionCalls(sql: String, paramIndex: ParamIndex): List<FunctionCall> {
    val calls = mutableListOf<FunctionCall>()

    for (match in FUNCTION_CALL_START.findAll(sql)) {
      val funcName = match.groupValues[1]
      if (funcName.uppercase() in SQL_KEYWORDS) continue

      val openParenthesis = match.range.last
      val closeParenthesis = findMatchingCloseParenthesis(sql, openParenthesis)
      if (closeParenthesis < 0) continue

      // Only include calls that contain at least one real placeholder. A "?" inside a string
      // literal argument (e.g. digest('?')) doesn't count, and must not bump functionCallCounts
      // in inferFunctionArgNames -- doing so would misnumber a later, real call to the same function.
      if (paramIndex.hasPlaceholderIn(openParenthesis + 1, closeParenthesis)) {
        val argsText = sql.substring(openParenthesis + 1, closeParenthesis)
        val splitArgs = splitAtTopLevel(argsText, ',')
        val args = buildArgExpressions(splitArgs, sqlIndexOfFirstArg = openParenthesis + 1, paramIndex)
        calls.add(FunctionCall(funcName, args))
      }
    }

    return calls
  }

  /**
   * Converts comma-split argument text into [ArgExpression] objects, recording the SQL-level
   * character index of each real placeholder so that [ParamIndex] can map it to a parameter number.
   * A `?` that is not a real placeholder (e.g. one inside a string literal argument) is excluded,
   * so an argument like `'?'` counts as having none, not one.
   *
   * @param commaDelimitedArgs The raw argument strings produced by [splitAtTopLevel], potentially
   *   with leading/trailing whitespace (e.g., `[" digest(?, ?)", " ?"]`).
   * @param sqlIndexOfFirstArg The char index in the original SQL where the argument list begins
   *   (i.e., the position right after the opening parenthesis of the function call).
   */
  private fun buildArgExpressions(
    commaDelimitedArgs: List<String>,
    sqlIndexOfFirstArg: Int,
    paramIndex: ParamIndex,
  ): List<ArgExpression> {
    val result = mutableListOf<ArgExpression>()
    var sqlOffset = sqlIndexOfFirstArg
    for (rawArg in commaDelimitedArgs) {
      val trimmed = rawArg.trim()
      val leadingWhitespace = rawArg.length - rawArg.trimStart().length
      val trimmedStartInSql = sqlOffset + leadingWhitespace

      val paramPositions = mutableListOf<Int>()
      for (i in trimmed.indices) {
        if (trimmed[i] == '?') {
          val globalPosition = trimmedStartInSql + i
          if (paramIndex.isPlaceholderAt(globalPosition)) paramPositions.add(globalPosition)
        }
      }

      result.add(ArgExpression(trimmed, paramPositions))
      sqlOffset += rawArg.length + 1 // +1 for the comma delimiter
    }
    return result
  }
}

/**
 * Matches `func_name(` to find the start of function calls — the captured name is a PostgreSQL
 * unquoted identifier ([COLUMN_REFERENCE_IDENTIFIER_START] followed by zero or more
 * [COLUMN_REFERENCE_IDENTIFIER_CONTINUATION] characters, the same identifier shape
 * [COLUMN_REFERENCE] uses), never a bare `\w+`: `\w` excludes both `$` and any `>= 0x80`
 * character, both of which PostgreSQL admits after an identifier's first character (see
 * [isIdentifierChar]). For `SELECT my$fn(?)`, `\w+` cannot match `my$fn` as one run (`$` breaks
 * it), so `findAll` instead matches the shorter run `fn` immediately before the `(` — handing
 * `SqlParameterInferrer.extractFunctionCalls` the wrong function name (`fn` for `my$fn`).
 * On PostgreSQL 18.4, `CREATE FUNCTION "my$fn"(...)` and the unquoted call `my$fn(...)` both
 * resolve to the same function, and an unquoted `>= 0x80`-named function (`fn€(...)`) is likewise
 * legal, while a digit-led name (`2fn(...)`) is rejected outright ("trailing junk after numeric
 * literal") — exactly the identifier shape this regex encodes.
 */
internal val FUNCTION_CALL_START = Regex(
  """($COLUMN_REFERENCE_IDENTIFIER_START$COLUMN_REFERENCE_IDENTIFIER_CONTINUATION*)\(""",
)

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
 * Finds the best matching [FunctionOverload] for a call with [argCount] arguments.
 *
 * Prefers an exact match by argument count. Falls back to overloads with more arguments
 * (default parameters) or overloads with no named arguments (variadic/generic).
 */
internal fun findOverload(overloads: List<FunctionOverload>, argCount: Int): FunctionOverload? =
  overloads.find { it.argNames.size == argCount || it.argNames.isEmpty() }
    ?: overloads.find { it.argNames.size >= argCount }

/**
 * Index mapping each placeholder position in a SQL string, as [placeholderPositions] defines it, to
 * its 1-based parameter number.
 */
private class ParamIndex(sql: String) {
  private val positions: IntArray = placeholderPositions(sql)

  /**
   * Returns the 1-based parameter number for the placeholder at [charIndex], or `null` if
   * [charIndex] is not a real placeholder position — e.g. a `?` a caller's own raw-text scan or
   * regex match landed on that actually sits inside a string literal, quoted identifier,
   * dollar-quoted string, or comment.
   */
  fun paramNumberAt(charIndex: Int): Int? {
    val idx = positions.asList().binarySearch(charIndex)
    return if (idx >= 0) idx + 1 else null
  }

  /** Whether [charIndex] is a real placeholder position. */
  fun isPlaceholderAt(charIndex: Int): Boolean = paramNumberAt(charIndex) != null

  /** Whether any real placeholder lies within `[startInclusive, endExclusive)`. */
  fun hasPlaceholderIn(startInclusive: Int, endExclusive: Int): Boolean {
    val searchResult = positions.asList().binarySearch(startInclusive)
    val firstAtOrAfterStart = if (searchResult >= 0) searchResult else -(searchResult + 1)
    return firstAtOrAfterStart < positions.size && positions[firstAtOrAfterStart] < endExclusive
  }
}

/**
 * A parsed function call extracted from SQL.
 *
 * @property name The function name as it appears in the SQL.
 * @property args The argument expressions, each carrying its text and the global positions of any `?` it contains.
 */
private data class FunctionCall(val name: String, val args: List<ArgExpression>)

/**
 * A single argument expression from a function call.
 *
 * @property text The trimmed argument text (e.g., `"?"`, `"gen_salt('bf')"`).
 * @property paramPositions Global char indices of `?` placeholders within this argument.
 */
private data class ArgExpression(val text: String, val paramPositions: List<Int>)
