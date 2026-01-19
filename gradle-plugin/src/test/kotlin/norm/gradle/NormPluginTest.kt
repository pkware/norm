package norm.gradle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

@Execution(ExecutionMode.SAME_THREAD)
@OptIn(ExperimentalPathApi::class)
class NormPluginTest {

  @TempDir
  private lateinit var testProjectDir: Path

  @ParameterizedTest
  @MethodSource("basicScenarios")
  fun `code can be generated and matches golden files without type resolution`(scenarioDirectory: Path) {
    executeGradleProject(scenarioDirectory, false)
  }

  @ParameterizedTest
  @MethodSource("complexScenarios")
  fun `code can be generated and matches golden files with type resolution`(scenarioDirectory: Path) {
    executeGradleProject(scenarioDirectory, true)
  }

  private fun executeGradleProject(scenarioDirectory: Path, requiresDatabase: Boolean) {
    val project = TestProject(testProjectDir, scenarioDirectory)
    project.setup(requiresDatabase)

    // Run full build to verify generated code compiles
    project.gradle("build").build()

    // Collect and compare Kotlin files
    val expectedFiles = collectKotlinFiles(scenarioDirectory.resolve("example"), scenarioDirectory)
    val generatedFiles = collectKotlinFiles(project.generatedCodeDirectory, project.generatedCodeDirectory)

    // Assert all expected files exist and match
    for ((relativePath, expectedContent) in expectedFiles) {
      assertThat(
        generatedFiles.containsKey(relativePath),
        "Expected file $relativePath to be generated",
      ).isTrue()
      assertThat(
        generatedFiles[relativePath],
        "Content mismatch for $relativePath",
      ).isEqualTo(expectedContent)
    }

    // Assert no unexpected files were generated (stale golden files)
    val unexpectedFiles = generatedFiles.keys - expectedFiles.keys
    assertThat(unexpectedFiles, "Unexpected files were generated").isEmpty()

    // Assert schema.json matches (after cleaning)
    val generatedSchema = TestProject.readAndCleanSchemaJson(project.schemaJsonPath)
    val goldenSchema = TestProject.readAndCleanSchemaJson(scenarioDirectory.resolve("schema.json"))
    assertThat(generatedSchema, "schema.json mismatch").isEqualTo(goldenSchema)
  }

  @Test
  fun `packageNames with periods are correctly generated`() {
    val scenarioDirectory = basicScenarios().first()
    val project = TestProject(testProjectDir, scenarioDirectory)
    project.setupSettingsOnly()

    project.buildFile.writeText(
      """
      plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      norm {
        databases {
          create("Test") {
            packageName = "example.with.periods"
            schemas.addAll("${scenarioDirectory.resolve("schema.sql").normalize().toAbsolutePath()}")
            queries.addAll("${scenarioDirectory.resolve("queries.sql").normalize().toAbsolutePath()}")
            useDatabase = false  // Schema-only validation is sufficient
          }
        }
      }
      """.trimIndent(),
    )

    project.gradle("normGenerateCode").build()

    // Verify the folder structure matches the package name with periods
    val packageDirectory = project.generatedCodeDirectory.resolve("example/with/periods")
    assertThat(packageDirectory.exists()).isTrue()

    // Verify generated Kotlin files have the correct package declaration
    val kotlinFiles = packageDirectory.listDirectoryEntries("*.kt")
    assertThat(kotlinFiles).isNotEmpty()

    kotlinFiles.forEach { kotlinFile ->
      assertThat(kotlinFile.readText()).contains("package example.with.periods")
    }
  }

  @Test
  fun `relative paths can be used for schemas and queries`() {
    val scenarioDirectory = basicScenarios().first()
    val project = TestProject(testProjectDir, scenarioDirectory)
    project.setupSettingsOnly()

    project.buildFile.writeText(
      """
      plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      norm {
        databases {
          create("Test") {
            packageName = "example"
            schemas.addAll("../${testProjectDir.relativize(scenarioDirectory.resolve("schema.sql"))}")
            queries.addAll("../${testProjectDir.relativize(scenarioDirectory.resolve("queries.sql"))}")
            useDatabase = false  // Schema-only validation is sufficient
          }
        }
      }
      """.trimIndent(),
    )

    project.gradle("normGenerateCode").build()
  }

  // TODO Test that tasks are correctly cached

  /**
   * Collects all Kotlin files from [directory], returning a map of relative path to content.
   */
  private fun collectKotlinFiles(directory: Path, baseDir: Path): Map<String, String> = directory.walk()
    .filter { it.isRegularFile() && it.extension == "kt" }
    .associate { file ->
      file.relativeTo(baseDir).pathString to file.readText()
    }

  companion object {
    @JvmStatic
    fun basicScenarios() = Path("../test-scenarios-basic/").normalize().toAbsolutePath().listDirectoryEntries()
      .filter(Files::isDirectory)

    @JvmStatic
    fun complexScenarios() = Path("../test-scenarios-complex/").normalize().toAbsolutePath().listDirectoryEntries()
      .filter(Files::isDirectory)
  }
}
