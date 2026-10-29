package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [SelectItem.normalizedExpression] keeps the text of a single-quoted literal, a dollar-quoted string and a quoted
 * identifier as written, and drops comments. Whitespace between tokens is collapsed.
 */
class NormalizedExpressionTest {

  private fun normalized(selectList: String): String =
    parseSelectItems("SELECT $selectList FROM t").single().normalizedExpression

  @Nested
  inner class QuotedIdentifiers {

    @Test
    fun `two literal spaces inside a quoted identifier survive untouched`() {
      assertThat(normalized("""UPPER("My  Col")""")).isEqualTo("""UPPER("My  Col")""")
    }

    @Test
    fun `a tab inside a quoted identifier survives untouched`() {
      assertThat(normalized("UPPER(\"My\tCol\")")).isEqualTo("UPPER(\"My\tCol\")")
    }

    @Test
    fun `a doubled double-quote escape inside a quoted identifier is not mistaken for the closing quote`() {
      assertThat(normalized("""UPPER("My ""Col"" x")""")).isEqualTo("""UPPER("My ""Col"" x")""")
    }
  }

  @Nested
  inner class SingleQuotedLiterals {

    @Test
    fun `internal spaces around parentheses inside a string literal survive untouched`() {
      assertThat(normalized("name || '( x )'")).isEqualTo("name || '( x )'")
    }

    @Test
    fun `a run of internal spaces inside a string literal survives untouched`() {
      assertThat(normalized("name || 'a  b'")).isEqualTo("name || 'a  b'")
    }

    @Test
    fun `a newline inside a string literal survives untouched`() {
      assertThat(normalized("name || 'a\nb'")).isEqualTo("name || 'a\nb'")
    }

    @Test
    fun `a doubled single-quote escape inside a string literal is not mistaken for the closing quote`() {
      assertThat(normalized("name || 'it''s  fine'")).isEqualTo("name || 'it''s  fine'")
    }
  }

  @Nested
  inner class DollarQuotedStrings {

    @Test
    fun `internal whitespace inside a bare dollar-quoted string survives untouched`() {
      assertThat(normalized("name || \$\$a  b\$\$")).isEqualTo("name || \$\$a  b\$\$")
    }

    @Test
    fun `internal whitespace inside a tagged dollar-quoted string survives untouched`() {
      assertThat(normalized("name || \$tag\$a\tb\nc\$tag\$")).isEqualTo("name || \$tag\$a\tb\nc\$tag\$")
    }
  }

  @Nested
  inner class StringContinuation {

    @Test
    fun `a line comment between two quoted segments becomes one space`() {
      assertThat(normalized("'a' -- note\n'b' AS x")).isEqualTo("'a' 'b'")
    }

    @Test
    fun `a newline between two quoted segments becomes one space`() {
      assertThat(normalized("'a'\n'b' AS x")).isEqualTo("'a' 'b'")
    }

    @Test
    fun `a lone carriage return between two quoted segments becomes one space`() {
      assertThat(normalized("'a'\r'b' AS x")).isEqualTo("'a' 'b'")
    }

    @Test
    fun `a carriage return and a newline between two quoted segments become one space`() {
      assertThat(normalized("'a'\r\n'b' AS x")).isEqualTo("'a' 'b'")
    }

    @Test
    fun `a line comment ended by a lone carriage return between two quoted segments becomes one space`() {
      assertThat(normalized("'a' -- note\r'b' AS x")).isEqualTo("'a' 'b'")
    }

    @Test
    fun `an escape string keeps its prefix and its escaped quote`() {
      assertThat(normalized("E'a\\'' \n 'b' AS x")).isEqualTo("E'a\\'' 'b'")
    }

    @Test
    fun `a doubled quote inside a segment is not a segment boundary`() {
      assertThat(normalized("'it''s'\n'x'")).isEqualTo("'it''s' 'x'")
    }

    @Test
    fun `every separator of a literal with three segments becomes one space`() {
      assertThat(normalized("'a'\n'b'\n  -- c\n 'c'")).isEqualTo("'a' 'b' 'c'")
    }

    @Test
    fun `a continuation inside an expression is joined and the rest is unchanged`() {
      assertThat(normalized("name  ||  'a'\n'b'")).isEqualTo("name || 'a' 'b'")
    }

    @Test
    fun `a newline inside one segment survives untouched`() {
      assertThat(normalized("'a\nb' AS x")).isEqualTo("'a\nb'")
    }

    @Test
    fun `a block comment between segments is rejected by PostgreSQL, so there are no items`() {
      assertThat(parseSelectItems("SELECT 'a' /* c */\n'b' AS x FROM t")).isEmpty()
    }
  }

  @Nested
  inner class Comments {

    @Test
    fun `a block comment between two tokens is dropped`() {
      assertThat(normalized("UPPER(/* a  b */name)")).isEqualTo("UPPER(name)")
    }

    @Test
    fun `a line comment between two tokens is dropped and the tokens stay separated`() {
      assertThat(normalized("name -- a  b\n|| other")).isEqualTo("name || other")
    }

    @Test
    fun `a comment between two otherwise adjacent tokens becomes one space`() {
      assertThat(normalized("a/*c*/+b")).isEqualTo("a +b")
    }
  }

  @Nested
  inner class OrdinaryCosmeticCollapsing {

    @Test
    fun `a run of ordinary whitespace outside any literal collapses to one space`() {
      assertThat(normalized("UPPER(a)   ||   LOWER(b)")).isEqualTo("UPPER(a) || LOWER(b)")
    }

    @Test
    fun `a space immediately after an opening parenthesis outside any literal is removed`() {
      assertThat(normalized("UPPER( a)")).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a space immediately before a closing parenthesis outside any literal is removed`() {
      assertThat(normalized("UPPER(a )")).isEqualTo("UPPER(a)")
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
      assertThat(normalized("  UPPER(a)  ")).isEqualTo("UPPER(a)")
    }
  }
}
