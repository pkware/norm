package norm.generator

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
