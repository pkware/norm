package norm.generator

/**
 * A minimal, generic JSON value model and parser.
 *
 * PostgreSQL's `EXPLAIN (FORMAT JSON)` output is the only JSON this generator ever reads. JSON's
 * grammar is small, deterministic, and unambiguous — unlike SQL, which is why the codebase's own
 * "no hand-rolled SQL text scanning" concern does not apply here: this is a REAL, complete parser
 * for the format, not an ad-hoc scanner for specific field names inside SQL text.
 *
 * Only the value shapes this generator's own EXPLAIN consumers need are modeled: objects
 * (`Map<String, JsonValue>`), arrays, strings, and everything else (numbers, booleans, `null`)
 * folded into a single [JsonScalar] carrying its raw source text, since none of today's callers
 * need numeric or boolean values as anything other than "not an object or array".
 */
internal sealed interface JsonValue {
  data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue
  data class JsonArray(val items: List<JsonValue>) : JsonValue
  data class JsonString(val value: String) : JsonValue
  data class JsonScalar(val rawText: String) : JsonValue

  companion object {
    /**
     * Parses [text] as a single JSON value.
     *
     * @throws IllegalArgumentException if [text] is not valid JSON. Callers analyzing `EXPLAIN`
     *   output should treat this the same as any other unexpected-shape failure: fall back to the
     *   safe (nullable) default, never propagate it as a build failure.
     */
    fun parse(text: String): JsonValue {
      val parser = JsonParser(text)
      val value = parser.parseValue()
      parser.skipWhitespace()
      require(parser.isAtEnd()) { "Unexpected trailing content in JSON at index ${parser.index}" }
      return value
    }
  }
}

private class JsonParser(private val text: String) {
  var index = 0
    private set

  fun isAtEnd(): Boolean = index >= text.length

  fun skipWhitespace() {
    while (index < text.length && text[index].isWhitespace()) index++
  }

  fun parseValue(): JsonValue {
    skipWhitespace()
    require(index < text.length) { "Unexpected end of JSON input" }
    return when (text[index]) {
      '{' -> parseObject()
      '[' -> parseArray()
      '"' -> JsonValue.JsonString(parseStringLiteral())
      else -> parseScalar()
    }
  }

  private fun parseObject(): JsonValue.JsonObject {
    expect('{')
    val fields = linkedMapOf<String, JsonValue>()
    skipWhitespace()
    if (peekIs('}')) {
      index++
      return JsonValue.JsonObject(fields)
    }
    while (true) {
      skipWhitespace()
      val key = parseStringLiteral()
      skipWhitespace()
      expect(':')
      val value = parseValue()
      fields[key] = value
      skipWhitespace()
      when {
        peekIs(',') -> index++
        peekIs('}') -> {
          index++
          return JsonValue.JsonObject(fields)
        }
        else -> throw IllegalArgumentException("Expected ',' or '}' in JSON object at index $index")
      }
    }
  }

  private fun parseArray(): JsonValue.JsonArray {
    expect('[')
    val items = mutableListOf<JsonValue>()
    skipWhitespace()
    if (peekIs(']')) {
      index++
      return JsonValue.JsonArray(items)
    }
    while (true) {
      items.add(parseValue())
      skipWhitespace()
      when {
        peekIs(',') -> index++
        peekIs(']') -> {
          index++
          return JsonValue.JsonArray(items)
        }
        else -> throw IllegalArgumentException("Expected ',' or ']' in JSON array at index $index")
      }
    }
  }

  private fun parseStringLiteral(): String {
    expect('"')
    val result = StringBuilder()
    while (true) {
      require(index < text.length) { "Unterminated JSON string starting before index $index" }
      val character = text[index]
      when {
        character == '"' -> {
          index++
          return result.toString()
        }
        character == '\\' -> {
          index++
          require(index < text.length) { "Unterminated JSON escape at index $index" }
          when (val escaped = text[index]) {
            '"' -> result.append('"')
            '\\' -> result.append('\\')
            '/' -> result.append('/')
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
              require(index + 4 < text.length) { "Truncated JSON unicode escape at index $index" }
              val hex = text.substring(index + 1, index + 5)
              result.append(hex.toInt(16).toChar())
              index += 4
            }
            else -> throw IllegalArgumentException("Unknown JSON escape '\\$escaped' at index $index")
          }
          index++
        }
        else -> {
          result.append(character)
          index++
        }
      }
    }
  }

  private fun parseScalar(): JsonValue.JsonScalar {
    val start = index
    while (index < text.length && text[index] !in ",]}".toCharArray() && !text[index].isWhitespace()) {
      index++
    }
    require(index > start) { "Expected a JSON value at index $index" }
    return JsonValue.JsonScalar(text.substring(start, index))
  }

  private fun peekIs(character: Char): Boolean = index < text.length && text[index] == character

  private fun expect(character: Char) {
    require(peekIs(character)) { "Expected '$character' at index $index" }
    index++
  }
}
