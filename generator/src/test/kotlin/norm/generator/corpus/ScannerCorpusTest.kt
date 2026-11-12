package norm.generator.corpus

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ScannerCorpusTest {

  @Test
  fun `the corpus holds every kind of source`() {
    val keys = ScannerCorpus.load().map { it.key }
    val kinds = listOf("test-scenarios/", "test-scenarios-frameworks/", "issue-331#", "issue-419#", "issue-459") +
      LITERAL_SOURCE_FILES.map { "$it.kt:" }
    for (kind in kinds) {
      assertThat(keys.count { it.startsWith(kind) }, "entries starting with $kind").isGreaterThan(0)
    }
  }

  @Nested
  inner class Unescape {

    @Test
    fun `reads an escaped backslash`() {
      assertThat(unescape("a\\\\nb")).isEqualTo("a\\nb")
    }

    @Test
    fun `reads an escaped line feed`() {
      assertThat(unescape("a\\nb")).isEqualTo("a\nb")
    }

    @Test
    fun `reads an escaped carriage return`() {
      assertThat(unescape("a\\rb")).isEqualTo("a\rb")
    }

    @Test
    fun `reads an escaped tab`() {
      assertThat(unescape("a\\tb")).isEqualTo("a\tb")
    }

    @Test
    fun `reads an escaped UTF-16 code unit`() {
      assertThat(unescape("a\\u0000b\\u007f")).isEqualTo("a\u0000b\u007f")
    }

    @Test
    fun `reads an escaped surrogate pair`() {
      assertThat(unescape("\\ud83d\\ude00")).isEqualTo("😀")
    }
  }
}
