package norm.gradle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.matches
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Loads `norm.generator.libpgquery.LibPgQuery` from the plugin classloader of a build that also applies the Micronaut
 * Gradle plugin.
 *
 * The fixture resolves `com.pkware.norm` from an included plugin build, so the plugin classpath holds the shadow jar
 * and not the compiled classes that `withPluginClasspath()` would add.
 */
@ResourceLock(TestProject.COMPOSITE_BUILD_RESOURCE_LOCK)
@Execution(ExecutionMode.SAME_THREAD)
class LibPgQueryPluginClasspathTest {

  @TempDir
  private lateinit var projectDir: Path

  @Test
  fun `the plugin jar serves LibPgQuery beside the Micronaut plugin`() {
    writeFixture()

    val result = GradleRunner.create()
      .withProjectDir(projectDir.toFile())
      .withArguments("probeLibPgQuery", "--stacktrace")
      .forwardOutput()
      .build()

    assertThat(result.task(":probeLibPgQuery")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.output).contains("version=180006")
    val location = result.output.lineSequence().single { it.startsWith(LOCATION_PREFIX) }.removePrefix(LOCATION_PREFIX)
    assertThat(location).matches(Regex(".*/gradle-plugin-${Regex.escape(BUILD_VERSION)}\\.jar"))
  }

  private fun writeFixture() {
    val normRoot = TestProject.ROOT_NORM_PROJECT_PATH.toString().replace('\\', '/')
    projectDir.resolve("settings.gradle.kts").writeText(
      """
      pluginManagement {
        includeBuild("$normRoot")
        repositories {
          mavenCentral()
          gradlePluginPortal()
        }
      }

      rootProject.name = "libpgquery-plugin-classpath"

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
        kotlin("jvm") version "${requiredProperty("norm.test.kotlinVersion")}"
        id("com.pkware.norm")
        id("io.micronaut.library") version "${requiredProperty("norm.test.micronautPluginVersion")}"
      }

      micronaut {
        version("${requiredProperty("norm.test.micronautVersion")}")
      }

      tasks.register("probeLibPgQuery") {
        doLast {
          val type = Class.forName("norm.generator.libpgquery.LibPgQuery")
          val instance = type.getField("INSTANCE").get(null)
          val tree = type.getMethod("parse", String::class.java).invoke(instance, "SELECT 1")
          val version = tree.javaClass.getMethod("intField", String::class.java).invoke(tree, "version")
          println("LibPgQuery parse result version=" + version)
          println("$LOCATION_PREFIX" + type.protectionDomain.codeSource.location)
        }
      }
      """.trimIndent(),
    )
  }

  private fun requiredProperty(name: String): String =
    checkNotNull(System.getProperty(name)) { "System property $name is not set" }

  private companion object {
    const val LOCATION_PREFIX = "LibPgQuery code source: "
  }
}
