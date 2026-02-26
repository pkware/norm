package norm.gradle

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

/**
 * Sets up a Gradle test project configured to use the Norm plugin with a specific scenario.
 *
 * The project uses Gradle's composite build feature to substitute the published `runtime` artifact
 * with the local project, enabling tests to run against unpublished changes.
 */
class TestProject(private val projectDir: Path, private val scenarioDirectory: Path) {

  val buildFile: Path = projectDir.resolve("build.gradle.kts")

  val generatedCodeDirectory: Path
    get() = projectDir.resolve("build").resolve(NormPlugin.NORM_GENERATED_CODE)

  /**
   * Sets up both settings.gradle.kts and build.gradle.kts for a standard scenario test.
   *
   * Reads optional `norm.properties` from the scenario directory for per-scenario configuration.
   * Supported properties:
   * - `generateCrud=true` — enables CRUD method generation.
   *
   * @param frameworks Optional set of framework names to enable. If empty, no frameworks are configured.
   */
  fun setup(frameworks: Set<String> = emptySet()) {
    setupSettingsOnly()

    val scenarioProperties = loadScenarioProperties()

    val frameworkImport = if (frameworks.isNotEmpty()) {
      "import norm.generator.Framework\n\n"
    } else {
      ""
    }

    val frameworksConfigBlock = if (frameworks.isNotEmpty()) {
      "frameworks.addAll(${frameworks.joinToString(", ") { "Framework.$it" }})"
    } else {
      ""
    }

    val generateCrud = scenarioProperties.getProperty("generateCrud", "false").toBoolean()
    val generateCrudBlock = "generateCrud = $generateCrud"

    val buildFileContent = """
      ${frameworkImport}plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      norm {
        databases {
          create("Test") {
            packageName = "example"
            schemas.addAll("${scenarioDirectory.resolve("schema.sql").normalize().toAbsolutePath()}")
            queries.addAll("${scenarioDirectory.resolve("queries.sql").normalize().toAbsolutePath()}")
            $frameworksConfigBlock
            $generateCrudBlock
          }
        }
      }

      kotlin {
        compilerOptions {
          allWarningsAsErrors = true
        }
      }
    """.trimIndent()
    buildFile.writeText(buildFileContent)
  }

  /**
   * Loads scenario-specific configuration from `norm.properties` if it exists.
   */
  private fun loadScenarioProperties(): Properties {
    val propsFile = scenarioDirectory.resolve("norm.properties")
    return Properties().apply {
      if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
      }
    }
  }

  /**
   * Sets up only settings.gradle.kts, for tests that need custom build.gradle.kts content.
   */
  fun setupSettingsOnly() {
    val includePath = ROOT_NORM_PROJECT_PATH.toString().replace('\\', '/')
    val settingsContent = """
      rootProject.name = "norm-test-project"

      // Include the parent Norm build to substitute runtime dependency with local project
      includeBuild("$includePath")

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
    """.trimIndent()
    projectDir.resolve("settings.gradle.kts").writeText(settingsContent)
  }

  fun gradle(vararg tasks: String): GradleRunner = GradleRunner.create()
    .withProjectDir(projectDir.toFile())
    .withArguments(*tasks)
    .withPluginClasspath()
    .forwardOutput()

  companion object {
    val ROOT_NORM_PROJECT_PATH: Path = Path.of("..").normalize().toAbsolutePath()
  }
}
