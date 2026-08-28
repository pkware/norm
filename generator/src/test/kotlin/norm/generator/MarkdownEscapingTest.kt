package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [markdownInlineCodeSpan] and [markdownFenceDelimiter] — the two escape-or-decline
 * primitives [TypeRepository]'s KDoc emission uses so that a `@property` source reference or a
 * fenced `sql` block can never render as something other than the developer's own text (#238 8.1–8.3).
 * [TypeRepositoryTest] exercises these through [TypeRepository.buildTypeProjectionForQuery] itself;
 * this file pins the primitives' own edge cases directly.
 */
class MarkdownEscapingTest {

  @Nested
  inner class InlineCodeSpan {

    @Test
    fun `text with no backtick and no newline is wrapped in a single backtick pair`() {
      assertThat(markdownInlineCodeSpan("UPPER(x)")).isEqualTo("`UPPER(x)`")
    }

    @Test
    fun `a single embedded backtick is escaped with a double-backtick delimiter, unpadded`() {
      // The backtick sits in the MIDDLE of the text, not touching either end, so no padding space
      // is needed for the delimiter to stay unambiguous.
      assertThat(markdownInlineCodeSpan("s || '`'")).isEqualTo("``s || '`'``")
    }

    @Test
    fun `text starting with a backtick is padded so the backtick never touches the delimiter`() {
      assertThat(markdownInlineCodeSpan("`x")).isEqualTo("`` `x ``")
    }

    @Test
    fun `text ending with a backtick is padded so the backtick never touches the delimiter`() {
      assertThat(markdownInlineCodeSpan("x`")).isEqualTo("`` x` ``")
    }

    @Test
    fun `a longer run of embedded backticks widens the delimiter beyond that run`() {
      assertThat(markdownInlineCodeSpan("a ``` b")).isEqualTo("````a ``` b````")
    }

    @Test
    fun `a raw newline is declined -- no delimiter choice can preserve it through Markdown rendering`() {
      assertThat(markdownInlineCodeSpan("a\nb")).isNull()
    }

    @Test
    fun `a raw carriage return is declined for the same reason as a newline`() {
      assertThat(markdownInlineCodeSpan("a\rb")).isNull()
    }
  }

  @Nested
  inner class FenceDelimiter {

    @Test
    fun `text with no backtick run gets the conventional 3-backtick fence`() {
      assertThat(markdownFenceDelimiter("SELECT 1")).isEqualTo("```")
    }

    @Test
    fun `a 3-backtick run inside the text widens the fence to 4`() {
      assertThat(markdownFenceDelimiter("SELECT '```' AS x")).isEqualTo("````")
    }

    @Test
    fun `a longer embedded run widens the fence beyond that run`() {
      assertThat(markdownFenceDelimiter("SELECT '``````' AS x")).isEqualTo("`".repeat(7))
    }
  }
}
