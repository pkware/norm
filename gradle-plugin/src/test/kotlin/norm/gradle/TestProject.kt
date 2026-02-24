package norm.gradle

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Path
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
   * @param frameworks Optional set of framework names to enable. If empty, no frameworks are configured.
   */
  fun setup(frameworks: Set<String> = emptySet()) {
    setupSettingsOnly()

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
