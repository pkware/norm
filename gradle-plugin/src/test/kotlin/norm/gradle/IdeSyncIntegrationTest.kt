package norm.gradle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Verifies Norm's sync-time generation mechanism (`IdeIntegration.configureGenerationOnIdeSync`): wiring
 * `normGenerate<Name>` tasks as dependencies of `prepareKotlinIdeaImportNorm`, the task Norm registers
 * under its own name that IntelliJ IDEA runs on every Gradle sync (see [IdeIntegration] for why the name
 * matters).
 *
 * Replaces an earlier `gradle-idea-ext` `afterSync` approach, which reached from a project into
 * `project.rootProject` and was illegal under Gradle's Isolated Projects for any project but the root.
 * `configureGenerationOnIdeSync` only touches its own project's `tasks` container, so it needs no such
 * guard.
 *
 * Shares [TestProject.COMPOSITE_BUILD_RESOURCE_LOCK] with `NormPluginTest`: both run TestKit builds that
 * `includeBuild` the Norm root project, and two running at once race on the shared `buildSrc` project's
 * incremental compilation cache. See the lock's KDoc for details.
 */
@ResourceLock(TestProject.COMPOSITE_BUILD_RESOURCE_LOCK)
@Execution(ExecutionMode.SAME_THREAD)
class IdeSyncIntegrationTest {

  @TempDir
  private lateinit var projectDir: Path

  @Nested
  inner class SubprojectUnderIsolatedProjects {

    /**
     * Norm applied to a subproject, not the root. `--dry-run` proves the dependency edge exists without
     * paying for a real container start; [RealGenerationOnSync] covers actual execution more cheaply as a
     * single-project build.
     */
    @Test
    fun `dry run shows normGenerateTest as a dependency of prepareKotlinIdeaImportNorm`() {
      setUpMultiProjectAppBuild(appDirectory = projectDir.resolve("app"))

      val result = gradleRunner(":app:prepareKotlinIdeaImportNorm", "--dry-run", "--isolated-projects").build()

      // ":app:normGenerateTest" isn't on the command line, so it only appears here because of the
      // dependency edge under test — unlike ":app:prepareKotlinIdeaImportNorm", which dry-run always echoes.
      assertThat(result.output).contains(":app:normGenerateTest")
    }

    @Test
    fun `configures cleanly with zero cross-project problems`() {
      setUpMultiProjectAppBuild(appDirectory = projectDir.resolve("app"))

      // Under Gradle 9.7, an Isolated Projects violation fails the build instead of printing a
      // diagnostic. `.build()` returning at all (not throwing `UnexpectedBuildFailure`) is the proof no
      // cross-project access occurred.
      val result = gradleRunner(":app:help", "--isolated-projects").build()

      assertThat(result.task(":app:help")?.outcome).isEqualTo(SUCCESS)
    }
  }

  @Nested
  inner class RealGenerationOnSync {

    /**
     * Confirms the wiring actually runs generation, not just that a dependency edge exists on paper.
     * Single-project build, cheaper than a second Testcontainers-backed multi-project build, since
     * [SubprojectUnderIsolatedProjects] already covers the subproject dependency wiring via `--dry-run`.
     * Isolated Projects stays enabled here too, to confirm real execution is unaffected by it.
     */
    @Test
    fun `running prepareKotlinIdeaImportNorm actually generates code on first sync`() {
      val project = singleProjectWithNormConfigured()

      val result = project.gradle("prepareKotlinIdeaImportNorm", "--isolated-projects").build()

      assertThat(result.task(":normGenerateTest")?.outcome).isEqualTo(SUCCESS)
      assertThat(result.task(":prepareKotlinIdeaImportNorm")?.outcome).isEqualTo(SUCCESS)
      assertThat(project.generatedCodeDirectory.resolve("example").exists()).isTrue()
    }
  }

