package norm.generator.baseline

import norm.generator.libpgquery.SweepSkipsReport
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Rewrites the committed scanner baseline and the grammar sweep skip list from the current code.
 *
 * Run `./gradlew :generator:generateScannerBaseline`. The regular test run excludes this class, and its comparison
 * tests read the files it writes.
 */
@Tag("generateScannerBaseline")
class GenerateScannerBaseline {

  @Test
  fun `write the scanner baseline`() {
    write(ScannerBaselineFiles.baseline, ScannerBaseline.render(ScannerCorpus.load()))
  }

  @Test
  fun `write the sweep skip list`() {
    write(ScannerBaselineFiles.sweepSkips, SweepSkipsReport.render())
  }

  private fun write(path: Path, text: String) {
    Files.createDirectories(path.parent)
    Files.writeString(path, text)
  }
}
