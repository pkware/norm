package norm.generator

import com.google.gson.JsonArray
import com.google.gson.JsonObject

private const val OPERATOR_CHARACTERS = "~!@#^&|`?+-*/%<>="
private val DATA_MODIFYING_STATEMENTS = listOf("InsertStmt", "UpdateStmt", "DeleteStmt", "MergeStmt")

/** A token from `pg_query_scan` with its source text decoded from the UTF-8 byte offsets. */
private class SourceToken(val start: Int, val end: Int, val text: String) {
  val isComment get() = text.startsWith("--") || text.startsWith("/*")
}

/**
 * [parseSelectItems] on libpg_query: the item list comes from the parse tree and the item text extents from
 * `pg_query_scan` token offsets. Returns an empty list when libpg_query rejects [sql].
 */
internal fun libPgQueryParseSelectItems(sql: String): List<SelectItem> {
  val parseable = replacePlaceholdersKeepingOffsets(sql) ?: return emptyList()
  val bytes = parseable.toByteArray(Charsets.UTF_8)
  val originalBytes = sql.toByteArray(Charsets.UTF_8)
  val tokens = scanSourceTokens(parseable, bytes)?.filterNot { it.isComment } ?: return emptyList()
  val tree = parseOrNull(parseable) ?: return emptyList()
  val outputList = outputList(tree) ?: return emptyList()
  val targets = outputList.map { it.asJsonObject["ResTarget"].asJsonObject }
  if (targets.isEmpty() || targets.any { it["location"].asInt < 0 }) return emptyList()
  if (isSetOperationWithParenthesizedBranch(tree, tokens, targets.first()["location"].asInt)) return emptyList()

  val items = targets.indices.map { index ->
    val lastTokenIndex = lastTokenOfItem(bytes, tokens, targets, index, outputList) ?: return emptyList()
    buildItem(
      bytes,
      originalBytes,
      tokens,
      targets[index],
      tokenIndexAt(tokens, targets[index]["location"].asInt),
      lastTokenIndex,
    )
      ?: return emptyList()
  }
  val firstStarIndex = targets.indexOfFirst { isStar(it["val"].asJsonObject) }
  return if (firstStarIndex < 0 || items.size == 1) items else items.take(firstStarIndex)
}

private fun parseOrNull(sql: String): JsonObject? = try {
  LibPgQuery.parseJson(sql)
} catch (_: LibPgQueryError) {
  null
}

private fun scanSourceTokens(sql: String, bytes: ByteArray): List<SourceToken>? = try {
  LibPgQuery.scan(sql).map { SourceToken(it.start, it.end, String(bytes, it.start, it.end - it.start, Charsets.UTF_8)) }
} catch (_: LibPgQueryError) {
  null
}

/**
 * libpg_query's grammar has no `?` placeholder, so each placeholder becomes the one-character literal `0`.
 * Offsets stay valid. A pgjdbc `??` escape becomes ` ?`, which the lexer reads as the operator character.
 * Returns `null` when the lexer rejects [sql].
 */
internal fun replacePlaceholdersKeepingOffsets(sql: String): String? {
  if ('?' !in sql) return sql
  val original = sql.toByteArray(Charsets.UTF_8)
  val rewritten = original.copyOf()
  for (token in scanSourceTokens(sql, original) ?: return null) {
    if ('?' !in token.text || token.text.any { it !in OPERATOR_CHARACTERS }) continue
    var position = token.start
    while (position < token.end) {
      if (original[position] != '?'.code.toByte()) {
        position++
      } else if (position + 1 < token.end && original[position + 1] == '?'.code.toByte()) {
        rewritten[position] = ' '.code.toByte()
        position += 2
      } else {
        rewritten[position] = '0'.code.toByte()
        position++
      }
    }
  }
  return String(rewritten, Charsets.UTF_8)
}

private fun outputList(tree: JsonObject): JsonArray? {
  val statement = tree["stmts"].asJsonArray.firstOrNull()?.asJsonObject?.get("stmt")?.asJsonObject ?: return null
  statement["SelectStmt"]?.let { return leftmostSelect(it.asJsonObject)["targetList"]?.asJsonArray }
  val dataModifying = DATA_MODIFYING_STATEMENTS.firstNotNullOfOrNull { statement[it] } ?: return null
  return dataModifying.asJsonObject["returningClause"]?.asJsonObject?.get("exprs")?.asJsonArray
}

private fun leftmostSelect(select: JsonObject): JsonObject {
  var current = select
  while (current["op"]?.asString != "SETOP_NONE" && current["larg"] != null) current = current["larg"].asJsonObject
  return current
}

