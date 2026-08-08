package norm.gradle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import org.gradle.testkit.runner.GradleRunner
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
 * `normGenerate<Name>` tasks as dependencies of `prepareKotlinIdeaImportNorm`, a task Norm registers under
 * its own name and that IntelliJ IDEA runs on every Gradle sync (see [IdeIntegration] for why the name
 * matters).
 *
 * This mechanism replaces an earlier `gradle-idea-ext` `afterSync`-based approach, which required reaching
 * from a project into `project.rootProject` and was therefore illegal under Gradle's Isolated Projects for
 * any project other than the root. `configureGenerationOnIdeSync` only ever touches its own project's
 * `tasks` container, so it needs no such guard.
 *
 * Shares [TestProject.COMPOSITE_BUILD_RESOURCE_LOCK] with `NormPluginTest`: both classes run TestKit builds
 * that `includeBuild` the Norm root project, and running two of those builds at once races on the shared
 * `buildSrc` project's incremental compilation caches. See the lock's KDoc for details.
 */
@ResourceLock(TestProject.COMPOSITE_BUILD_RESOURCE_LOCK)
@Execution(ExecutionMode.SAME_THREAD)
class IdeSyncIntegrationTest {

  @TempDir
  private lateinit var projectDir: Path

  @Nested
  inner class SubprojectUnderIsolatedProjects {

    /**
     * Covers the case named in the task's acceptance criteria: Norm applied to a subproject, not the
     * root. A real Testcontainers-backed run of `:app:normGenerateTest` is exercised more cheaply in
     * [RealGenerationOnSync], as a single-project build; here, `--dry-run` is enough to prove the
     * *dependency* exists without paying for a real container start, and is what actually needs a
     * multi-project shape to be meaningful (a single-project build can't demonstrate the subproject case
     * at all).
     */
    @Test
    fun `dry run shows normGenerateTest as a dependency of prepareKotlinIdeaImportNorm`() {
      setUpMultiProjectAppBuild(appDirectory = projectDir.resolve("app"))

      val result = gradleRunner(":app:prepareKotlinIdeaImportNorm", "--dry-run", "--isolated-projects").build()

      assertThat(result.output).contains(":app:normGenerateTest")
      assertThat(result.output).contains(":app:prepareKotlinIdeaImportNorm")
    }

    @Test
    fun `configures cleanly with zero cross-project problems`() {
      setUpMultiProjectAppBuild(appDirectory = projectDir.resolve("app"))

      // Under Gradle 9.7, an Isolated Projects violation fails the build rather than merely printing a
      // diagnostic. `.build()` returning at all — instead of throwing `UnexpectedBuildFailure` — is
      // therefore already the proof that no cross-project access occurred; there is no build output left
      // to assert against for a violation that, by construction, could not have happened here.
      val result = gradleRunner(":app:help", "--isolated-projects").build()

      assertThat(result.task(":app:help")?.outcome).isEqualTo(SUCCESS)
    }
  }

