package norm.generator.baseline

import java.nio.file.Path
import kotlin.io.path.Path

/** Where the committed baseline files live, relative to the working directory of the `generator` module. */
internal object ScannerBaselineFiles {
  val baseline: Path = Path("src/test/resources/scanner-baseline/baseline.txt")

  val sweepSkips: Path = Path("src/test/resources/pg-regress/sweep-skips.txt")

  const val BASELINE_RESOURCE = "/scanner-baseline/baseline.txt"
}
