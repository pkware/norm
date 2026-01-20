package norm.gradle

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
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
class GenerateGoldenFiles {

  @ParameterizedTest
  @MethodSource("basicScenarios")
  fun `generate golden files without type resolution`(scenarioDirectory: Path) {
    executeGradleProject(scenarioDirectory, false)
  }

  @ParameterizedTest
  @MethodSource("complexScenarios")
  fun `generate golden files with type resolution`(scenarioDirectory: Path) {
    executeGradleProject(scenarioDirectory, true)
  }

  @ParameterizedTest
  @MethodSource("frameworkScenarios")
  fun `generate golden files for framework scenarios`(scenario: FrameworkGoldenScenario) {
    executeGradleProjectWithFrameworks(
      scenario.scenarioDirectory,
      requiresDatabase = false,
      frameworks = scenario.frameworks,
      goldenSubdir = scenario.goldenSubdir,
    )
  }

  private fun executeGradleProject(scenarioDirectory: Path, requiresDatabase: Boolean) {
    executeGradleProjectInternal(scenarioDirectory, requiresDatabase, emptySet(), "example")
  }

  private fun executeGradleProjectWithFrameworks(
    scenarioDirectory: Path,
    requiresDatabase: Boolean,
    frameworks: Set<String>,
    goldenSubdir: String,
  ) {
    executeGradleProjectInternal(scenarioDirectory, requiresDatabase, frameworks, goldenSubdir)
  }

  private fun executeGradleProjectInternal(
    scenarioDirectory: Path,
    requiresDatabase: Boolean,
    frameworks: Set<String>,
    goldenSubdir: String,
  ) {
    val projectDirSuffix = if (goldenSubdir == "example") "" else "-$goldenSubdir"
    val projectDir = Path.of("build/tmp/generateGoldenFiles/${scenarioDirectory.fileName}$projectDirSuffix")
    projectDir.createDirectories()

    val project = TestProject(projectDir, scenarioDirectory)
    project.setup(requiresDatabase, frameworks)

    // Run generation - use runCatching to capture partial success
    // (e.g., if some files generate but compilation would fail)
    val result = runCatching {
      project.gradle("normGenerateCodeTest").build()
    }

    // Copy whatever was generated, even if the build failed partway through
    if (project.generatedCodeDirectory.exists()) {
      // Delete existing golden files to remove stale files
      val goldenExampleDir = scenarioDirectory.resolve(goldenSubdir)
      if (goldenExampleDir.exists()) {
        goldenExampleDir.deleteRecursively()
      }

      // Copy generated code to scenario directory
      project.generatedCodeDirectory.walk()
        .filter { it.isRegularFile() }
        .forEach { file ->
          val relativePath = file.relativeTo(project.generatedCodeDirectory)
          val target = scenarioDirectory.resolve(goldenSubdir).resolve(relativePath)
          target.parent.createDirectories()
          file.copyTo(target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // Copy cleaned schema.json
    val schemaJsonTarget = scenarioDirectory.resolve("schema.json")
    TestProject.writeCleanedSchemaJson(project.schemaJsonPath, schemaJsonTarget)

    // Now rethrow if generation failed, so the test reports the failure
    result.getOrThrow()

    // Clean up project directory on success
    projectDir.deleteRecursively()
  }

  companion object {

    private val scenarioToRun = System.getProperty("scenario", "")
    private val scenariosFilter: (Path) -> Boolean = {
      scenarioToRun.isBlank() ||
        it.fileName.toString() == scenarioToRun
    }

    @JvmStatic
    fun basicScenarios() = NormPluginTest.basicScenarios().filter(scenariosFilter)

    @JvmStatic
    fun complexScenarios() = NormPluginTest.complexScenarios().filter(scenariosFilter)

    @JvmStatic
    fun frameworkScenarios(): List<FrameworkGoldenScenario> {
      val frameworkScenariosDir = Path.of("../test-scenarios-frameworks/").normalize().toAbsolutePath()
      return frameworkScenariosDir.listDirectoryEntries()
        .filter(Files::isDirectory)
        .filter(scenariosFilter)
        .flatMap { scenarioDir ->
          listOf(
            FrameworkGoldenScenario(scenarioDir, setOf("MICRONAUT_DATA_JDBC"), "micronaut"),
            FrameworkGoldenScenario(scenarioDir, setOf("SPRING_DATA_JDBC"), "spring"),
            FrameworkGoldenScenario(scenarioDir, setOf("ALL_TABLES"), "all-tables"),
          )
        }
    }
  }
}

/**
 * Represents a framework-specific golden file generation scenario.
 *
 * @property scenarioDirectory The directory containing the scenario's schema.sql and queries.sql files.
 * @property frameworks The set of framework names to enable for code generation. These must match
 *   [Framework][norm.generator.Framework] enum constant names (e.g., "MICRONAUT_DATA_JDBC").
 * @property goldenSubdir The subdirectory name where golden files should be output relative to
 *   [scenarioDirectory] (e.g., "micronaut", "spring").
 */
data class FrameworkGoldenScenario(val scenarioDirectory: Path, val frameworks: Set<String>, val goldenSubdir: String)