  @Nested
  inner class ResolvedTaskGraph {

    /**
     * Asserts directly on [org.gradle.api.tasks.TaskDependency] rather than `--dry-run` text or build
     * outcome: a verification task queries `prepareKotlinIdeaImportNorm`'s resolved dependencies and fails
     * the build if `normGenerateTest` is not among them. Catches a `dependsOn` resolving to an empty list
     * even if the rest of the build still happens to succeed.
     */
    @Test
    fun `prepareKotlinIdeaImportNorm resolves normGenerateTest as a task dependency`() {
      writeAuthorSchemaAndQueries(projectDir)
      val project = TestProject(projectDir, projectDir)
      project.setupSettingsOnly()

      project.buildFile.writeText(
        """
        plugins {
          kotlin("jvm")
          id("com.pkware.norm")
        }

        norm {
          ${databasesBlock(projectDir)}
        }

        tasks.register("assertPrepareKotlinIdeaImportDependsOnNormGenerate") {
          doLast {
            val syncTask = tasks.named("prepareKotlinIdeaImportNorm").get()
            val resolvedDependencies = syncTask.taskDependencies.getDependencies(syncTask).map { it.path }
            check(":normGenerateTest" in resolvedDependencies) {
              "Expected prepareKotlinIdeaImportNorm to resolve normGenerateTest as a dependency, " +
                "but resolved: ${'$'}resolvedDependencies"
            }
          }
        }
        """.trimIndent(),
      )

      val result = project.gradle("assertPrepareKotlinIdeaImportDependsOnNormGenerate").build()

      assertThat(result.task(":assertPrepareKotlinIdeaImportDependsOnNormGenerate")?.outcome).isEqualTo(SUCCESS)
    }
  }

  @Nested
  inner class ConsumerRegistersItsOwnPrepareKotlinIdeaImportTask {

    /**
     * A consumer may register their own task named exactly `prepareKotlinIdeaImport` at any point —
     * including inside `afterEvaluate { }` — without colliding with Norm. Norm's sync-time task is
     * [IdeIntegration.PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME] (`prepareKotlinIdeaImportNorm`), a name it
     * owns outright, so no `prepareKotlinIdeaImport`-named task from Kotlin Gradle Plugin or a consumer
     * can collide with it.
     */
    @Test
    fun `builds successfully when the consumer's task is registered inside afterEvaluate`() {
      val project = singleProjectWithConsumerPrepareKotlinIdeaImportTask(registerInsideAfterEvaluate = true)

      val result = project.gradle("help").build()

      assertThat(result.task(":help")?.outcome).isEqualTo(SUCCESS)
    }

    /**
     * Same scenario, but the consumer's task is registered at build-script top level, which ran before
     * Norm's `afterEvaluate` even in the pre-fix design and so already passed. Kept alongside the test
     * above to cover both orderings.
     */
    @Test
    fun `builds successfully when the consumer's task is registered at top level`() {
      val project = singleProjectWithConsumerPrepareKotlinIdeaImportTask(registerInsideAfterEvaluate = false)

      val result = project.gradle("help").build()

      assertThat(result.task(":help")?.outcome).isEqualTo(SUCCESS)
    }
  }

  @Nested
  inner class GenerateOnIdeSyncOptOut {

    /**
     * With `generateOnIdeSync = false`, `prepareKotlinIdeaImportNorm` is still registered — it's Norm's
     * own task, so registering it unconditionally is harmless — but depends on nothing. The build
     * succeeding at all proves the task exists (Gradle fails outright on an unregistered task); the
     * `--dry-run` output missing `:normGenerateTest` proves the flag suppressed the dependency. This
     * fixture registers nothing besides Norm's own tasks, so there's no other source of a
     * `prepareKotlinIdeaImport*` task name.
     */
    @Test
    fun `disables the dependency when set before the databases block`() {
      val project = singleProjectWithGenerateOnIdeSyncDisabled(setBeforeDatabasesBlock = true)

      val result = project.gradle(":prepareKotlinIdeaImportNorm", "--dry-run").build()

      assertThat(result.output).doesNotContain(":normGenerateTest")
    }

    @Test
    fun `disables the dependency when set after the databases block`() {
      val project = singleProjectWithGenerateOnIdeSyncDisabled(setBeforeDatabasesBlock = false)

      val result = project.gradle(":prepareKotlinIdeaImportNorm", "--dry-run").build()

      assertThat(result.output).doesNotContain(":normGenerateTest")
    }
  }

  @Nested
  inner class TaskNamePrefixContract {

    /**
     * Pins the most fragile fact in this design: IntelliJ IDEA discovers sync-time tasks by matching names
     * that start with `prepareKotlinIdeaImport`, not by exact match (see
     * [IdeIntegration.PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME]'s KDoc). Renaming Norm's task to drop that
     * prefix would silently break IDE sync generation with no build failure to catch it.
     */
    @Test
    fun `Norm's sync task name starts with prepareKotlinIdeaImport`() {
      assertThat(IdeIntegration.PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME).startsWith("prepareKotlinIdeaImport")
    }
  }

  /**
   * Minimal, valid `author` schema and matching query — enough for Norm to configure generation without
   * exercising any particular SQL feature.
   */
  private fun writeAuthorSchemaAndQueries(directory: Path) {
    directory.resolve("schema.sql").writeText(AUTHOR_TABLE_SCHEMA_SQL)
    directory.resolve("queries.sql").writeText(
      """
      -- name: getAll :many
      SELECT * FROM author;
      """.trimIndent(),
    )
  }

