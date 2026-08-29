package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [markdownInlineCodeSpan] and [markdownFenceDelimiter] — the two escape-or-decline
 * primitives [TypeRepository]'s KDoc emission uses so that a `@property` source reference or a
 * fenced `sql` block can never render as something other than the developer's own text.
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
  inner class BacktickEscaping {

    @Test
    fun `a lone backtick is escaped with a preceding backslash`() {
      assertThat(escapeMarkdownBacktick("a `b`")).isEqualTo("a \\`b\\`")
    }

    @Test
    fun `a backslash already immediately before a backtick does not defeat the escape`() {
      // text.replace("`", "\\`") alone turns "weird \`" (a literal backslash then a backtick, e.g.
      // from `COMMENT ON COLUMN ... IS 'weird \`'`) into "weird \\`" -- CommonMark reads the doubled
      // backslash as an escaped backslash (a literal "\"), leaving the backtick that follows
      // unescaped and free to open a code span that pairs forward with a later property's own
      // source-reference span. Escaping the text's own literal backslashes first, before escaping
      // backticks, keeps the two escapes from colliding.
      val textWithBackslashBeforeBacktick = "weird \\" + "`"

      val escaped = escapeMarkdownBacktick(textWithBackslashBeforeBacktick)

      val markdown = "@property a $escaped\n@property b (`hz.b`)"
      val spans = extractSourceReferenceSpans(markdown)
      assertThat(spans).isEqualTo(listOf("hz.b"))
    }

    /**
     * A minimal, self-contained copy of the extraction real KDoc verification
     * ([SourceReferenceLiveVerificationTest]'s own `extractSourceReferenceSpans`) uses: a real
     * CommonMark parse for a [org.commonmark.node.Code] node whose immediately preceding text ends
     * in `(` — the shape [TypeSpec.Builder.addClassKdoc] produces via `append("($source)")`. Kept
     * here, rather than shared, so this pure-text unit test needs no live database and cannot be
     * broken by unrelated changes to that file's live-verification concerns.
     */
    private fun extractSourceReferenceSpans(markdown: String): List<String> {
      val spans = mutableListOf<String>()
      val precedingText = StringBuilder()
      val visitor = object : AbstractVisitor() {
        override fun visit(text: Text) {
          precedingText.append(text.literal)
        }

        override fun visit(softLineBreak: SoftLineBreak) {
          precedingText.append("\n")
        }

        override fun visit(code: Code) {
          if (precedingText.endsWith("(")) spans.add(code.literal)
          precedingText.append("`").append(code.literal).append("`")
        }
      }
      Parser.builder().build().parse(markdown).accept(visitor)
      return spans
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
