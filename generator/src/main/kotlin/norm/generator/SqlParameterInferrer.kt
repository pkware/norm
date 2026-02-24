package norm.generator

import plugin.Catalog
import plugin.Column

/**
 * Infers parameter names and nullability from SQL patterns.
 *
 * Applies multiple strategies to map `$N` positional parameters to descriptive names and
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
   * 1. **Function argument names** — `func($N)` resolved via `pg_proc` (e.g., `digest($1, $2)` → `data`, `type`)
   * 2. **INSERT column names** — `INSERT INTO t(col) VALUES ($N)` (only for params not inside function calls)
   * 3. **SET/WHERE column names** — `SET col = $N` or `WHERE col = $N`
   *
   * Function names take priority because they describe what the caller should provide.
   * For example, `INSERT INTO t(password_hash) VALUES (crypt($1, gen_salt('bf')))` names `$1`
   * as `data` (from `crypt`'s signature) rather than `password_hash` (the target column).
   *
   * @return A map from 1-based parameter number to inferred parameter info.
   */
  fun inferParameterInfo(sql: String): Map<Int, InferredParameter> {
    val params = mutableMapOf<Int, InferredParameter>()

    // First pass: resolve function argument names for parameters inside function calls.
    // These take highest priority because they describe what the caller should provide.
    val funcNames = inferFunctionArgNames(sql)

    // INSERT INTO table(col1, col2) VALUES ($1, $2)
    val insertMatch = INSERT_INTO.find(sql)
    if (insertMatch != null) {
      val tableName = insertMatch.groupValues[1]
      val columns = insertMatch.groupValues[2].split(",").map { it.trim() }
      val valuesMatch = VALUES_PARAMS.find(sql)
      if (valuesMatch != null) {
        val positionalParams = POSITIONAL_PARAM.findAll(valuesMatch.groupValues[1]).toList()
        for ((index, param) in positionalParams.withIndex()) {
          val paramNum = param.value.removePrefix("$").toInt()
          if (index < columns.size) {
            // Function arg name wins over column name
            val name = funcNames[paramNum] ?: columns[index]
            params[paramNum] = InferredParameter(name, tableName, inheritsNullability = true)
          }
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
    val tableName = UPDATE_TABLE.find(sql)?.groupValues?.get(1)
      ?: DELETE_FROM.find(sql)?.groupValues?.get(1)
    val whereIndex = sql.indexOf(" WHERE ", ignoreCase = true)

    if (whereIndex > 0) {
      // SET col = $N (before WHERE — inherits nullability)
      val setClause = sql.substring(0, whereIndex)
      for (match in COLUMN_COMPARES_PARAM.findAll(setClause)) {
        val colName = match.groupValues[1]
        val paramNum = match.groupValues[2].toInt()
        params[paramNum] = InferredParameter(funcNames[paramNum] ?: colName, tableName, inheritsNullability = true)
      }

      // WHERE col <op> $N (after WHERE — does NOT inherit nullability)
      val whereClause = sql.substring(whereIndex)
      for (match in COLUMN_COMPARES_PARAM.findAll(whereClause)) {
        val colName = match.groupValues[1]
        val paramNum = match.groupValues[2].toInt()
        if (paramNum !in params) {
          params[paramNum] = InferredParameter(funcNames[paramNum] ?: colName, tableName, inheritsNullability = false)
        }
      }
    } else {
      // No WHERE clause — all comparisons treated as non-inheriting
      for (match in COLUMN_COMPARES_PARAM.findAll(sql)) {
        val colName = match.groupValues[1]
        val paramNum = match.groupValues[2].toInt()
        params[paramNum] = InferredParameter(funcNames[paramNum] ?: colName, tableName, inheritsNullability = false)
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
   * Resolves parameter nullability by looking up inferred column names in the catalog.
   *
   * For parameters in INSERT or SET context ([InferredParameter.inheritsNullability] = `true`),
   * the parameter's nullability matches the target column's. For WHERE parameters,
   * the parameter is always non-nullable since `col = NULL` is never true in SQL.
   *
   * @return A map from 1-based parameter number to whether the parameter is non-nullable.
   */
  fun resolveParameterNullability(inferredParams: Map<Int, InferredParameter>, catalog: Catalog): Map<Int, Boolean> {
    val notNullMap = mutableMapOf<Int, Boolean>()
    for ((paramNum, inferred) in inferredParams) {
      if (!inferred.inheritsNullability) {
        notNullMap[paramNum] = true
        continue
      }
      val column = findColumnInCatalog(inferred.name, inferred.tableName, catalog)
      notNullMap[paramNum] = column?.not_null ?: true
    }
    return notNullMap
  }

  /**
   * Infers parameter names from function calls by looking up formal argument names in `pg_proc`.
   *
   * Parses `func($N, ...)` patterns from the SQL — including nested calls like
   * `encode(digest($1, $2), $3)` — and resolves each `$N` to the corresponding
   * formal argument name from the function's `pg_proc` entry.
   *
   * For example, `digest($1, $2)` with `pg_proc` showing `proargnames = {data, type}`
   * yields `{1 → "data", 2 → "type"}`.
   *
   * When a parameter appears in multiple function calls (e.g., nested), the innermost
   * (first-matched) function wins.
   *
   * @return A map from 1-based parameter number to the formal argument name.
   */
  private fun inferFunctionArgNames(sql: String): Map<Int, String> {
    val result = mutableMapOf<Int, String>()

    // Track how many times each function name appears, so repeated calls get a disambiguating suffix.
    // First call to crypt → "crypt", second → "crypt2", etc.
    val functionCallCounts = mutableMapOf<String, Int>()

    for (call in extractFunctionCalls(sql)) {
      val funcName = call.first.lowercase()
      val argExpressions = call.second

      // Look up overload metadata for this function.
      // If the function isn't in pg_proc at all, skip it (not a real function).
      val overloads = functionOverloads[funcName] ?: continue
      val overload = overloads.find { it.argNames.size == argExpressions.size }
        ?: overloads.find { it.argNames.size >= argExpressions.size }
      val formalNames = overload?.argNames

      val callNumber = functionCallCounts.getOrDefault(funcName, 0) + 1
      functionCallCounts[funcName] = callNumber
      val callPrefix = if (callNumber == 1) funcName else "$funcName$callNumber"

      // For each argument expression, find positional parameters and assign a name.
      // Priority: pg_proc formal name > function name with arg position (e.g., crypt_param1).
      // When the same function is called multiple times, calls after the first get a numeric suffix
      // on the function name (e.g., crypt2_param1) so a developer can tell which call it belongs to.
      for ((argIndex, argExpr) in argExpressions.withIndex()) {
        val paramMatches = POSITIONAL_PARAM.findAll(argExpr).toList()
        // Only assign the name if the argument is a single positional parameter (possibly with whitespace)
        // For complex expressions like `gen_salt('bf')`, there's no $N to name
        if (paramMatches.size == 1) {
          val paramNum = paramMatches[0].value.removePrefix("$").toInt()
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
   * For `encode(digest($1, $2), $3)`, returns both:
   * - `"encode"` with args `["digest($1, $2)", "$3"]`
   * - `"digest"` with args `["$1", "$2"]`
   *
   * SQL keywords like SELECT, FROM, WHERE, INSERT, VALUES, etc. are excluded.
   *
   * @return List of pairs: (function name, list of argument expressions).
   */
  private fun extractFunctionCalls(sql: String): List<Pair<String, List<String>>> {
    val calls = mutableListOf<Pair<String, List<String>>>()

    for (match in FUNCTION_CALL_START.findAll(sql)) {
      val funcName = match.groupValues[1]
      if (funcName.uppercase() in SQL_KEYWORDS) continue

      // Find the matching closing paren, tracking depth
      val argsStart = match.range.last + 1
      var depth = 1
      var i = argsStart
      while (i < sql.length && depth > 0) {
        when (sql[i]) {
          '(' -> depth++
          ')' -> depth--
        }
        i++
      }
      if (depth != 0) continue

      val argsText = sql.substring(argsStart, i - 1)
      // Only include calls that contain at least one positional parameter
      if (POSITIONAL_PARAM.containsMatchIn(argsText)) {
        calls.add(funcName to splitAtTopLevel(argsText, ',').map { it.trim() }.filter { it.isNotEmpty() })
      }
    }

    return calls
  }

  /**
   * Finds a column definition in the catalog by name, optionally scoped to a specific table.
   */
  private fun findColumnInCatalog(columnName: String, tableName: String?, catalog: Catalog): Column? {
    for (schema in catalog.schemas) {
      for (table in schema.tables) {
        if (tableName != null && table.rel?.name != tableName) continue
        for (column in table.columns) {
          if (column.name == columnName) return column
        }
      }
    }
    return null
  }

  private companion object {
    private val INSERT_INTO = Regex("""INSERT\s+INTO\s+(\w+)\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
    private val VALUES_PARAMS = Regex("""VALUES\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
    private val UPDATE_TABLE = Regex("""UPDATE\s+(\w+)\s""", RegexOption.IGNORE_CASE)
    private val DELETE_FROM = Regex("""DELETE\s+FROM\s+(\w+)""", RegexOption.IGNORE_CASE)
    private val COLUMN_COMPARES_PARAM =
      Regex("""(\w+)\s*(?:=|<>|!=|>=|<=|>|<|LIKE|ILIKE)\s*\$(\d+)""", RegexOption.IGNORE_CASE)
  }
}

/**
 * Information inferred about a query parameter from SQL context.
 *
 * @property name The column name this parameter corresponds to.
 * @property tableName The table name, if determinable from the SQL. `null` when the SQL has no
 *   table context (e.g., pure function calls).
 * @property inheritsNullability Whether this parameter's nullability should match the column's.
 *   `true` for INSERT/SET contexts where `null` is a valid value to write;
 *   `false` for WHERE contexts where comparing with `null` requires `IS NULL` instead.
 */
internal data class InferredParameter(val name: String, val tableName: String?, val inheritsNullability: Boolean)
