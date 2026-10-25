package norm.generator.baseline

/**
 * A Kotlin string literal found in source text.
 *
 * @property line 1-based line of the opening quote.
 * @property value the literal's text, with escapes decoded and, for a raw literal followed by `.trimIndent()`, the
 *   indentation removed. `$name` and `${...}` templates stay as written.
 */
internal data class KotlinStringLiteral(val line: Int, val value: String)

/**
 * Extracts the string literals of Kotlin [source], skipping comments, character literals and backticked names.
 *
 * Handles `"..."` literals with the escapes `\" \$ \\ \n \t \r \uXXXX`, and `"""..."""` literals.
 *
 * @throws IllegalArgumentException if a regular literal holds any other escape or a literal is unterminated.
 */
internal fun lexKotlinStringLiterals(source: String): List<KotlinStringLiteral> = KotlinStringLiteralLexer(source).lex()

private class KotlinStringLiteralLexer(private val source: String) {
  private var index = 0

  fun lex(): List<KotlinStringLiteral> {
    val literals = mutableListOf<KotlinStringLiteral>()
    while (index < source.length) {
      when {
        source.startsWith("//", index) -> skipLineComment()
        source.startsWith("/*", index) -> skipBlockComment()
        source.startsWith("\"\"\"", index) -> literals += readRawLiteral()
        source[index] == '"' -> literals += readRegularLiteral()
        source[index] == '\'' -> skipCharacterLiteral()
        source[index] == '`' -> skipBackticked()
        else -> index++
      }
    }
    return literals
  }

  private fun lineAt(position: Int): Int = source.substring(0, position).count { it == '\n' } + 1

  private fun skipLineComment() {
    while (index < source.length && source[index] != '\n') index++
  }

  private fun skipBlockComment() {
    var depth = 0
    do {
      when {
        source.startsWith("/*", index) -> {
          depth++
          index += 2
        }
        source.startsWith("*/", index) -> {
          depth--
          index += 2
        }
        else -> index++
      }
    } while (depth > 0 && index < source.length)
    require(depth == 0) { "Unterminated block comment" }
  }

  private fun skipCharacterLiteral() {
    index++
    while (source[index] != '\'') index += if (source[index] == '\\') 2 else 1
    index++
  }

  private fun skipBackticked() {
    index = source.indexOf('`', index + 1) + 1
    require(index > 0) { "Unterminated backticked name" }
  }

  private fun readRegularLiteral(): KotlinStringLiteral {
    val line = lineAt(index)
    index++
    val value = StringBuilder()
    while (true) {
      require(index < source.length) { "Unterminated literal at line $line" }
      when (val character = source[index]) {
        '"' -> {
          index++
          return KotlinStringLiteral(line, value.toString())
        }
        '\\' -> value.append(readEscape(line))
        '$' -> if (source.startsWith("\${", index)) copyBracedTemplate(value) else value.append(source[index++])
        else -> {
          value.append(character)
          index++
        }
      }
    }
  }

  private fun readEscape(line: Int): String {
    val escape = source[index + 1]
    index += 2
    return when (escape) {
      '"', '$', '\\' -> escape.toString()
      'n' -> "\n"
      't' -> "\t"
      'r' -> "\r"
      'u' -> {
        val digits = source.substring(index, index + UNICODE_ESCAPE_DIGITS)
        index += UNICODE_ESCAPE_DIGITS
        digits.toInt(HEX_RADIX).toChar().toString()
      }
      else -> throw IllegalArgumentException("Unsupported escape \\$escape at line $line")
    }
  }

  private fun readRawLiteral(): KotlinStringLiteral {
    val line = lineAt(index)
    index += 3
    val value = StringBuilder()
    while (true) {
      require(index < source.length) { "Unterminated literal at line $line" }
      when {
        source.startsWith("\"\"\"", index) -> {
          index += 3
          while (index < source.length && source[index] == '"') value.append(source[index++])
          return KotlinStringLiteral(
            line,
            if (followedByTrimIndent()) value.toString().trimIndent() else value.toString(),
          )
        }
        source.startsWith("\${", index) -> copyBracedTemplate(value)
        else -> value.append(source[index++])
      }
    }
  }

  private fun followedByTrimIndent(): Boolean {
    var position = index
    while (position < source.length && source[position].isWhitespace()) position++
    return source.startsWith(TRIM_INDENT_CALL, position)
  }

  private fun copyBracedTemplate(into: StringBuilder) {
    val start = index
    index += 2
    var depth = 1
    while (depth > 0) {
      require(index < source.length) { "Unterminated template at line ${lineAt(start)}" }
      when {
        source.startsWith("\"\"\"", index) -> readRawLiteral()
        source[index] == '"' -> readRegularLiteral()
        source[index] == '{' -> {
          depth++
          index++
        }
        source[index] == '}' -> {
          depth--
          index++
        }
        else -> index++
      }
    }
    into.append(source, start, index)
  }

  private companion object {
    const val TRIM_INDENT_CALL = ".trimIndent()"
    const val UNICODE_ESCAPE_DIGITS = 4
    const val HEX_RADIX = 16
  }
}
