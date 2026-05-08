package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlUtilsTest {

  @Nested
  inner class ParseSelectItems {

    @Test
    fun `UPDATE RETURNING with simple columns`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id, name")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `UPDATE RETURNING with qualified alias`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING tier, old.tier AS old_tier")
      assertThat(result).containsExactly(
        SelectItem("tier", "tier", null),
        SelectItem("old.tier", "tier", "old"),
      )
    }

    @Test
    fun `INSERT RETURNING`() {
      val result = parseSelectItems("INSERT INTO t(x) VALUES(1) RETURNING id, name")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `DELETE RETURNING`() {
      val result = parseSelectItems("DELETE FROM t WHERE id = 1 RETURNING id, name")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `RETURNING with computed expression`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id, count(*) AS total")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("count(*)", null, null),
      )
    }

    @Test
    fun `RETURNING with trailing semicolon`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id, name;")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `RETURNING with CAST does not confuse inner AS with alias AS`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING CAST(tier AS text) AS tier_text")
      assertThat(result).containsExactly(
        SelectItem("CAST(tier AS text)", null, null),
      )
    }

    @Test
    fun `RETURNING star`() {
      val result = parseSelectItems("DELETE FROM t RETURNING *")
      assertThat(result).containsExactly(
        SelectItem("*", null, null),
      )
    }

    @Test
    fun `SELECT with simple columns`() {
      val result = parseSelectItems("SELECT id, name FROM users")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `SELECT with qualified alias`() {
      val result = parseSelectItems("SELECT author.name AS author_name FROM author")
      assertThat(result).containsExactly(
        SelectItem("author.name", "name", "author"),
      )
    }

    @Test
    fun `SELECT with computed expression`() {
      val result = parseSelectItems("SELECT COUNT(*) AS total FROM users")
      assertThat(result).containsExactly(
        SelectItem("COUNT(*)", null, null),
      )
    }

    @Test
    fun `SELECT star`() {
      val result = parseSelectItems("SELECT * FROM users")
      assertThat(result).containsExactly(
        SelectItem("*", null, null),
      )
    }

    @Test
    fun `no SELECT or RETURNING returns empty list`() {
      val result = parseSelectItems("CALL my_proc()")
      assertThat(result).isEmpty()
    }
  }

  @Test
  fun `splits simple comma-separated items`() {
    val result = splitAtTopLevel("a, b, c", ',')
    assertThat(result).containsExactly("a", " b", " c")
  }

  @Test
  fun `preserves content inside parentheses`() {
    val result = splitAtTopLevel("func(a, b), c", ',')
    assertThat(result).containsExactly("func(a, b)", " c")
  }

  @Test
  fun `handles nested parentheses`() {
    val result = splitAtTopLevel("outer(inner(a, b), c), d", ',')
    assertThat(result).containsExactly("outer(inner(a, b), c)", " d")
  }

  @Test
  fun `returns single item when no delimiter`() {
    val result = splitAtTopLevel("no delimiters here", ',')
    assertThat(result).containsExactly("no delimiters here")
  }

  @Test
  fun `returns single item for empty string`() {
    val result = splitAtTopLevel("", ',')
    assertThat(result).hasSize(1)
    assertThat(result).containsExactly("")
  }

  @Test
  fun `works with non-comma delimiters`() {
    val result = splitAtTopLevel("a;b;c", ';')
    assertThat(result).containsExactly("a", "b", "c")
  }

  @Test
  fun `handles multiple expressions with nested function calls`() {
    val result = splitAtTopLevel("EXISTS(SELECT 1 FROM t) AS valid, COUNT(*) AS total, name", ',')
    assertThat(result).containsExactly("EXISTS(SELECT 1 FROM t) AS valid", " COUNT(*) AS total", " name")
  }

  @Test
  fun `handles deeply nested parentheses`() {
    val result = splitAtTopLevel("encode(digest(hmac(a, b, c), d), e), f", ',')
    assertThat(result).containsExactly("encode(digest(hmac(a, b, c), d), e)", " f")
  }

  @Nested
  inner class ReplaceParameterPlaceholders {

    @Test
    fun `replaces single placeholder`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE id = ?")
      assertThat(result).isEqualTo("SELECT * FROM t WHERE id = NULL")
    }

    @Test
    fun `replaces multiple placeholders`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE a = ? AND b = ?")
      assertThat(result).isEqualTo("SELECT * FROM t WHERE a = NULL AND b = NULL")
    }

    @Test
    fun `preserves question mark inside single-quoted string`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE note = 'really?' AND id = ?")
      assertThat(result).isEqualTo("SELECT * FROM t WHERE note = 'really?' AND id = NULL")
    }

    @Test
    fun `preserves question mark inside escaped string literal`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE note = 'it''s a ? mark' AND id = ?")
      assertThat(result).isEqualTo("SELECT * FROM t WHERE note = 'it''s a ? mark' AND id = NULL")
    }

    @Test
    fun `preserves question mark inside line comment`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t -- why?\nWHERE id = ?")
      assertThat(result).isEqualTo("SELECT * FROM t -- why?\nWHERE id = NULL")
    }

    @Test
    fun `preserves question mark inside block comment`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t /* what? */ WHERE id = ?")
      assertThat(result).isEqualTo("SELECT * FROM t /* what? */ WHERE id = NULL")
    }

    @Test
    fun `no placeholders returns unchanged`() {
      val sql = "SELECT * FROM department"
      val result = replaceParameterPlaceholders(sql)
      assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `unclosed string literal preserves content without replacing`() {
      // Valid SQL never has unclosed literals, but the function should not crash
      val result = replaceParameterPlaceholders("SELECT '?")
      assertThat(result).isEqualTo("SELECT '?")
    }

    @Test
    fun `unclosed block comment preserves content without replacing`() {
      val result = replaceParameterPlaceholders("SELECT /* ?")
      assertThat(result).isEqualTo("SELECT /* ?")
    }
  }

  @Nested
  inner class ReplaceParameterPlaceholdersWithSentinels {

    @Test
    fun `replaces each placeholder with corresponding sentinel`() {
      val result = replaceParameterPlaceholdersWithSentinels(
        "SELECT digest(?, ?)",
        listOf("'\\x00'::bytea", "''::text"),
      )
      assertThat(result).isEqualTo("SELECT digest('\\x00'::bytea, ''::text)")
    }

    @Test
    fun `falls back to NULL when sentinels exhausted`() {
      val result = replaceParameterPlaceholdersWithSentinels(
        "SELECT ?, ?",
        listOf("0::int4"),
      )
      assertThat(result).isEqualTo("SELECT 0::int4, NULL")
    }

    @Test
    fun `skips placeholders in string literals`() {
      val result = replaceParameterPlaceholdersWithSentinels(
        "SELECT '?' || ?",
        listOf("''::text"),
      )
      assertThat(result).isEqualTo("SELECT '?' || ''::text")
    }

    @Test
    fun `skips placeholders in line comments`() {
      val result = replaceParameterPlaceholdersWithSentinels(
        "SELECT -- ?\n?",
        listOf("0::int4"),
      )
      assertThat(result).isEqualTo("SELECT -- ?\n0::int4")
    }

    @Test
    fun `returns original when no placeholders`() {
      val sql = "SELECT 1"
      val result = replaceParameterPlaceholdersWithSentinels(sql, listOf("0::int4"))
      assertThat(result).isEqualTo("SELECT 1")
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
