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
  @MethodSource("scenarios")
  fun `code can be generated and matches golden files`(scenarioDirectory: Path) {
    executeGradleProject(scenarioDirectory)
  }

  private fun executeGradleProject(scenarioDirectory: Path) {
    val project = TestProject(projectDir, scenarioDirectory)
    project.setup()

    // Run full build to verify generated code compiles
    project.gradle("build").build()

    // Collect and compare Kotlin files
    val goldenDir = scenarioDirectory.resolve("example")
    val expectedFiles = collectKotlinFiles(goldenDir, goldenDir)
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
  }

  @Test
  fun `packageNames with periods are correctly generated`() {
    val scenarioDirectory = scenarios().first()
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
          }
        }
      }
      """.trimIndent(),
    )

    project.gradle("normGenerateTest").build()

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
    val scenarioDirectory = scenarios().first()
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
          }
        }
      }
      """.trimIndent(),
    )

    project.gradle("normGenerateTest").build()
  }

  // TODO Test that tasks are correctly cached

  @Test
  fun `stale generated files are deleted when queries are removed`() {
    val scenarioDirectory = BASIC_EMBEDS_SCENARIO
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
      SELECT * FROM author WHERE id = ?;
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
          }
        }
      }
      """.trimIndent(),
    )

    // First build - generate initial files
    val initialResult = project.gradle("normGenerateTest").build()
    assertThat(initialResult.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)

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
    val modifiedResult = project.gradle("normGenerateTest").build()
    assertThat(modifiedResult.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)

    val finalFiles = collectKotlinFileNames(generatedDir)

    // Verify expected files exist
    assertThat(finalFiles).contains("Queries.kt")
    assertThat(finalFiles).contains("PostgresQueries.kt")
  }

  @Test
  fun `cleaning one database does not affect another database`() {
    val scenarioDirectory = BASIC_EMBEDS_SCENARIO
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
      SELECT * FROM author WHERE id = ?;
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
          }
          create("Database2") {
            packageName = "example.db2"
            schemas.addAll("$schema")
            queries.addAll("$queries2")
          }
        }
      }
      """.trimIndent(),
    )

    // Generate both databases
    project.gradle("normGenerateDatabase1", "normGenerateDatabase2").build()

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
    project.gradle("normGenerateDatabase1").build()

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

    /**
     * A scenario with a simple `author` table, used by hand-written tests that
     * need a known schema but aren't testing golden-file matching.
     */
    private val BASIC_EMBEDS_SCENARIO = Path("../test-scenarios/basic_embeds").normalize().toAbsolutePath()

    private val EMBED_SCENARIOS =
      setOf("basic_embeds", "complex_embed_mixing", "consecutive_embeds", "nested_joins_embeds")

    /**
     * Returns all non-embed test scenario directories, sorted by name.
     *
     * Sorting is required because [Path.listDirectoryEntries] does not guarantee order.
     */
    @JvmStatic
    fun scenarios() = Path("../test-scenarios/").normalize().toAbsolutePath()
      .listDirectoryEntries()
      .filter(Files::isDirectory)
      .filter { it.fileName.toString() !in EMBED_SCENARIOS }
      .sorted()
  }
}
