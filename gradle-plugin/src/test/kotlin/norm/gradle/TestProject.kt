package norm.gradle

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.wire.WireJsonAdapterFactory
import okio.buffer
import okio.sink
import okio.source
import org.gradle.testkit.runner.GradleRunner
import plugin.GenerateRequest
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
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

  val schemaJsonPath: Path
    get() = projectDir.resolve("build/tmp/norm/Test/schema.json")

  /**
   * Sets up both settings.gradle.kts and build.gradle.kts for a standard scenario test.
   * Requires [scenarioDirectory] to be set.
   */
  fun setup(requiresDatabase: Boolean) {
    setupSettingsOnly()

    val buildFileContent = """
      plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      norm {
        databases {
          create("Test") {
            packageName = "example"
            schemas.addAll("${scenarioDirectory.resolve("schema.sql").normalize().toAbsolutePath()}")
            queries.addAll("${scenarioDirectory.resolve("queries.sql").normalize().toAbsolutePath()}")
            useDatabase = $requiresDatabase
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

    @OptIn(ExperimentalStdlibApi::class)
    private val schemaJsonAdapter: JsonAdapter<GenerateRequest> =
      Moshi.Builder()
        .add(WireJsonAdapterFactory())
        .build()
        .adapter()

    /**
     * Reads and cleans a schema.json file by removing environment-specific fields.
     * Used for both generation (writing) and verification (comparing).
     */
    fun readAndCleanSchemaJson(path: Path): GenerateRequest = path.source().buffer().use(schemaJsonAdapter::fromJson)!!
      .copy(settings = null, sqlc_version = "Norm")

    /**
     * Writes a cleaned schema.json file, removing environment-specific fields.
     */
    fun writeCleanedSchemaJson(source: Path, destination: Path) {
      val cleaned = readAndCleanSchemaJson(source)
      destination.deleteIfExists()
      destination.sink().buffer().use { schemaJsonAdapter.toJson(it, cleaned) }
    }
  }
}
