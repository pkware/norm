package norm.generator.baseline

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class ScannerBaselineEscapeTest {

  @Test
  fun `unescape inverts escape for backslashes and control characters`() {
    val value = "a\\b\nc\rd\te\u0000f\u007fg"

    assertThat(ScannerBaseline.unescape(ScannerBaseline.escape(value))).isEqualTo(value)
  }

  @Test
  fun `unescape inverts escape for unpaired surrogates and surrogate pairs`() {
    val value = "x\uD800y\uDC00z😀"

    assertThat(ScannerBaseline.unescape(ScannerBaseline.escape(value))).isEqualTo(value)
  }

  @Test
  fun `escape writes one line and unescape reads it back`() {
    val escaped = ScannerBaseline.escape("one\ntwo")

    assertThat(escaped).isEqualTo("one\\ntwo")
    assertThat(ScannerBaseline.unescape("\\\\n\\u0041")).isEqualTo("\\nA")
  }
}
