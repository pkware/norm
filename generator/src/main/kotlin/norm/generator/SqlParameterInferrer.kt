package norm.generator

import plugin.Catalog
import plugin.Column

/**
 * Infers parameter names and nullability from SQL patterns.
 *
 * Applies multiple strategies to map `?` positional parameters to descriptive names and
 * determine their nullability based on SQL context (INSERT vs WHERE clauses, function arguments).
 *
 * @param functionOverloads Metadata about PostgreSQL functions from `pg_proc`, used to resolve
 *   formal argument names for parameters passed to function calls.
 */
internal class SqlParameterInferrer(private val functionOverloads: Map<String, List<FunctionOverload>>) {

  /**
   * Infers parameter names and context from SQL by parsing common SQL patterns.
   *
   * Applies multiple strategies in priority order:
   * 1. **Function argument names** — `func(?)` resolved via `pg_proc` (e.g., `digest(?, ?)` → `data`, `type`)
   * 2. **INSERT column names** — `INSERT INTO t(col) VALUES (?)` (only for params not inside function calls)
   * 3. **SET/WHERE column names** — `SET col = ?` or `WHERE col = ?`
   *
   * Function names take priority because they describe what the caller should provide.
   * For example, `INSERT INTO t(password_hash) VALUES (crypt(?, gen_salt('bf')))` names the first `?`
   * as `data` (from `crypt`'s signature) rather than `password_hash` (the target column).
   *
   * @return A map from 1-based parameter number to inferred parameter info.
   */
  fun inferParameterInfo(sql: String): Map<Int, InferredParameter> {
    val paramIndex = ParamIndex(sql)
    val params = mutableMapOf<Int, InferredParameter>()

    // First pass: resolve function argument names for parameters inside function calls.
    // These take highest priority because they describe what the caller should provide.
    val funcNames = inferFunctionArgNames(sql, paramIndex)

    // INSERT INTO table(col1, col2) VALUES (?, func(?), ...)
    val insertMatch = INSERT_INTO.find(sql)
    if (insertMatch != null) {
      val tableName = tableSimpleName(insertMatch.groupValues[1])
      val columns = insertMatch.groupValues[2].split(",").map { unquoteIdentifier(it.trim()) }
      val valueExpressions = extractValuesExpressions(sql)
      if (valueExpressions != null) {
        val (expressions, contentStart) = valueExpressions
        // Map each top-level VALUES expression to its corresponding INSERT column.
        // splitAtTopLevel respects parenthesis depth, so `crypt(?, gen_salt('bf'))` stays
        // as one expression correctly mapped to its target column.
        var exprOffset = contentStart
        for ((colIndex, expr) in expressions.withIndex()) {
          if (colIndex >= columns.size) break
          val columnName = columns[colIndex]
          for (charIdx in expr.indices) {
            if (expr[charIdx] == '?') {
              val paramNum = paramIndex.paramNumberAt(exprOffset + charIdx)
              val displayName = funcNames[paramNum] ?: columnName
              params[paramNum] =
                InferredParameter(displayName, tableName, inheritsNullability = true, columnName = columnName)
            }
          }
          exprOffset += expr.length + 1 // +1 for comma
        }
      }
      // Add any function-inferred params not in the INSERT (shouldn't happen, but be safe)
      for ((paramNum, name) in funcNames) {
        if (paramNum !in params) {
          params[paramNum] = InferredParameter(name, tableName, inheritsNullability = true)
        }
      }
      return params
    }

    // For UPDATE/DELETE/SELECT, split on WHERE to distinguish SET from WHERE contexts
    val tableName = UPDATE_TABLE.find(sql)?.groupValues?.get(1)?.let(::tableSimpleName)
      ?: DELETE_FROM.find(sql)?.groupValues?.get(1)?.let(::tableSimpleName)
      ?: FROM_TABLE.find(sql)?.groupValues?.get(1)?.let(::tableSimpleName)
    val whereIndex = WHERE_KEYWORD.find(sql)?.range?.first ?: -1

    // COALESCE pattern: SET col = coalesce(?, fallback) — always nullable regardless of column constraint.
    // Scoped to the SET clause (before WHERE) so it doesn't affect WHERE conditions.
    val setEndIndex = if (whereIndex > 0) whereIndex else sql.length
    val setClauseForCoalesce = sql.substring(0, setEndIndex)
    for (match in COLUMN_EQUALS_COALESCE_PARAM.findAll(setClauseForCoalesce)) {
      val qualifiedTable = match.groupValues[1].ifEmpty { null }?.let(::unquoteIdentifier)
      val colName = unquoteIdentifier(match.groupValues[2])
      val paramNum = paramIndex.paramNumberAt(match.range.last)
      if (paramNum !in params) {
        params[paramNum] =
          InferredParameter(colName, qualifiedTable ?: tableName, inheritsNullability = false, alwaysNullable = true)
      }
    }

    if (whereIndex > 0) {
      // SET col = ? (before WHERE — inherits nullability)
      val setClause = sql.substring(0, whereIndex)
      for (match in COLUMN_COMPARES_PARAM.findAll(setClause)) {
        val qualifiedTable = match.groupValues[1].ifEmpty { null }?.let(::unquoteIdentifier)
        val colName = unquoteIdentifier(match.groupValues[2])
        val paramNum = paramIndex.paramNumberAt(match.range.last)
        if (paramNum !in params) {
          params[paramNum] =
            InferredParameter(funcNames[paramNum] ?: colName, qualifiedTable ?: tableName, inheritsNullability = true)
        }
      }

      // WHERE col <op> ? (after WHERE — does NOT inherit nullability)
      val whereClause = sql.substring(whereIndex)
      for (match in COLUMN_COMPARES_PARAM.findAll(whereClause)) {
        val qualifiedTable = match.groupValues[1].ifEmpty { null }?.let(::unquoteIdentifier)
        val colName = unquoteIdentifier(match.groupValues[2])
        val paramNum = paramIndex.paramNumberAt(whereIndex + match.range.last)
        if (paramNum !in params) {
          params[paramNum] =
            InferredParameter(funcNames[paramNum] ?: colName, qualifiedTable ?: tableName, inheritsNullability = false)
        }
      }
    } else {
      // No WHERE clause.
      // For UPDATE statements, all col = ? patterns are SET assignments that inherit nullability from
      // the target column's schema definition.
      // For other statements (SELECT, DELETE), col = ? patterns are comparisons; passing null would
      // never match, so they do not inherit nullability.
      val setParametersInheritNullability = UPDATE_TABLE.containsMatchIn(sql)
      for (match in COLUMN_COMPARES_PARAM.findAll(sql)) {
        val qualifiedTable = match.groupValues[1].ifEmpty { null }?.let(::unquoteIdentifier)
        val colName = unquoteIdentifier(match.groupValues[2])
        val paramNum = paramIndex.paramNumberAt(match.range.last)
        if (paramNum !in params) {
          params[paramNum] =
            InferredParameter(
              funcNames[paramNum] ?: colName,
              qualifiedTable ?: tableName,
              inheritsNullability = setParametersInheritNullability,
            )
        }
      }
    }

    // Add any function-inferred params not matched by other patterns
    for ((paramNum, name) in funcNames) {
      if (paramNum !in params) {
        params[paramNum] = InferredParameter(name, tableName, inheritsNullability = false)
      }
    }

    return params
  }

