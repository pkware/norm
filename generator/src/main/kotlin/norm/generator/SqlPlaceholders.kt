package norm.generator

import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryException
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber

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
 * Placeholders are located in the operator tokens of PostgreSQL's lexer, so a `?` inside a string literal,
 * `E''` escape string, quoted identifier, dollar-quoted string, or comment is left alone. Inside an operator token,
 * pgjdbc reads `??` as one literal `?`, the escape for the `jsonb` operators. It is emitted as a single `?` and is
 * not a placeholder. Text from the point where the lexer rejects [sql] onward is left alone.
 *
 * @param sql The SQL text with `?` parameter placeholders.
 * @param replacement Given a placeholder's 0-based parameter index, returns the SQL text to substitute for it.
 * @return The SQL with each `?` replaced by [replacement]'s result for it.
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun replaceParameterPlaceholders(sql: String, replacement: (parameterIndex: Int) -> String): String {
  val result = StringBuilder(sql.length + 16)
  var copiedUpTo = 0
  var parameterIndex = 0
  forEachQuestionMark(sql) { index, isEscape ->
    result.append(sql, copiedUpTo, index)
    result.append(if (isEscape) "?" else replacement(parameterIndex++))
    copiedUpTo = index + if (isEscape) 2 else 1
  }
  return result.append(sql, copiedUpTo, sql.length).toString()
}

/**
 * Returns the 0-based char index of every `?` in [sql] that is a genuine positional parameter
 * placeholder. [replaceParameterPlaceholders] uses the same rules, so the two functions agree on
 * what counts as a placeholder: a `?` inside a string literal, `E''` escape string, quoted
 * identifier, dollar-quoted string, line comment, or block comment is not a placeholder and is
 * excluded, as are both `?` of pgjdbc's `??` escape.
 *
 * @return The placeholder positions, in ascending order.
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun placeholderPositions(sql: String): IntArray {
  val positions = mutableListOf<Int>()
  forEachQuestionMark(sql) { index, isEscape -> if (!isEscape) positions.add(index) }
  return positions.toIntArray()
}

/**
 * Rewrites [sql] so that PostgreSQL's parser accepts it and every other token keeps its offset.
 *
 * Each operator token is rewritten in place: `??` becomes `?`, a placeholder `?` becomes `0`, other characters are
 * kept, and spaces at the end of the token restore its length. Text outside operator tokens is unchanged, as is text
 * from the point where the lexer rejects [sql] onward.
 *
 * @return A string of the same length as [sql].
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun replaceParameterPlaceholdersKeepingOffsets(sql: String): String {
  val characters = sql.toCharArray()
  for (token in operatorTokens(sql)) {
    var written = token.start
    var read = token.start
    forEachQuestionMark(sql, token) { index, isEscape ->
      while (read < index) characters[written++] = sql[read++]
      characters[written++] = if (isEscape) '?' else '0'
      read = index + if (isEscape) 2 else 1
    }
    while (read < token.end) characters[written++] = sql[read++]
    characters.fill(' ', written, token.end)
  }
  return String(characters)
}

/**
 * Splits [sql] into PostgreSQL lexer tokens, stopping where the lexer rejects the text.
 *
 * When the lexer reports an error at a position, the tokens before that position are returned and the text from
 * there on has no tokens. An error without a position leaves the whole text without tokens.
 *
 * @throws IllegalArgumentException if [sql] contains a NUL character.
 */
internal fun scanLeadingTokens(sql: String): List<ScanToken> = try {
  LibPgQuery.scan(sql)
} catch (exception: LibPgQueryException) {
  val cursorIndex = exception.cursorIndex
  if (cursorIndex == null || cursorIndex !in sql.indices) emptyList() else scanLeadingTokens(sql.take(cursorIndex))
}

private fun operatorTokens(sql: String): List<ScanToken> = scanLeadingTokens(sql).filter {
  it.token ==
    TokenNumber.OPERATOR
}

private inline fun forEachQuestionMark(
  sql: String,
  token: ScanToken,
  action: (index: Int, isEscape: Boolean) -> Unit,
) {
  var index = token.start
  while (index < token.end) {
    if (sql[index] != '?') {
      index++
      continue
    }
    val isEscape = index + 1 < token.end && sql[index + 1] == '?'
    action(index, isEscape)
    index += if (isEscape) 2 else 1
  }
}

private inline fun forEachQuestionMark(sql: String, action: (index: Int, isEscape: Boolean) -> Unit) {
  for (token in operatorTokens(sql)) forEachQuestionMark(sql, token, action)
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