  private fun databasesBlock(directory: Path) = """
    databases {
      create("Test") {
        packageName = "example"
        schemas.addAll("${directory.resolve("schema.sql").normalize().toAbsolutePath()}")
        queries.addAll("${directory.resolve("queries.sql").normalize().toAbsolutePath()}")
        generateCrud = false
      }
    }
  """.trimIndent()

  /**
   * Single-project build with Norm configured against a valid schema and query; no
   * `prepareKotlinIdeaImport*` task other than Norm's own is registered.
   */
  private fun singleProjectWithNormConfigured(): TestProject {
    writeAuthorSchemaAndQueries(projectDir)
    val project = TestProject(projectDir, projectDir)
    project.setupSettingsOnly()

    project.buildFile.writeText(
      """
      plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      norm {
        ${databasesBlock(projectDir)}
      }
      """.trimIndent(),
    )
    return project
  }

  /**
   * Single-project build where the consumer registers their own, unrelated task named exactly
   * `prepareKotlinIdeaImport`, either inside `afterEvaluate { }` (matching how Kotlin Gradle Plugin's own
   * tooling might register the task lazily, per
   * [IdeIntegration.PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME]'s KDoc) or at top level, depending on
   * [registerInsideAfterEvaluate].
   */
  private fun singleProjectWithConsumerPrepareKotlinIdeaImportTask(registerInsideAfterEvaluate: Boolean): TestProject {
    writeAuthorSchemaAndQueries(projectDir)
    val project = TestProject(projectDir, projectDir)
    project.setupSettingsOnly()

    val consumerTaskRegistration = """
      tasks.register("prepareKotlinIdeaImport") {
        description = "The consumer's own task, unrelated to Norm, that happens to share Kotlin Gradle " +
          "Plugin's exact task name."
      }
    """.trimIndent()
    val consumerTaskBlock = if (registerInsideAfterEvaluate) {
      "afterEvaluate {\n  $consumerTaskRegistration\n}"
    } else {
      consumerTaskRegistration
    }

    project.buildFile.writeText(
      """
      plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      $consumerTaskBlock

      norm {
        ${databasesBlock(projectDir)}
      }
      """.trimIndent(),
    )
    return project
  }

  /**
   * Single-project build with `generateOnIdeSync = false`, set either before or after the
   * `databases { }` block depending on [setBeforeDatabasesBlock]. Registers nothing besides Norm itself,
   * so the only `prepareKotlinIdeaImport*` task that can appear is Norm's own.
   */
  private fun singleProjectWithGenerateOnIdeSyncDisabled(setBeforeDatabasesBlock: Boolean): TestProject {
    writeAuthorSchemaAndQueries(projectDir)
    val project = TestProject(projectDir, projectDir)
    project.setupSettingsOnly()

    val generateOnIdeSyncLine = "generateOnIdeSync = false"
    val normBlockBody = if (setBeforeDatabasesBlock) {
      "$generateOnIdeSyncLine\n${databasesBlock(projectDir)}"
    } else {
      "${databasesBlock(projectDir)}\n$generateOnIdeSyncLine"
    }

    project.buildFile.writeText(
      """
      plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      norm {
        $normBlockBody
      }
      """.trimIndent(),
    )
    return project
  }

  /**
   * Two-project build: an empty root project and an included `app` subproject applying Kotlin JVM and
   * Norm. No `gradle-idea-ext` involved — `prepareKotlinIdeaImportNorm` wiring is project-local, so the
   * root project needs no configuration.
   */
  private fun setUpMultiProjectAppBuild(appDirectory: Path) {
    appDirectory.createDirectories()
    writeAuthorSchemaAndQueries(appDirectory)

    val includePath = TestProject.ROOT_NORM_PROJECT_PATH.toString().replace('\\', '/')
    projectDir.resolve("settings.gradle.kts").writeText(
      """
      rootProject.name = "norm-ide-sync-test"

      // Include the parent Norm build to substitute runtime dependency with local project
      includeBuild("$includePath")

      include("app")

      pluginManagement {
        repositories {
          mavenCentral()
          gradlePluginPortal()
        }
      }

      dependencyResolutionManagement {
        repositories {
          mavenCentral()
        }
      }
      """.trimIndent(),
    )
    projectDir.resolve("build.gradle.kts").writeText("")
    appDirectory.resolve("build.gradle.kts").writeText(
      """
      plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      norm {
        ${databasesBlock(appDirectory)}
      }
      """.trimIndent(),
    )
  }

  private fun gradleRunner(vararg tasks: String) = TestProject.gradleRunner(projectDir, *tasks)
}
