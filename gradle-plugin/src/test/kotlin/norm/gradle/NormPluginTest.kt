package norm.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import com.google.common.truth.Truth.assertThat
import com.pkware.norm.gradle.NormPlugin
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.wire.WireJsonAdapterFactory
import okio.buffer
import okio.sink
import okio.source
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import plugin.GenerateRequest
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.stream.Stream
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

class NormPluginTest {

  @TempDir
  private lateinit var testProjectDir: Path
  private lateinit var buildFile: Path

  @BeforeEach
  fun setup() {
    buildFile = testProjectDir.resolve("build.gradle.kts")
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  fun `code can be generated`(scenarioDirectory: Path) {
    scenarioDirectory.resolve("sql").absolutePathString()
    val buildFileContent = """
      plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      norm {
        databases {
          create("Test") {
            packageName = "example"
            schemas.addAll("${testProjectDir.relativize(scenarioDirectory.resolve("schema.sql"))}")
            queries.addAll("${testProjectDir.relativize(scenarioDirectory.resolve("queries.sql"))}")
          }
        }
      }
    """.trimIndent()
    buildFile.writeText(buildFileContent)

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.toFile())
      .withArguments("normGenerateCode")
      .withPluginClasspath()
      .build()

    println(result.output)
    assertThat(result.task(":normGenerateCodeTest")!!.outcome).isEqualTo(SUCCESS)

    // Copy the schema file from the project back to the scenario folder,
    // so it can be used in generator integration tests
    transferSchemaJsonToDirectory(scenarioDirectory)

    // Copy the generated code back to the scenario folder. For new scenarios, this bootstraps the test. For existing
    // scenarios, it shows a change in how the code is being generated.
    val generatedCodeDirectory = testProjectDir.resolve("build").resolve(NormPlugin.NORM_GENERATED_CODE)
    Files.walk(generatedCodeDirectory).use { files ->
      files.forEach { file ->
        val pathRelativeToBase = file.relativeTo(generatedCodeDirectory)
        val target = scenarioDirectory.resolve(pathRelativeToBase)
        try {
          Files.copy(
            file,
            target,
            // TODO Uncomment this to set up a new scenario
//            java.nio.file.StandardCopyOption.REPLACE_EXISTING
          )
        } catch (ignored: FileAlreadyExistsException) {
        } catch (ignored: DirectoryNotEmptyException) {
        }
      }
    }
  }

  // TODO Test that package with periods gets properly generated
  // TODO Test that tasks are correctly cached

  @OptIn(ExperimentalStdlibApi::class)
  private fun transferSchemaJsonToDirectory(scenarioDirectory: Path) {
    val moshi = Moshi.Builder()
      .add(WireJsonAdapterFactory())
      .build()
    val requestJsonAdapter = moshi.adapter<GenerateRequest>()

    val request =
      testProjectDir.resolve("build/tmp/norm/Test/schema.json").source().buffer().use(requestJsonAdapter::fromJson)!!
        // Clear the settings because they have environment-specific file paths and so aren't suitable for source control
        .copy(settings = null)
    scenarioDirectory.resolve("schema.json").sink().buffer().use { requestJsonAdapter.toJson(it, request) }
  }

  companion object {
    @JvmStatic
    fun scenarios(): Stream<Path> = Files.list(Path("../test-scenarios/").normalize().toAbsolutePath())
  }
}
