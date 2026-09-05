package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
      // The old hand-rolled scanner searched for the first "*/" from the opening "/*", so it read
      // this comment as ending at the inner close and would have converted the "?" that follows.
      // skipLexicalToken honors PostgreSQL's documented nesting-depth semantics instead, treating
      // the whole span as a single comment that only ends at the "*/" bringing the depth back to
      // zero -- this is an accepted behavior change, matching PostgreSQL's own nested comments.
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
