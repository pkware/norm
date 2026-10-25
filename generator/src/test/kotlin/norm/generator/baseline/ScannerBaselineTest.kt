package norm.generator.baseline

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import org.junit.jupiter.api.Test

class ScannerBaselineTest {

  @Test
  fun `the committed baseline matches what the scanner functions return`() {
    val committed = checkNotNull(
      ScannerBaselineTest::class.java.getResourceAsStream(ScannerBaselineFiles.BASELINE_RESOURCE),
    ) {
      "The baseline is missing. Run ./gradlew :generator:generateScannerBaseline."
    }.use { String(it.readBytes(), Charsets.UTF_8) }

    val recomputed = ScannerBaseline.render(ScannerCorpus.load())

    val committedLines = committed.lines()
    val recomputedLines = recomputed.lines()
    val firstDifference = committedLines.zip(recomputedLines).indexOfFirst { (left, right) -> left != right }
    val index = if (firstDifference >= 0) firstDifference else minOf(committedLines.size, recomputedLines.size)
    assertThat(
      recomputedLines.getOrNull(index),
      "baseline line ${index + 1}, preceded by the entry ${entryAt(recomputedLines, index)}",
    ).isEqualTo(committedLines.getOrNull(index))
    assertThat(recomputed.length).isEqualTo(committed.length)
  }

  @Test
  fun `the corpus holds every kind of source`() {
    val keys = ScannerCorpus.load().map { it.key }
    val kinds = listOf("test-scenarios/", "test-scenarios-frameworks/", "issue-331#", "issue-419#", "issue-459") +
      LITERAL_SOURCE_FILES.map { "$it.kt:" }
    for (kind in kinds) {
      assertThat(keys.count { it.startsWith(kind) }, "entries starting with $kind").isGreaterThan(0)
    }
  }

  private fun entryAt(lines: List<String>, index: Int): String =
    lines.take(index + 1).lastOrNull { it.startsWith("=== ") } ?: "(none)"
}
