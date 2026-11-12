package norm.generator.libpgquery

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.Path

/**
 * Rewrites the committed grammar sweep skip list from the current code.
 *
 * Run `./gradlew :generator:generateSweepSkips`. The regular test run excludes this class, and
 * [RegressSweepTest] compares the file it writes.
 */
@Tag("generateSweepSkips")
class GenerateSweepSkips {

  @Test
  fun `write the sweep skip list`() {
    val path = Path("src/test/resources/pg-regress/sweep-skips.txt")
    Files.createDirectories(path.parent)
    Files.writeString(path, SweepSkipsReport.render())
  }
}