  @Nested
  inner class RealGenerationOnSync {

    /**
     * Proves the wiring actually runs generation, not just that a dependency edge exists on paper. Uses a
     * single-project build (cheaper than starting a second Testcontainers-backed build against a
     * multi-project shape) since [SubprojectUnderIsolatedProjects] already covers the subproject-specific
     * dependency wiring via `--dry-run`. Isolated Projects is also enabled here to demonstrate that real
     * execution, not just configuration, is unaffected by it.
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
  inner class ConsumerRegistersItsOwnPrepareKotlinIdeaImportTask {

    /**
     * Guarantees a consumer may register their own task named exactly `prepareKotlinIdeaImport` at any
     * point — including from inside their own `afterEvaluate { }` block — without colliding with Norm.
     * This is safe because Norm registers its sync-time task under
     * [IdeIntegration.PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME] (`prepareKotlinIdeaImportNorm`), a name Norm
     * owns outright and never shares with a consumer or with Kotlin Gradle Plugin's own task, so there is
     * no task named exactly `prepareKotlinIdeaImport` for a consumer's task of that name to collide with,
     * however or whenever they register it.
     */
    @Test
    fun `builds successfully when the consumer's task is registered inside afterEvaluate`() {
      val project = singleProjectWithConsumerPrepareKotlinIdeaImportTask(registerInsideAfterEvaluate = true)

      val result = project.gradle("help").build()

      assertThat(result.task(":help")?.outcome).isEqualTo(SUCCESS)
    }

    /**
     * The same scenario, but with the consumer's task registered at build-script top level, which already
     * ran before Norm's `afterEvaluate` in the pre-fix design and so already passed. Kept to cover the
     * ordering that never triggered the defect, alongside the one above that did.
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
     * With `generateOnIdeSync = false`, `prepareKotlinIdeaImportNorm` is still registered — it is Norm's
     * own task, so registering it unconditionally is harmless — but must depend on nothing. Asserting that
     * `:prepareKotlinIdeaImportNorm` itself appears in the `--dry-run` output proves Norm's task wiring
     * actually ran (as opposed to being skipped altogether), while asserting `:normGenerateTest` is absent
     * proves the flag suppressed the dependency specifically. Unlike a fixture that pre-registers a
     * consumer's own `prepareKotlinIdeaImport` task, this fixture registers nothing besides what Norm
     * itself registers, so there is no other source of a `prepareKotlinIdeaImport*` task name that could
     * make the "no dependency" assertion pass for the wrong reason.
     */
    @Test
    fun `disables the dependency when set before the databases block`() {
      val project = singleProjectWithGenerateOnIdeSyncDisabled(setBeforeDatabasesBlock = true)

      val result = project.gradle(":prepareKotlinIdeaImportNorm", "--dry-run").build()

      assertThat(result.output).contains(":prepareKotlinIdeaImportNorm")
      assertThat(result.output).doesNotContain(":normGenerateTest")
    }

    @Test
    fun `disables the dependency when set after the databases block`() {
      val project = singleProjectWithGenerateOnIdeSyncDisabled(setBeforeDatabasesBlock = false)

      val result = project.gradle(":prepareKotlinIdeaImportNorm", "--dry-run").build()

      assertThat(result.output).contains(":prepareKotlinIdeaImportNorm")
      assertThat(result.output).doesNotContain(":normGenerateTest")
    }
  }

  @Nested
  inner class TaskNamePrefixContract {

    /**
     * Pins the single most fragile fact in this design: IntelliJ IDEA discovers sync-time tasks by
     * matching task names that START WITH `prepareKotlinIdeaImport`, not by exact name (see
     * [IdeIntegration.PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME]'s KDoc). Renaming Norm's task to anything that
     * doesn't keep this prefix would silently break IDE sync generation with no build failure to catch it
     * — this test is the cheap guard against that happening by accident.
     */
    @Test
    fun `Norm's sync task name starts with prepareKotlinIdeaImport`() {
      assertThat(IdeIntegration.PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME).startsWith("prepareKotlinIdeaImport")
      assertThat(IdeIntegration.PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME).isEqualTo("prepareKotlinIdeaImportNorm")
    }
  }

  /**
   * Writes a minimal, valid `author` table schema and a matching query into [directory], for tests that
   * need Norm to configure (and, in [RealGenerationOnSync], actually run) generation without exercising
   * any particular SQL feature.
   */
  private fun writeAuthorSchemaAndQueries(directory: Path) {
    directory.resolve("schema.sql").writeText("CREATE TABLE author (id serial PRIMARY KEY, name text NOT NULL);")
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
   * Builds a single-project build with Norm configured against a valid schema and query, and no
   * `prepareKotlinIdeaImport*` task registered by anything other than Norm itself.
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
   * Builds a single-project build where the consumer registers their own, unrelated task named exactly
   * `prepareKotlinIdeaImport`, either inside an `afterEvaluate { }` block (matching how Kotlin Gradle
   * Plugin's own tooling — or a future release of it — might register the task lazily, per
   * [IdeIntegration.PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME]'s KDoc) or at build-script top level, depending
   * on [registerInsideAfterEvaluate].
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
   * Builds a single-project build with `generateOnIdeSync = false`, set either before or after the
   * `databases { }` block depending on [setBeforeDatabasesBlock]. Registers nothing besides Norm itself, so
   * the only `prepareKotlinIdeaImport*` task that can appear in the build is Norm's own.
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
   * Sets up a two-project build: an empty root project and an included `app` subproject applying the
   * Kotlin JVM plugin and Norm. No `gradle-idea-ext` is involved — `prepareKotlinIdeaImportNorm` wiring is
   * entirely project-local, so the root project needs no configuration at all.
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

  private fun gradleRunner(vararg tasks: String) = GradleRunner.create()
    .withProjectDir(projectDir.toFile())
    .withArguments(*tasks)
    .withPluginClasspath()
    .forwardOutput()
}
