package norm.gradle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
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
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

@Execution(ExecutionMode.SAME_THREAD)
@OptIn(ExperimentalPathApi::class)
class NormPluginTest {

  @TempDir
  private lateinit var projectDir: Path

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
    val project = TestProject(projectDir, scenarioDirectory)
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
    val project = TestProject(projectDir, scenarioDirectory)
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
    val project = TestProject(projectDir, scenarioDirectory)
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
            schemas.addAll("../${projectDir.relativize(scenarioDirectory.resolve("schema.sql"))}")
            queries.addAll("../${projectDir.relativize(scenarioDirectory.resolve("queries.sql"))}")
            useDatabase = false  // Schema-only validation is sufficient
          }
        }
      }
      """.trimIndent(),
    )

    project.gradle("normGenerateCode").build()
  }

  // TODO Test that tasks are correctly cached

  @Test
  fun `stale generated files are deleted when queries are removed`() {
    val scenarioDirectory = Path("../test-scenarios-basic/basic_embeds").normalize().toAbsolutePath()
    val project = TestProject(projectDir, scenarioDirectory)
    project.setupSettingsOnly()

    // Create initial queries file with 2 queries
    val schema = scenarioDirectory.resolve("schema.sql")
    val queriesFile = projectDir.resolve("queries.sql")
    queriesFile.writeText(
      $$"""
      -- name: getAll :many
      SELECT * FROM author;

      -- name: getById :one
      SELECT * FROM author WHERE id = $1;
      """.trimIndent(),
    )

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
            schemas.addAll("$schema")
            queries.addAll("$queriesFile")
            useDatabase = false
          }
        }
      }
      """.trimIndent(),
    )

    // First build - generate initial files
    val initialResult = project.gradle("normGenerateCode").build()
    assertThat(initialResult.task(":normGenerateCodeTest")?.outcome).isEqualTo(SUCCESS)

    val generatedDir = project.generatedCodeDirectory.resolve("example")
    val initialFiles = collectKotlinFileNames(generatedDir)

    assertThat(initialFiles).contains("Queries.kt")
    assertThat(initialFiles).contains("PostgresQueries.kt")

    // Modify queries - remove one query
    queriesFile.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
      """.trimIndent(),
    )

    // Second build - stale files should be removed
    val modifiedResult = project.gradle("normGenerateCode").build()
    assertThat(modifiedResult.task(":normGenerateCodeTest")?.outcome).isEqualTo(SUCCESS)

    val finalFiles = collectKotlinFileNames(generatedDir)

    // Verify expected files exist
    assertThat(finalFiles).contains("Queries.kt")
    assertThat(finalFiles).contains("PostgresQueries.kt")
  }

  @Test
  fun `cleaning one database does not affect another database`() {
    val scenarioDirectory = Path("../test-scenarios-basic/basic_embeds").normalize().toAbsolutePath()
    val project = TestProject(projectDir, scenarioDirectory)
    project.setupSettingsOnly()

    val schema = scenarioDirectory.resolve("schema.sql")
    val queries1 = projectDir.resolve("queries1.sql")
    val queries2 = projectDir.resolve("queries2.sql")

    queries1.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
      """.trimIndent(),
    )

    queries2.writeText(
      $$"""
      -- name: getById :one
      SELECT * FROM author WHERE id = $1;
      """.trimIndent(),
    )

    project.buildFile.writeText(
      """
      plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      norm {
        databases {
          create("Database1") {
            packageName = "example.db1"
            schemas.addAll("$schema")
            queries.addAll("$queries1")
            useDatabase = false
          }
          create("Database2") {
            packageName = "example.db2"
            schemas.addAll("$schema")
            queries.addAll("$queries2")
            useDatabase = false
          }
        }
      }
      """.trimIndent(),
    )

    // Generate both databases
    project.gradle("normGenerateCodeDatabase1", "normGenerateCodeDatabase2").build()

    val db1Dir = project.generatedCodeDirectory.resolve("example/db1")
    val db2Dir = project.generatedCodeDirectory.resolve("example/db2")

    val db1Files = collectKotlinFileNames(db1Dir).toSet()
    val db2Files = collectKotlinFileNames(db2Dir).toSet()

    assertThat(db1Files).isNotEmpty()
    assertThat(db2Files).isNotEmpty()

    // Modify Database1 queries (add new query)
    queries1.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;

      -- name: count :one
      SELECT COUNT(*) FROM author;
      """.trimIndent(),
    )

    // Regenerate only Database1
    project.gradle("normGenerateCodeDatabase1").build()

    // Verify Database2 files unchanged
    val db2FilesAfter = collectKotlinFileNames(db2Dir).toSet()
    assertThat(db2FilesAfter).isEqualTo(db2Files)
  }

  /**
   * Collects all Kotlin file names from [directory], returning a list of file names.
   */
  private fun collectKotlinFileNames(directory: Path): List<String> = directory.walk()
    .filter { it.isRegularFile() && it.extension == "kt" }
    .map { it.name }
    .toList()

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