  /**
   * Determines which parameters are non-nullable by looking up inferred column names in the catalog.
   *
   * For parameters in INSERT or SET context ([InferredParameter.inheritsNullability] = `true`),
   * the result mirrors the target column's `NOT NULL` constraint. For WHERE parameters,
   * the result is always `true` (non-nullable) since `col = NULL` is never true in SQL.
   *
   * @return A map from 1-based parameter number to whether the parameter is non-nullable (`true` = `NOT NULL`).
   */
  fun resolveParameterNotNull(inferredParams: Map<Int, InferredParameter>, catalog: Catalog): Map<Int, Boolean> {
    val notNullMap = mutableMapOf<Int, Boolean>()
    for ((paramNum, inferred) in inferredParams) {
      if (inferred.alwaysNullable) {
        notNullMap[paramNum] = false
        continue
      }
      if (!inferred.inheritsNullability) {
        notNullMap[paramNum] = true
        continue
      }
      val column = findColumnInCatalog(inferred.columnName ?: inferred.name, inferred.tableName, catalog)
      notNullMap[paramNum] = column?.not_null ?: true
    }
    return notNullMap
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

    for (call in extractFunctionCalls(sql)) {
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
          val paramNum = paramIndex.paramNumberAt(paramPositions[0])
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
  private fun extractFunctionCalls(sql: String): List<FunctionCall> {
    val calls = mutableListOf<FunctionCall>()

    for (match in FUNCTION_CALL_START.findAll(sql)) {
      val funcName = match.groupValues[1]
      if (funcName.uppercase() in SQL_KEYWORDS) continue

      val openParenthesis = match.range.last
      val closeParenthesis = findMatchingCloseParenthesis(sql, openParenthesis)
      if (closeParenthesis < 0) continue

      val argsText = sql.substring(openParenthesis + 1, closeParenthesis)
      // Only include calls that contain at least one positional parameter
      if ('?' in argsText) {
        val splitArgs = splitAtTopLevel(argsText, ',')
        val args = buildArgExpressions(splitArgs, sqlIndexOfFirstArg = openParenthesis + 1)
        calls.add(FunctionCall(funcName, args))
      }
    }

    return calls
  }

  /**
   * Converts comma-split argument text into [ArgExpression] objects, recording the SQL-level
   * character index of each `?` so that [ParamIndex] can map it to a parameter number.
   *
   * @param commaDelimitedArgs The raw argument strings produced by [splitAtTopLevel], potentially
   *   with leading/trailing whitespace (e.g., `[" digest(?, ?)", " ?"]`).
   * @param sqlIndexOfFirstArg The char index in the original SQL where the argument list begins
   *   (i.e., the position right after the opening parenthesis of the function call).
   */
  private fun buildArgExpressions(commaDelimitedArgs: List<String>, sqlIndexOfFirstArg: Int): List<ArgExpression> {
    val result = mutableListOf<ArgExpression>()
    var sqlOffset = sqlIndexOfFirstArg
    for (rawArg in commaDelimitedArgs) {
      val trimmed = rawArg.trim()
      val leadingWhitespace = rawArg.length - rawArg.trimStart().length
      val trimmedStartInSql = sqlOffset + leadingWhitespace

      val paramPositions = mutableListOf<Int>()
      for (i in trimmed.indices) {
        if (trimmed[i] == '?') {
          paramPositions.add(trimmedStartInSql + i)
        }
      }

      result.add(ArgExpression(trimmed, paramPositions))
      sqlOffset += rawArg.length + 1 // +1 for the comma delimiter
    }
    return result
  }

  /**
   * Finds a column definition in the catalog by name, optionally scoped to a specific table.
   */
  private fun findColumnInCatalog(columnName: String, tableName: String?, catalog: Catalog): Column? =
    catalog.findColumn(tableName, columnName)

  /**
   * Extracts the top-level comma-separated expressions from a `VALUES (...)` clause,
   * correctly handling nested parentheses (e.g., `crypt(?, gen_salt('bf'))`).
   *
   * @return A pair of (expressions, contentStartIndex) where `contentStartIndex` is the char
   *   position in [sql] of the first character inside the VALUES parentheses, or `null` if no
   *   VALUES clause is found.
   */
  private fun extractValuesExpressions(sql: String): Pair<List<String>, Int>? {
    val valuesIdx = sql.indexOf("VALUES", ignoreCase = true)
    if (valuesIdx < 0) return null
    val openParenthesis = sql.indexOf('(', valuesIdx + 6)
    if (openParenthesis < 0) return null
    val closeParenthesis = findMatchingCloseParenthesis(sql, openParenthesis)
    if (closeParenthesis < 0) return null

    val content = sql.substring(openParenthesis + 1, closeParenthesis)
    return splitAtTopLevel(content, ',') to (openParenthesis + 1)
  }

  private companion object {
    // Matches either a double-quoted SQL identifier ("name") or an unquoted one (word).
    private const val SQL_IDENTIFIER = """(?:"[^"]+"|\w+)"""

    // Matches a possibly schema-qualified table name: `table`, `"table"`, or `"schema"."table"`.
    private const val QUALIFIED_TABLE = """($SQL_IDENTIFIER(?:\.$SQL_IDENTIFIER)?)"""

    private val INSERT_INTO =
      Regex("""INSERT\s+INTO\s+$QUALIFIED_TABLE\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
    private val UPDATE_TABLE = Regex("""UPDATE\s+$QUALIFIED_TABLE\s""", RegexOption.IGNORE_CASE)
    private val DELETE_FROM = Regex("""DELETE\s+FROM\s+$QUALIFIED_TABLE""", RegexOption.IGNORE_CASE)
    private val FROM_TABLE = Regex("""\bFROM\s+$QUALIFIED_TABLE""", RegexOption.IGNORE_CASE)

    /**
     * Matches the WHERE keyword as a standalone word, handling any surrounding whitespace including newlines.
     * This supports multi-line SQL where WHERE may appear on its own line preceded by `\n` rather than a space.
     */
    private val WHERE_KEYWORD = Regex("""\bWHERE\b""", RegexOption.IGNORE_CASE)
    private val COLUMN_COMPARES_PARAM =
      Regex(
        """(?:($SQL_IDENTIFIER)\.)?($SQL_IDENTIFIER)\s*(?:=|<>|!=|>=|<=|>|<|LIKE|ILIKE)\s*\?""",
        RegexOption.IGNORE_CASE,
      )

    /**
     * Matches `col = coalesce(?, ...)` — a column assignment where COALESCE wraps the parameter.
     * The `?` is the first argument, meaning `null` = "use the fallback expression".
     * Group 1: optional table qualifier, Group 2: column name.
     */
    private val COLUMN_EQUALS_COALESCE_PARAM =
      Regex(
        """(?:($SQL_IDENTIFIER)\.)?($SQL_IDENTIFIER)\s*=\s*coalesce\(\s*\?""",
        RegexOption.IGNORE_CASE,
      )

    /**
     * Strips surrounding double-quotes from a SQL identifier, if present.
     * For example, `"name"` becomes `name`, and `author` stays `author`.
     */
    private fun unquoteIdentifier(identifier: String): String =
      if (identifier.startsWith('"') && identifier.endsWith('"')) {
        identifier.substring(1, identifier.length - 1)
      } else {
        identifier
      }

    /**
     * Extracts the simple (unqualified, unquoted) table name from a possibly
     * schema-qualified SQL table reference like `"schema"."tablename"` or `"tablename"`.
     */
    private fun tableSimpleName(qualifiedName: String): String = unquoteIdentifier(qualifiedName.split('.').last())
  }
}

/**
 * Index mapping each `?` character position in a SQL string to its 1-based parameter number.
 */
private class ParamIndex(sql: String) {
  private val positions: IntArray

  init {
    val list = mutableListOf<Int>()
    for (i in sql.indices) {
      if (sql[i] == '?') list.add(i)
    }
    positions = list.toIntArray()
  }

  /** Returns the 1-based parameter number for the `?` at [charIndex]. */
  fun paramNumberAt(charIndex: Int): Int {
    val idx = positions.asList().binarySearch(charIndex)
    check(idx >= 0) { "No ? at char index $charIndex" }
    return idx + 1
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

/**
 * Information inferred about a query parameter from SQL context.
 *
 * @property name The parameter name for generated code. May come from a function's formal argument
 *   name (e.g., `password` from `crypt`'s signature) or from the target column name.
 * @property tableName The table name, if determinable from the SQL. `null` when the SQL has no
 *   table context (e.g., pure function calls).
 * @property inheritsNullability Whether this parameter's nullability should match the column's.
 *   `true` for INSERT/SET contexts where `null` is a valid value to write;
 *   `false` for WHERE contexts where comparing with `null` requires `IS NULL` instead.
 * @property columnName The column name used for nullability lookup in the catalog. When a parameter
 *   appears inside a function call in an INSERT VALUES clause, [name] may be the function's formal
 *   argument name while [columnName] is the INSERT target column. `null` defaults to using [name].
 * @property alwaysNullable When `true`, the parameter is unconditionally nullable regardless of the
 *   target column's `NOT NULL` constraint. Used for `COALESCE(?, fallback)` patterns in SET clauses,
 *   where `null` means "keep the current value".
 */
internal data class InferredParameter(
  val name: String,
  val tableName: String?,
  val inheritsNullability: Boolean,
  val columnName: String? = null,
  val alwaysNullable: Boolean = false,
)
