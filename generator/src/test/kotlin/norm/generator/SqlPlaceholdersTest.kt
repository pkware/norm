package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import norm.generator.libpgquery.LibPgQuery
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

private val TRAILING_PLACEHOLDER_QUERIES = listOf(
  "SELECT '?', ?",
  "SELECT E'a\\'?', ?",
  "SELECT \$\$?\$\$, ?",
  "SELECT \$tag\$?\$tag\$, ?",
  "SELECT \"a?b\", ?",
  "SELECT 1 -- ?\n, ?",
  "SELECT /* ? */ ?",
  "SELECT a =?",
  "SELECT a >=?",
  "SELECT a =-?",
)

private val UNTERMINATED_QUERIES = listOf(
  "SELECT ?, '?",
  "SELECT ?, E'\\'?",
  "SELECT ?, \$\$?",
  "SELECT ?, \$tag\$?\$\$",
  "SELECT ?, \"?",
  "SELECT ?, /* ?",
)

private val DOUBLED_QUESTION_MARK_QUERIES = listOf(
  "SELECT j ?? 'k', ?",
  "SELECT j =?? 'k'",
  "SELECT j ??= 'k'",
  "SELECT j ??| 'k'",
)

class SqlPlaceholdersTest {

  @Nested
  inner class ReplaceParameterPlaceholders {

    @Test
    fun `replaces single placeholder`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE id = ?") { "NULL" }
      assertThat(result).isEqualTo("SELECT * FROM t WHERE id = NULL")
    }

