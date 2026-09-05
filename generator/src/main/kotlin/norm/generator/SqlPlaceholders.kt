package norm.generator

/**
 * Replaces each `?` parameter placeholder in [sql] with [replacement] applied to its 0-based
 * parameter index.
 *
 * PostgreSQL views cannot contain parameter placeholders. This function replaces each `?` that
 * represents a parameter, which allows the query to be used as a view body for node tree analysis.
 * Callers use it both to substitute a literal `"NULL"` (outer join nullability is determined by
 * join structure, not parameter values) and to substitute typed non-null sentinel literals, for a
 * strict function whose result nullability depends on every argument being non-null.
 *
 * A `?` inside a string literal, `E''` escape string, quoted identifier, dollar-quoted string, line
 * comment, or block comment is left alone.
 *
 * @param sql The SQL text with `?` parameter placeholders.
 * @param replacement Given a placeholder's 0-based parameter index, returns the SQL text to substitute for it.
 * @return The SQL with each `?` replaced by [replacement]'s result for it.
 */
internal fun replaceParameterPlaceholders(sql: String, replacement: (parameterIndex: Int) -> String): String {
  if ('?' !in sql) return sql
  val result = StringBuilder(sql.length + 16)
  var index = 0
  var parameterIndex = 0
  while (index < sql.length) {
    val afterToken = skipLexicalToken(sql, index)
    if (afterToken != index) {
      result.append(sql, index, afterToken)
      index = afterToken
      continue
    }
    if (sql[index] == '?') result.append(replacement(parameterIndex++)) else result.append(sql[index])
    index++
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
