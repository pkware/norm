package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.wire.WireJsonAdapterFactory
import okio.buffer
import okio.source
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import plugin.GenerateRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

class GenerateCodeTest {

  /**
   * Represents a framework test scenario.
   *
   * @property scenarioDirectory The directory containing the scenario's schema.json
   * @property frameworks The set of frameworks to pass to generateCode()
   * @property goldenSubdir The subdirectory name containing expected golden files (e.g., "micronaut")
   */
  data class FrameworkScenario(val scenarioDirectory: Path, val frameworks: Set<Framework>, val goldenSubdir: String) {
    override fun toString(): String = "${scenarioDirectory.fileName}/$goldenSubdir"
  }

  /**
   * Validates that generated code matches expected golden files.
   *
   * Generate `schema.json` files for scenarios by running `./gradlew :gradle-plugin:generateGoldenFiles`.
   */
  @ParameterizedTest
  @MethodSource("scenarios")
  fun `generated code is correct`(scenarioDirectory: Path) {
    assertGeneratedCodeMatchesGoldenFiles(
      scenarioDirectory = scenarioDirectory,
      goldenSubdirectory = scenarioDirectory.resolve("example"),
      packageName = "example",
      frameworks = emptySet(),
    )
  }

  /**
   * Validates that code generation with different framework configurations produces correct output.
   *
   * Generate `schema.json` and golden files by running `./gradlew :gradle-plugin:generateGoldenFiles`.
   */
  @ParameterizedTest
  @MethodSource("frameworkScenarios")
  fun `generated code with frameworks matches golden files`(scenario: FrameworkScenario) {
    assertGeneratedCodeMatchesGoldenFiles(
      scenarioDirectory = scenario.scenarioDirectory,
      goldenSubdirectory = scenario.scenarioDirectory.resolve(scenario.goldenSubdir),
      packageName = "example",
      frameworks = scenario.frameworks,
    )
  }

  /**
   * Asserts that generated code matches expected golden files.
   *
   * @param scenarioDirectory The directory containing the scenario's schema.json
   * @param goldenSubdirectory The directory containing expected .kt golden files
   * @param packageName The package name to pass to generateCode()
   * @param frameworks The set of frameworks to pass to generateCode()
   */
  private fun assertGeneratedCodeMatchesGoldenFiles(
    scenarioDirectory: Path,
    goldenSubdirectory: Path,
    packageName: String,
    frameworks: Set<Framework>,
  ) {
    val expectedFiles = mutableMapOf<String, String>()
    val request = readGenerateRequestFromFile(scenarioDirectory.resolve("schema.json"))

    Files.walk(goldenSubdirectory).use { files ->
      files.forEach { file ->
        if (file.toString().endsWith(".kt")) {
          expectedFiles[file.relativeTo(goldenSubdirectory).pathString] = file.readText()
        }
      }
    }

    assertThat(request, "Directory $scenarioDirectory does not have a schema.json").isNotNull()
    val result = generateCode(request.catalog!!, request.queries, packageName, frameworks, emptySet())
    val createdFiles = result.associate { spec ->
      Pair(spec.name, spec.contents.utf8())
    }.toMutableMap()

    for ((fileName, content) in expectedFiles.entries) {
      assertThat(fileName in createdFiles, "Expected file $fileName to be generated").isTrue()
      val createdFileContent = createdFiles.remove(fileName)
      assertThat(
        createdFileContent,
        "Content for ${scenarioDirectory.resolve(fileName).toAbsolutePath()}",
      ).isEqualTo(content)
    }

    assertThat(createdFiles, "More files were created than expected").isEmpty()
  }

  companion object {
    @JvmStatic
    fun scenarios() = listOf(
      Path("../test-scenarios-basic"),
      Path("../test-scenarios-complex"),
    )
      .flatMap { it.toAbsolutePath().listDirectoryEntries() }
      .filter(Files::isDirectory)

    /**
     * Provides test scenarios for framework-specific code generation tests.
     *
     * Each scenario directory is tested with multiple framework configurations,
     * producing separate test cases for Micronaut, Spring, and all-tables variants.
     *
     * @return List of [FrameworkScenario] instances, one for each combination of
     *         scenario directory and framework configuration.
     */
    @JvmStatic
    fun frameworkScenarios(): List<FrameworkScenario> {
      val frameworkScenariosDir = Path("../test-scenarios-frameworks").toAbsolutePath().normalize()
      return frameworkScenariosDir.listDirectoryEntries()
        .filter(Files::isDirectory)
        .flatMap { scenarioDir ->
          listOf(
            FrameworkScenario(scenarioDir, setOf(Framework.MICRONAUT_DATA_JDBC), "micronaut"),
            FrameworkScenario(scenarioDir, setOf(Framework.SPRING_DATA_JDBC), "spring"),
            FrameworkScenario(scenarioDir, setOf(Framework.ALL_TABLES), "all-tables"),
          )
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun readGenerateRequestFromFile(path: Path): GenerateRequest {
      val moshi = Moshi.Builder()
        .addLast(WireJsonAdapterFactory())
        .build()
      val adapter = moshi.adapter<GenerateRequest>()
      return path.source().buffer().use(adapter::fromJson)!!
    }
  }
}