    @Test
    fun `replaces multiple placeholders`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE a = ? AND b = ?") { "NULL" }
      assertThat(result).isEqualTo("SELECT * FROM t WHERE a = NULL AND b = NULL")
    }

    @Test
    fun `preserves question mark inside single-quoted string`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE note = 'really?' AND id = ?") { "NULL" }
      assertThat(result).isEqualTo("SELECT * FROM t WHERE note = 'really?' AND id = NULL")
    }

    @Test
    fun `preserves question mark inside escaped string literal`() {
      val result =
        replaceParameterPlaceholders("SELECT * FROM t WHERE note = 'it''s a ? mark' AND id = ?") { "NULL" }
      assertThat(result).isEqualTo("SELECT * FROM t WHERE note = 'it''s a ? mark' AND id = NULL")
    }

    @Test
    fun `preserves question mark inside line comment`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t -- why?\nWHERE id = ?") { "NULL" }
      assertThat(result).isEqualTo("SELECT * FROM t -- why?\nWHERE id = NULL")
    }

    @Test
    fun `preserves question mark inside block comment`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t /* what? */ WHERE id = ?") { "NULL" }
      assertThat(result).isEqualTo("SELECT * FROM t /* what? */ WHERE id = NULL")
    }

    @Test
    fun `a doubled question mark is one literal question mark and not a placeholder`() {
      val result = replaceParameterPlaceholders("SELECT j ?? 'k' FROM t WHERE a = ? AND id = ?") { "\$${it + 1}" }
      assertThat(result).isEqualTo("SELECT j ? 'k' FROM t WHERE a = \$1 AND id = \$2")
    }

    @Test
    fun `a tripled question mark is a literal question mark followed by a placeholder`() {
      val result = replaceParameterPlaceholders("SELECT j ??? 1") { "\$${it + 1}" }
      assertThat(result).isEqualTo("SELECT j ?\$1 1")
    }

    @Test
    fun `a doubled question mark inside a string literal or comment is left alone`() {
      val sql = "SELECT '??' /* ?? */ -- ??\n, ?"
      assertThat(replaceParameterPlaceholders(sql) { "\$${it + 1}" }).isEqualTo("SELECT '??' /* ?? */ -- ??\n, \$1")
    }

    @Test
    fun `no placeholders returns unchanged`() {
      val sql = "SELECT * FROM department"
      val result = replaceParameterPlaceholders(sql) { "NULL" }
      assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `unclosed string literal preserves content without replacing`() {
      // Valid SQL never has unclosed literals, but the function should not crash
      val result = replaceParameterPlaceholders("SELECT '?") { "NULL" }
      assertThat(result).isEqualTo("SELECT '?")
    }

    @Test
    fun `unclosed block comment preserves content without replacing`() {
      val result = replaceParameterPlaceholders("SELECT /* ?") { "NULL" }
      assertThat(result).isEqualTo("SELECT /* ?")
    }

    @Test
    fun `preserves question mark inside a dollar-quoted string`() {
      val result = replaceParameterPlaceholders("SELECT \$\$a ? b\$\$ WHERE id = ?") { "NULL" }
      assertThat(result).isEqualTo("SELECT \$\$a ? b\$\$ WHERE id = NULL")
    }

    @Test
    fun `preserves question mark inside a tagged dollar-quoted string`() {
      val result = replaceParameterPlaceholders("SELECT \$tag\$a ? b\$tag\$ WHERE id = ?") { "NULL" }
      assertThat(result).isEqualTo("SELECT \$tag\$a ? b\$tag\$ WHERE id = NULL")
    }

    @Test
    fun `preserves question mark inside a quoted identifier`() {
      val result = replaceParameterPlaceholders("""SELECT "quoted?identifier" WHERE id = ?""") { "NULL" }
      assertThat(result).isEqualTo("""SELECT "quoted?identifier" WHERE id = NULL""")
    }

    @Test
    fun `preserves question mark inside an E-string escape sequence`() {
      val result = replaceParameterPlaceholders("""SELECT E'a\'?b' WHERE id = ?""") { "NULL" }
      assertThat(result).isEqualTo("""SELECT E'a\'?b' WHERE id = NULL""")
    }

    @Test
    fun `question mark after the inner close of a nested block comment is left alone`() {
      val result = replaceParameterPlaceholders("SELECT /* a /* b */ c ? */ 1") { "NULL" }
      assertThat(result).isEqualTo("SELECT /* a /* b */ c ? */ 1")
    }

    @Test
    fun `replacement lambda receives 0-based parameter indices in order`() {
      val observedIndices = mutableListOf<Int>()
      val result = replaceParameterPlaceholders("SELECT ?, ?, ?") { index ->
        observedIndices.add(index)
        "\$$index"
      }
      assertThat(observedIndices).isEqualTo(listOf(0, 1, 2))
      assertThat(result).isEqualTo("SELECT \$0, \$1, \$2")
    }

    @Test
    fun `fewer sentinels than placeholders falls back per the lambda`() {
      val sentinels = listOf("0::int4")
      val result = replaceParameterPlaceholders("SELECT digest(?, ?)") { index ->
        sentinels.getOrElse(index) { "NULL" }
      }
      assertThat(result).isEqualTo("SELECT digest(0::int4, NULL)")
    }
  }

  @Nested
  inner class PlaceholderPositionsTest {

    @Test
    fun `finds each top-level placeholder position`() {
      val sql = "SELECT a FROM t WHERE a = ? AND b = ?"
      val result = placeholderPositions(sql)
      assertThat(result.toList()).isEqualTo(listOf(sql.indexOf('?'), sql.lastIndexOf('?')))
    }

    @Test
    fun `excludes a question mark inside a string literal`() {
      val sql = "SELECT * FROM t WHERE note = 'ok?' AND id = ?"
      val result = placeholderPositions(sql)
      assertThat(result.toList()).isEqualTo(listOf(sql.lastIndexOf('?')))
    }

    @Test
    fun `excludes a question mark inside a line comment`() {
      val sql = "SELECT * FROM t -- why?\nWHERE id = ?"
      val result = placeholderPositions(sql)
      assertThat(result.toList()).isEqualTo(listOf(sql.lastIndexOf('?')))
    }

    @Test
    fun `excludes both question marks of a doubled question mark`() {
      val sql = "SELECT j ?? 'k' FROM t WHERE id = ?"
      assertThat(placeholderPositions(sql).toList()).isEqualTo(listOf(sql.lastIndexOf('?')))
    }

    @Test
    fun `counts the third question mark of a tripled question mark`() {
      val sql = "SELECT j ??? 1"
      assertThat(placeholderPositions(sql).toList()).isEqualTo(listOf(sql.lastIndexOf('?')))
    }

    @Test
    fun `returns an empty array when there are no placeholders`() {
      assertThat(placeholderPositions("SELECT * FROM t").toList()).isEqualTo(emptyList<Int>())
    }
  }

  @Nested
  inner class PlaceholderLocation {

    @ParameterizedTest
    @MethodSource("norm.generator.SqlPlaceholdersTestKt#trailingPlaceholderQueries")
    fun `a trailing placeholder is found after a question mark that is not one`(sql: String) {
      assertThat(placeholderPositions(sql).toList()).isEqualTo(listOf(sql.length - 1))
      assertThat(replaceParameterPlaceholders(sql) { "\$${it + 1}" }).isEqualTo(sql.dropLast(1) + "\$1")
    }

    @ParameterizedTest
    @MethodSource("norm.generator.SqlPlaceholdersTestKt#unterminatedQueries")
    fun `a placeholder before an unterminated token is found and the token is left alone`(sql: String) {
      assertThat(placeholderPositions(sql).toList()).isEqualTo(listOf(sql.indexOf('?')))
      assertThat(replaceParameterPlaceholders(sql) { "\$${it + 1}" })
        .isEqualTo("SELECT \$1" + sql.removePrefix("SELECT ?"))
    }

    @Test
    fun `text after a lexer error is left alone`() {
      val sql = "SELECT ?, 1a, ?"
      assertThat(placeholderPositions(sql).toList()).isEqualTo(listOf(7))
      assertThat(replaceParameterPlaceholders(sql) { "\$${it + 1}" }).isEqualTo("SELECT \$1, 1a, ?")
    }

    @Test
    fun `a doubled question mark glued to operator characters is one literal question mark`() {
      val sql = "SELECT j =?? 'k' AND j ??| 'k' AND j ??= 'k' AND id = ?"
      assertThat(placeholderPositions(sql).toList()).isEqualTo(listOf(sql.lastIndexOf('?')))
      assertThat(replaceParameterPlaceholders(sql) { "\$${it + 1}" })
        .isEqualTo("SELECT j =? 'k' AND j ?| 'k' AND j ?= 'k' AND id = \$1")
    }

    @Test
    fun `a question mark ahead of a comment is a placeholder`() {
      val sql = "SELECT ?--c\n, ??--c"
      assertThat(placeholderPositions(sql).toList()).isEqualTo(listOf(7))
      assertThat(replaceParameterPlaceholders(sql) { "\$${it + 1}" }).isEqualTo("SELECT \$1--c\n, ?--c")
    }
  }

  @Nested
  inner class ReplaceParameterPlaceholdersKeepingOffsets {

    @ParameterizedTest
    @MethodSource("norm.generator.SqlPlaceholdersTestKt#allQueries")
    fun `keeps the length and writes zero at each placeholder`(sql: String) {
      val result = replaceParameterPlaceholdersKeepingOffsets(sql)
      assertThat(result.length).isEqualTo(sql.length)
      for (position in placeholderPositions(sql)) assertThat(result[position]).isEqualTo('0')
    }

    @ParameterizedTest
    @MethodSource("norm.generator.SqlPlaceholdersTestKt#parseableQueries")
    fun `result parses`(sql: String) {
      LibPgQuery.parse(replaceParameterPlaceholdersKeepingOffsets(sql))
    }

    @Test
    fun `an operator token shrinks by its doubled question marks and is padded with spaces`() {
      assertThat(replaceParameterPlaceholdersKeepingOffsets("a =?? b")).isEqualTo("a =?  b")
      assertThat(replaceParameterPlaceholdersKeepingOffsets("a ??= b")).isEqualTo("a ?=  b")
      assertThat(replaceParameterPlaceholdersKeepingOffsets("a ??| b")).isEqualTo("a ?|  b")
      assertThat(replaceParameterPlaceholdersKeepingOffsets("a ??? b")).isEqualTo("a ?0  b")
      assertThat(replaceParameterPlaceholdersKeepingOffsets("a =-? b")).isEqualTo("a =-0 b")
    }

    @Test
    fun `text outside operator tokens is unchanged`() {
      val sql = "SELECT '??' /* ? */ -- ??\n, \"a?\", \$\$?\$\$, 1"
      assertThat(replaceParameterPlaceholdersKeepingOffsets(sql)).isEqualTo(sql)
    }

    @Test
    fun `text after a lexer error is unchanged`() {
      val sql = "SELECT ?, 1a, ?"
      assertThat(replaceParameterPlaceholdersKeepingOffsets(sql)).isEqualTo("SELECT 0, 1a, ?")
    }

    @Test
    fun `select item locations match the original text`() {
      val sql = "SELECT j ?? 'k' AS flag, ? AS x, b FROM t"
      val tree = LibPgQuery.parse(replaceParameterPlaceholdersKeepingOffsets(sql))
      assertThat(resultTargetLocations(tree))
        .isEqualTo(listOf(sql.indexOf("j ??"), sql.indexOf("? AS x"), sql.indexOf("b FROM")))
    }

    private fun resultTargetLocations(value: JsonValue): List<Int> = when (value) {
      is JsonValue.JsonObject -> value.fields.flatMap { (key, child) ->
        if (key == "ResTarget" && child is JsonValue.JsonObject) {
          listOfNotNull(child.intField("location"))
        } else {
          resultTargetLocations(child)
        }
      }
      is JsonValue.JsonArray -> value.items.flatMap(::resultTargetLocations)
      else -> emptyList()
    }
  }

  @Nested
  inner class NonNullSentinel {

    @Test
    fun `integer types produce zero`() {
      assertThat(nonNullSentinel("int4")).isEqualTo("0::int4")
      assertThat(nonNullSentinel("int8")).isEqualTo("0::int8")
    }

    @Test
    fun `text types produce empty string`() {
      assertThat(nonNullSentinel("text")).isEqualTo("''::text")
      assertThat(nonNullSentinel("varchar")).isEqualTo("''::varchar")
    }

    @Test
    fun `boolean produces false`() {
      assertThat(nonNullSentinel("bool")).isEqualTo("false::bool")
    }

    @Test
    fun `bytea produces non-null value`() {
      assertThat(nonNullSentinel("bytea")).isEqualTo("'\\x00'::bytea")
    }

    @Test
    fun `array types produce empty array`() {
      assertThat(nonNullSentinel("_int4")).isEqualTo("ARRAY[]::_int4")
      assertThat(nonNullSentinel("_text")).isEqualTo("ARRAY[]::_text")
    }

    @Test
    fun `unknown types fall back to NULL with cast`() {
      assertThat(nonNullSentinel("custom_type")).isEqualTo("NULL::custom_type")
    }

    @Test
    fun `temporal types produce valid literals`() {
      assertThat(nonNullSentinel("date")).isEqualTo("'2000-01-01'::date")
      assertThat(nonNullSentinel("timestamp")).isEqualTo("'2000-01-01'::timestamp")
      assertThat(nonNullSentinel("uuid")).isEqualTo("'00000000-0000-0000-0000-000000000000'::uuid")
    }
  }
}

fun trailingPlaceholderQueries(): List<String> = TRAILING_PLACEHOLDER_QUERIES

fun unterminatedQueries(): List<String> = UNTERMINATED_QUERIES

fun allQueries(): List<String> = TRAILING_PLACEHOLDER_QUERIES + UNTERMINATED_QUERIES + DOUBLED_QUESTION_MARK_QUERIES

fun parseableQueries(): List<String> = TRAILING_PLACEHOLDER_QUERIES + DOUBLED_QUESTION_MARK_QUERIES + "SELECT j ???"
