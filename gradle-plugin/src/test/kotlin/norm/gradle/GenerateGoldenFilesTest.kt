package norm.gradle

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Stream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * Test class that generates golden files for test scenarios.
 *
 * This is not a typical test - it's a utility for generating expected test outputs.
 * Run via `./gradlew :gradle-plugin:generateGoldenFiles` to generate all scenarios,
 * or `./gradlew :gradle-plugin:generateGoldenFiles -Pscenario=<name>` for a specific one.
 *
 * Tagged to exclude from regular test runs.
 */
@Tag("generateGoldenFiles")
@Execution(ExecutionMode.SAME_THREAD)
@OptIn(ExperimentalPathApi::class)
class GenerateGoldenFilesTest {

  @ParameterizedTest
  @MethodSource("scenarios")
  fun `generate golden files`(scenarioDirectory: Path) {
    val projectDir = Path.of("build/tmp/generateGoldenFiles/${scenarioDirectory.fileName}")
    projectDir.createDirectories()

    val project = TestProject(projectDir, scenarioDirectory)
    project.setup()

    // Run generation - use runCatching to capture partial success
    // (e.g., if some files generate but compilation would fail)
    val result = runCatching {
      project.gradle("normGenerateCodeTest").build()
    }

    // Copy whatever was generated, even if the build failed partway through
    if (project.generatedCodeDirectory.exists()) {
      // Delete existing golden files to remove stale files
      val goldenExampleDir = scenarioDirectory.resolve("example")
      if (goldenExampleDir.exists()) {
        goldenExampleDir.deleteRecursively()
      }

      // Copy generated code to scenario directory
      project.generatedCodeDirectory.walk()
        .filter { it.isRegularFile() }
        .forEach { file ->
          val relativePath = file.relativeTo(project.generatedCodeDirectory)
          val target = scenarioDirectory.resolve(relativePath)
          target.parent.createDirectories()
          file.copyTo(target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // Copy cleaned schema.json if it exists
    if (project.schemaJsonPath.exists()) {
      TestProject.writeCleanedSchemaJson(project.schemaJsonPath, scenarioDirectory.resolve("schema.json"))
    }

    // Now rethrow if generation failed, so the test reports the failure
    result.getOrThrow()

    // Clean up project directory on success
    projectDir.deleteRecursively()
  }

  companion object {
    @JvmStatic
    fun scenarios(): Stream<Path> {
      val filter = System.getProperty("scenario", "")
      return NormPluginTest.scenarios()
        .filter { filter.isBlank() || it.fileName.toString() == filter }
    }
  }
}