private fun isSetOperationWithParenthesizedBranch(
  tree: JsonObject,
  tokens: List<SourceToken>,
  firstItemStart: Int,
): Boolean {
  val select = tree["stmts"].asJsonArray.first().asJsonObject["stmt"].asJsonObject["SelectStmt"]?.asJsonObject
  if (select == null || select["op"]?.asString == "SETOP_NONE") return false
  var depth = 0
  for (index in tokenIndexAt(tokens, firstItemStart) - 1 downTo 0) {
    when {
      tokens[index].text == ")" -> depth++
      tokens[index].text == "(" && depth > 0 -> depth--
      tokens[index].text.equals("SELECT", ignoreCase = true) && depth == 0 ->
        return index > 0 && tokens[index - 1].text == "("
    }
  }
  return false
}

private fun tokenIndexAt(tokens: List<SourceToken>, start: Int): Int = tokens.indexOfFirst { it.start == start }

/**
 * Index of the last token of item [index]. Inner items end before the comma that precedes the next item's
 * `location`. The last item has no such marker, so its end is the shortest token prefix whose re-parse yields the
 * same output list.
 */
private fun lastTokenOfItem(
  bytes: ByteArray,
  tokens: List<SourceToken>,
  targets: List<JsonObject>,
  index: Int,
  outputList: JsonArray,
): Int? {
  val startIndex = tokenIndexAt(tokens, targets[index]["location"].asInt)
  if (startIndex < 0) return null
  if (index < targets.lastIndex) {
    val nextIndex = tokenIndexAt(tokens, targets[index + 1]["location"].asInt)
    return if (nextIndex >= 2 && tokens[nextIndex - 1].text == ",") nextIndex - 2 else null
  }
  val baseDepth = parenthesisDepth(tokens.subList(0, startIndex))
  var depth = baseDepth
  for (candidate in startIndex until tokens.size) {
    depth += parenthesisDelta(tokens[candidate].text)
    if (depth != baseDepth) continue
    val prefix = String(bytes, 0, tokens[candidate].end, Charsets.UTF_8) + ")".repeat(baseDepth)
    val reparsed = parseOrNull(prefix)?.let(::outputList)
    if (reparsed == outputList) return candidate
  }
  return null
}

private fun parenthesisDepth(tokens: List<SourceToken>): Int = tokens.sumOf { parenthesisDelta(it.text) }

private fun parenthesisDelta(text: String): Int = when (text) {
  "(" -> 1
  ")" -> -1
  else -> 0
}

private fun buildItem(
  bytes: ByteArray,
  originalBytes: ByteArray,
  tokens: List<SourceToken>,
  target: JsonObject,
  startIndex: Int,
  lastIndex: Int,
): SelectItem? {
  val start = tokens[startIndex].start
  val afterItem = tokens.getOrNull(lastIndex + 1)?.start ?: bytes.size
  val aliasIndex = if (target["name"] != null) lastIndex else -1
  val hasAsKeyword = aliasIndex > startIndex && tokens[aliasIndex - 1].text.equals("AS", ignoreCase = true)
  val expressionEnd = if (hasAsKeyword) tokens[aliasIndex - 1].start else afterItem
  val expression = String(originalBytes, start, expressionEnd - start, Charsets.UTF_8).trim()
  val expressionTokens = tokens.subList(
    startIndex,
    tokens.indexOfFirst { it.start >= expressionEnd }.let {
      if (it <
        0
      ) {
        tokens.size
      } else {
        it
      }
    },
  )
  val noMatch = SelectItem(expression = expression, columnName = null, tableName = null)
  if (aliasIndex >= 0 && !hasAsKeyword) return noMatch
  val value = target["val"].asJsonObject
  val columnReference = value["ColumnRef"]?.asJsonObject ?: return noMatch
  val names = columnReference["fields"].asJsonArray.map {
    it.asJsonObject["String"]?.asJsonObject?.get("sval")?.asString
  }
  if (names.size !in 1..2 || names.any { it == null }) return noMatch
  if (expressionTokens.size != 2 * names.size - 1 || expressionTokens.any { it.text.startsWith("U&") }) return noMatch
  return SelectItem(
    expression = expression,
    columnName = names.last(),
    tableName = names.firstOrNull().takeIf { names.size == 2 },
    isColumnNameQuoted = expressionTokens.last().text.startsWith('"'),
    isTableNameQuoted = names.size == 2 && expressionTokens.first().text.startsWith('"'),
  )
}

private fun isStar(value: JsonObject): Boolean {
  value["ColumnRef"]?.let { return it.asJsonObject["fields"].asJsonArray.last().asJsonObject["A_Star"] != null }
  value["A_Indirection"]?.let {
    return it.asJsonObject["indirection"].asJsonArray.last().asJsonObject["A_Star"] != null
  }
  return false
}
