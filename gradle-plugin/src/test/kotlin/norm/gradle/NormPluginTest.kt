package norm.gradle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
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

// Shares `COMPOSITE_BUILD_RESOURCE_LOCK` with `IdeSyncIntegrationTest`: both classes run
// TestKit builds that `includeBuild` the Norm root project, and running two of those builds at once races
// on the shared `buildSrc` project's incremental compilation caches. See the lock's KDoc for details.
@ResourceLock(TestProject.COMPOSITE_BUILD_RESOURCE_LOCK)
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
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    project.gradle("normGenerateTest").build()
  }

  // TODO Test that tasks are correctly cached

  @Test
  fun `configuration cache is reused on second build`() {
    val scenarioDirectory = scenarios().first()
    val project = TestProject(projectDir, scenarioDirectory)
    project.setup()

    // First build: populates the configuration cache
    val firstResult = project.gradle("normGenerateTest", "--configuration-cache").build()
    assertThat(firstResult.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)
    assertThat(firstResult.output).contains("Configuration cache entry stored")

    // Second build: must reuse the configuration cache
    val secondResult = project.gradle("normGenerateTest", "--configuration-cache").build()
    assertThat(secondResult.output).contains("Configuration cache entry reused")
  }

  @Test
  fun `stale generated files are deleted when queries are removed`() {
    val scenarioDirectory = BASIC_EMBEDS_SCENARIO
    val project = TestProject(projectDir, scenarioDirectory)
    project.setupSettingsOnly()

    // Create initial queries file with 2 queries
    val schema = scenarioDirectory.resolve("schema.sql")
    val queriesFile = projectDir.resolve("queries.sql")
    queriesFile.writeText(
      """
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
      """
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

  @Test
  fun `schema application failure includes the filename in the error`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    val badSchemaFile = projectDir.resolve("bad_schema.sql")
    badSchemaFile.writeText("CREATE TABLE broken (id NOT_A_REAL_TYPE);")

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
            schemas.addAll("$badSchemaFile")
            queries.addAll("${BASIC_EMBEDS_SCENARIO.resolve("queries.sql").normalize().toAbsolutePath()}")
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").buildAndFail()
    assertThat(result.output).contains("bad_schema.sql")
    assertThat(result.output).contains("line 1")
  }

  @Test
  fun `query analysis failure includes the query name in the error`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    val badQueriesFile = projectDir.resolve("bad_queries.sql")
    badQueriesFile.writeText(
      """
      -- name: selectFromNowhere :many
      SELECT * FROM table_that_does_not_exist;
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
            schemas.addAll("${BASIC_EMBEDS_SCENARIO.resolve("schema.sql").normalize().toAbsolutePath()}")
            queries.addAll("$badQueriesFile")
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").buildAndFail()
    assertThat(result.output).contains("selectFromNowhere")
    assertThat(result.output).contains("bad_queries.sql:1")
  }

  @Test
  fun `query file parse failure includes the filename in the error`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    val badQueriesFile = projectDir.resolve("mixed_params.sql")
    badQueriesFile.writeText(
      """
      -- name: mixedParams :one
      SELECT * FROM author WHERE id = :id AND name = ?;
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
            schemas.addAll("${BASIC_EMBEDS_SCENARIO.resolve("schema.sql").normalize().toAbsolutePath()}")
            queries.addAll("$badQueriesFile")
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").buildAndFail()
    assertThat(result.output).contains("mixed_params.sql")
    assertThat(result.output).contains("Cannot mix named")
  }

  @Test
  fun `synthesized CRUD query failure identifies the source table`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    // A table whose name contains a literal double-quote character (created via the "" escape in SQL DDL).
    // CrudQuerySynthesizer wraps identifiers in double-quotes but does not escape embedded quotes,
    // so the synthesized SQL contains an invalid identifier like `"tab"le"`, causing a SQL error.
    val schema = projectDir.resolve("schema.sql")
    schema.writeText("""CREATE TABLE "tab""le" (id serial PRIMARY KEY);""")

    val queries = projectDir.resolve("queries.sql")
    queries.writeText("")

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
            queries.addAll("$queries")
            generateCrud = true
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").buildAndFail()
    assertThat(result.output).contains("synthesized CRUD for table")
    assertThat(result.output).contains("tab")
  }

  @Test
  fun `schemas can be specified as a directory`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    val schemaDir = projectDir.resolve("migrations")
    Files.createDirectories(schemaDir)
    schemaDir.resolve("V001__create_author.sql").writeText(
      AUTHOR_TABLE_SCHEMA_SQL,
    )

    val queries = projectDir.resolve("queries.sql")
    queries.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
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
            schemas.addAll("$schemaDir")
            queries.addAll("$queries")
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").build()
    assertThat(result.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `schemas can mix individual files and directories`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    val baseSchema = projectDir.resolve("base.sql")
    baseSchema.writeText("CREATE TABLE base_table (id serial PRIMARY KEY);")

    val migrationDir = projectDir.resolve("migrations")
    Files.createDirectories(migrationDir)
    migrationDir.resolve("V001__author.sql").writeText(
      AUTHOR_TABLE_SCHEMA_SQL,
    )

    val queries = projectDir.resolve("queries.sql")
    queries.writeText(
      """
      -- name: getAllAuthors :many
      SELECT * FROM author;

      -- name: getAllBase :many
      SELECT * FROM base_table;
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
            schemas.addAll("$baseSchema", "$migrationDir")
            queries.addAll("$queries")
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").build()
    assertThat(result.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `configuration cache works with directory-based schemas`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    val schemaDir = projectDir.resolve("migrations")
    Files.createDirectories(schemaDir)
    schemaDir.resolve("V001__create_author.sql").writeText(
      AUTHOR_TABLE_SCHEMA_SQL,
    )

    val queries = projectDir.resolve("queries.sql")
    queries.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
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
            schemas.addAll("$schemaDir")
            queries.addAll("$queries")
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    val firstResult = project.gradle("normGenerateTest", "--configuration-cache").build()
    assertThat(firstResult.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)
    assertThat(firstResult.output).contains("Configuration cache entry stored")

    val secondResult = project.gradle("normGenerateTest", "--configuration-cache").build()
    assertThat(secondResult.output).contains("Configuration cache entry reused")
  }

  @Test
  fun `non-sql files in schema directory are ignored`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    val schemaDir = projectDir.resolve("migrations")
    Files.createDirectories(schemaDir)
    schemaDir.resolve("V001__create_author.sql").writeText(
      AUTHOR_TABLE_SCHEMA_SQL,
    )
    schemaDir.resolve("README.md").writeText("This directory contains migrations.")

    val queries = projectDir.resolve("queries.sql")
    queries.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
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
            schemas.addAll("$schemaDir")
            queries.addAll("$queries")
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").build()
    assertThat(result.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `schema directory applies Flyway versioned migrations in numeric version order`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    // Lexically, "V10__..." sorts before "V2__...". If schemas were still applied by lexical
    // path order, V10's ALTER TABLE would run before V2's CREATE TABLE and the replay would fail.
    val schemaDir = projectDir.resolve("migrations")
    Files.createDirectories(schemaDir)
    schemaDir.resolve("V2__create_author.sql").writeText(
      AUTHOR_TABLE_SCHEMA_SQL,
    )
    schemaDir.resolve("V10__add_email.sql").writeText(
      "ALTER TABLE author ADD COLUMN email text;",
    )

    val queries = projectDir.resolve("queries.sql")
    queries.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
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
            schemas.addAll("$schemaDir")
            queries.addAll("$queries")
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").build()
    assertThat(result.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)

    val generatedFiles = collectKotlinFiles(project.generatedCodeDirectory, project.generatedCodeDirectory)
    assertThat(generatedFiles.values.any { it.contains("email") }, "Expected the email column in generated code")
      .isTrue()
  }

  @Test
  fun `schema entries apply in declaration order across files and directories`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    // Named so that lexically-by-absolute-path sorting would place "migrations/..." before
    // "zz_base.sql", running the ALTER TABLE before the CREATE TABLE it depends on.
    val baseSchema = projectDir.resolve("zz_base.sql")
    baseSchema.writeText(AUTHOR_TABLE_SCHEMA_SQL)

    val migrationDir = projectDir.resolve("migrations")
    Files.createDirectories(migrationDir)
    migrationDir.resolve("V1__add_email.sql").writeText(
      "ALTER TABLE author ADD COLUMN email text;",
    )

    val queries = projectDir.resolve("queries.sql")
    queries.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
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
            schemas.addAll("$baseSchema", "$migrationDir")
            queries.addAll("$queries")
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").build()
    assertThat(result.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `a repeatable migration in an earlier schemas entry runs after a versioned migration in a later entry`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    // Regression case for per-entry (rather than global) ordering: the first directory's repeatable
    // migration depends on a table created by the second directory's versioned migration. Per-entry
    // ordering applied the first directory's repeatable migration right after that directory's own
    // (empty) versioned-migration list, before the second directory was even considered — running the
    // view creation before the table it selects from existed.
    val firstDirectory = projectDir.resolve("views")
    Files.createDirectories(firstDirectory)
    firstDirectory.resolve("R__author_view.sql").writeText(
      "CREATE VIEW author_view AS SELECT * FROM author;",
    )

    val secondDirectory = projectDir.resolve("migrations")
    Files.createDirectories(secondDirectory)
    secondDirectory.resolve("V1__create_author.sql").writeText(AUTHOR_TABLE_SCHEMA_SQL)

    val queries = projectDir.resolve("queries.sql")
    queries.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author_view;
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
            schemas.addAll("$firstDirectory", "$secondDirectory")
            queries.addAll("$queries")
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").build()
    assertThat(result.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `a plain file that a versioned migration depends on is not reordered ahead of it`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    // "Base.sql" sorts lexically before "V1__add_email.sql". Treating every versioned file as
    // sorting ahead of every plain file (rather than only reordering versioned files among
    // themselves) would run this ALTER TABLE before the CREATE TABLE it depends on.
    val schemaDir = projectDir.resolve("migrations")
    Files.createDirectories(schemaDir)
    schemaDir.resolve("Base.sql").writeText(
      AUTHOR_TABLE_SCHEMA_SQL,
    )
    schemaDir.resolve("V1__add_email.sql").writeText(
      "ALTER TABLE author ADD COLUMN email text;",
    )

    val queries = projectDir.resolve("queries.sql")
    queries.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
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
            schemas.addAll("$schemaDir")
            queries.addAll("$queries")
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").build()
    assertThat(result.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)

    val generatedFiles = collectKotlinFiles(project.generatedCodeDirectory, project.generatedCodeDirectory)
    assertThat(generatedFiles.values.any { it.contains("email") }, "Expected the email column in generated code")
      .isTrue()
  }

  @Test
  fun `undo migrations are skipped and logged with a warning`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    val schemaDir = projectDir.resolve("migrations")
    Files.createDirectories(schemaDir)
    schemaDir.resolve("V1__create_author.sql").writeText(
      AUTHOR_TABLE_SCHEMA_SQL,
    )
    schemaDir.resolve("U1__undo.sql").writeText("DROP TABLE author;")

    val queries = projectDir.resolve("queries.sql")
    queries.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
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
            schemas.addAll("$schemaDir")
            queries.addAll("$queries")
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").build()
    assertThat(result.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.output).contains("Skipping undo migration")
    assertThat(result.output).contains("U1__undo.sql")

    val generatedFiles = collectKotlinFiles(project.generatedCodeDirectory, project.generatedCodeDirectory)
    assertThat(generatedFiles.values.any { it.contains("Author") }, "Expected the author table in generated code")
      .isTrue()
  }

  @Test
  fun `editing only an undo migration does not invalidate the up-to-date check`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    val schemaDir = projectDir.resolve("migrations")
    Files.createDirectories(schemaDir)
    schemaDir.resolve("V1__create_author.sql").writeText(
      AUTHOR_TABLE_SCHEMA_SQL,
    )
    val undoFile = schemaDir.resolve("U1__undo.sql")
    undoFile.writeText("DROP TABLE author;")

    val queries = projectDir.resolve("queries.sql")
    queries.writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
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
            schemas.addAll("$schemaDir")
            queries.addAll("$queries")
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )

    val firstResult = project.gradle("normGenerateTest").build()
    assertThat(firstResult.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)

    // The undo migration is never replayed, so it must not be part of the task's declared inputs.
    undoFile.writeText("DROP TABLE author; -- edited")

    val secondResult = project.gradle("normGenerateTest").build()
    assertThat(secondResult.task(":normGenerateTest")?.outcome).isEqualTo(UP_TO_DATE)
  }

  @Test
  fun `CRUD generation succeeds for tables with reserved keyword names`() {
    val project = TestProject(projectDir, BASIC_EMBEDS_SCENARIO)
    project.setupSettingsOnly()

    // "user" is a PostgreSQL reserved keyword; CRUD-generated SQL must quote it as an identifier
    val schema = projectDir.resolve("schema.sql")
    schema.writeText("""CREATE TABLE "user" (id serial PRIMARY KEY, name text NOT NULL);""")

    val queries = projectDir.resolve("queries.sql")
    queries.writeText("")

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
            queries.addAll("$queries")
            generateCrud = true
          }
        }
      }
      """.trimIndent(),
    )

    val result = project.gradle("normGenerateTest").build()
    assertThat(result.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)
  }

  companion object {

    /**
     * A scenario with a simple `author` table, used by hand-written tests that
     * need a known schema but aren't testing golden-file matching.
     */
    private val BASIC_EMBEDS_SCENARIO = Path("../test-scenarios/basic_embeds").toAbsolutePath().normalize()

    private val EMBED_SCENARIOS =
      setOf("basic_embeds", "complex_embed_mixing", "consecutive_embeds", "nested_joins_embeds")

    /**
     * Returns all non-embed test scenario directories, sorted by name.
     *
     * Sorting is required because [Path.listDirectoryEntries] does not guarantee order.
     */
    @JvmStatic
    fun scenarios() = Path("../test-scenarios/").toAbsolutePath().normalize()
      .listDirectoryEntries()
      .filter(Files::isDirectory)
      .filter { it.fileName.toString() !in EMBED_SCENARIOS }
      .sorted()
  }
}
