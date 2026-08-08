package norm.gradle

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Pins the plugin's minimum supported Gradle version, determined empirically with
 * `GradleRunner.withGradleVersion(...)` (see the module README's Requirements section).
 *
 * [MINIMUM_SUPPORTED_GRADLE_VERSION] (9.1.0) is confirmed to apply the plugin successfully. Gradle 9.1.0 is
 * the only version this project supports, so no lower version is exercised here: asserting that older
 * versions fail would make CI download a full Gradle distribution per version for no coverage of anything
 * Norm promises. Lower versions are expected to fail because this module is compiled to Java 25 bytecode
 * (the toolchain configured by buildSrc's `JavaConventionsPlugin`), which is what pushes the floor above
 * whatever Norm's own Gradle API usage would require on its own.
 *
 * This test does not use [TestProject]'s composite `includeBuild` of the Norm root project, because that
 * build's own `buildSrc` has the same Java 25 floor and would fail to configure under old Gradle versions
 * for the same reason, independent of this plugin — it would no longer isolate the plugin's own floor.
 */
class GradleVersionCompatibilityTest {

  @TempDir
  private lateinit var projectDir: Path

  @Test
  fun `plugin applies successfully on the minimum supported Gradle version`() {
    writeProjectFiles()

    val result = GradleRunner.create()
      .withGradleVersion(MINIMUM_SUPPORTED_GRADLE_VERSION)
      .withProjectDir(projectDir.toFile())
      .withArguments("help")
      .withPluginClasspath()
      .forwardOutput()
      .build()

    assertThat(result.task(":help")?.outcome).isEqualTo(SUCCESS)
  }

  private fun writeProjectFiles() {
    val schema = projectDir.resolve("schema.sql")
    schema.writeText("CREATE TABLE author (id serial PRIMARY KEY, name text NOT NULL);")
    val queries = projectDir.resolve("queries.sql")
    queries.writeText("")

    projectDir.resolve("settings.gradle.kts").writeText(
      """
      rootProject.name = "gradle-version-floor-test"

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

    projectDir.resolve("build.gradle.kts").writeText(
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
            generateCrud = false
          }
        }
      }
      """.trimIndent(),
    )
  }

  companion object {
    /**
     * Keep in sync with the "Requirements" section of `gradle-plugin/README.md`.
     */
    const val MINIMUM_SUPPORTED_GRADLE_VERSION: String = "9.1.0"
  }
}
