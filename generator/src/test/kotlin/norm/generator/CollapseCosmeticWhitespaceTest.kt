package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [collapseCosmeticWhitespace] must never alter a byte inside a single-quoted literal, a
 * dollar-quoted string, a quoted identifier, or a comment — only whitespace outside those spans is
 * cosmetic.
 */
class CollapseCosmeticWhitespaceTest {

  @Nested
  inner class QuotedIdentifiers {

    @Test
    fun `two literal spaces inside a quoted identifier survive untouched`() {
      // Verified live: CREATE TABLE t (id INT NOT NULL, "My  Col" TEXT NOT NULL) then
      // UPPER("My  Col") -- collapsing the internal double space to one names a DIFFERENT,
      // nonexistent column ("My Col"), which PostgreSQL rejects outright.
      assertThat(collapseCosmeticWhitespace("""UPPER("My  Col")""")).isEqualTo("""UPPER("My  Col")""")
    }

    @Test
    fun `a tab inside a quoted identifier survives untouched`() {
      assertThat(collapseCosmeticWhitespace("UPPER(\"My\tCol\")")).isEqualTo("UPPER(\"My\tCol\")")
    }

    @Test
    fun `a doubled double-quote escape inside a quoted identifier is not mistaken for the closing quote`() {
      assertThat(collapseCosmeticWhitespace("""UPPER("My ""Col"" x")""")).isEqualTo("""UPPER("My ""Col"" x")""")
    }
  }

  @Nested
  inner class SingleQuotedLiterals {

    @Test
    fun `internal spaces around parentheses inside a string literal survive untouched`() {
      // "name || '( x )'" must never become "name || '(x)'" -- the literal's own content, not its
      // padding, would change.
      assertThat(collapseCosmeticWhitespace("name || '( x )'")).isEqualTo("name || '( x )'")
    }

    @Test
    fun `a run of internal spaces inside a string literal survives untouched`() {
      assertThat(collapseCosmeticWhitespace("name || 'a  b'")).isEqualTo("name || 'a  b'")
    }

    @Test
    fun `a newline inside a string literal survives untouched`() {
      assertThat(collapseCosmeticWhitespace("name || 'a\nb'")).isEqualTo("name || 'a\nb'")
    }

    @Test
    fun `a doubled single-quote escape inside a string literal is not mistaken for the closing quote`() {
      assertThat(collapseCosmeticWhitespace("name || 'it''s  fine'")).isEqualTo("name || 'it''s  fine'")
    }
  }

  @Nested
  inner class DollarQuotedStrings {

    @Test
    fun `internal whitespace inside a bare dollar-quoted string survives untouched`() {
      assertThat(collapseCosmeticWhitespace("name || \$\$a  b\$\$")).isEqualTo("name || \$\$a  b\$\$")
    }

    @Test
    fun `internal whitespace inside a tagged dollar-quoted string survives untouched`() {
      assertThat(collapseCosmeticWhitespace("name || \$tag\$a\tb\nc\$tag\$")).isEqualTo("name || \$tag\$a\tb\nc\$tag\$")
    }
  }

  @Nested
  inner class Comments {

    @Test
    fun `a block comment between two tokens is walked over as one opaque unit, never rewritten`() {
      // collapseCosmeticWhitespace runs AFTER stripComments in the real pipeline, so a raw comment
      // is not expected input here -- this pins that a comment reached directly is copied through
      // VERBATIM (never a byte inside it rewritten), matching every other opaque span this
      // function protects; removing the comment itself is stripComments' own job, not this one's.
      assertThat(collapseCosmeticWhitespace("UPPER(/* a  b */name)")).isEqualTo("UPPER(/* a  b */name)")
    }

    @Test
    fun `a line comment is walked over as one opaque unit, never rewritten`() {
      assertThat(collapseCosmeticWhitespace("name -- a  b\n|| other")).isEqualTo("name -- a  b\n|| other")
    }
  }

  @Nested
  inner class OrdinaryCosmeticCollapsing {

    @Test
    fun `a run of ordinary whitespace outside any literal collapses to one space`() {
      assertThat(collapseCosmeticWhitespace("UPPER(a)   ||   LOWER(b)")).isEqualTo("UPPER(a) || LOWER(b)")
    }

    @Test
    fun `a space immediately after an opening parenthesis outside any literal is removed`() {
      assertThat(collapseCosmeticWhitespace("UPPER( a)")).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a space immediately before a closing parenthesis outside any literal is removed`() {
      assertThat(collapseCosmeticWhitespace("UPPER(a )")).isEqualTo("UPPER(a)")
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
      assertThat(collapseCosmeticWhitespace("  UPPER(a)  ")).isEqualTo("UPPER(a)")
    }
  }
}
