package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * Tests the full code generation pipeline: schema SQL → JDBC analysis → Kotlin code generation.
 *
 * Uses a real PostgreSQL Testcontainer to analyze schemas and queries via JDBC metadata,
 * then compares generated Kotlin code against golden files.
 *
 * Golden files are regenerated via `./gradlew :gradle-plugin:generateGoldenFiles`.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class GenerateCodeTest {

  /**
   * Represents a framework test scenario.
   *
   * @property scenarioDirectory The directory containing the scenario's `schema.sql` and `queries.sql`
   * @property frameworks The set of frameworks to pass to generateCode()
   * @property goldenSubdir The subdirectory name containing expected golden files (e.g., "micronaut")
   */
  data class FrameworkScenario(val scenarioDirectory: Path, val frameworks: Set<Framework>, val goldenSubdir: String) {
    override fun toString(): String = "${scenarioDirectory.fileName}/$goldenSubdir"
  }

  /**
   * Validates that generated code matches expected golden files.
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
   * Applies a scenario's schema to the database, runs the full analysis and generation pipeline,
   * and asserts that the generated code matches the expected golden files.
   *
   * Resets the database schema between scenarios by dropping and recreating the `public` schema.
   *
   * @param scenarioDirectory The directory containing schema.sql and queries.sql
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
    // Collect expected golden files
    val expectedFiles = mutableMapOf<String, String>()
    Files.walk(goldenSubdirectory).use { files ->
      files.forEach { file ->
        if (file.toString().endsWith(".kt")) {
          expectedFiles[file.relativeTo(goldenSubdirectory).pathString] = file.readText()
        }
      }
    }

    // Reset database: drop all user objects and extensions, then recreate public schema.
    // DEALLOCATE ALL clears server-side prepared-statement caches so that the next scenario
    // doesn't hit "cached plan must not change result type" when PostgreSQL reuses a stale plan.
    connection.createStatement().use {
      it.execute(
        """
        DEALLOCATE ALL;
        DROP SCHEMA public CASCADE;
        CREATE SCHEMA public;
        GRANT ALL ON SCHEMA public TO public;
        """.trimIndent(),
      )
    }
    val schema = scenarioDirectory.resolve("schema.sql").readText()
    connection.createStatement().use { it.execute(schema) }

    // Run the full pipeline
    val analyzer = JdbcAnalyzer(connection)
    val catalog = analyzer.buildCatalog()

    val parsedQueries = QueryFileParser.parse(scenarioDirectory.resolve("queries.sql").readText())
    val analyzedQueries = parsedQueries.map { analyzer.analyzeQuery(it, catalog) }

    val result = generateCode(catalog, analyzedQueries, packageName, frameworks, emptySet())
    val createdFiles = result.associate { spec ->
      Pair(spec.name, spec.contents.utf8())
    }.toMutableMap()

    // Compare generated code with golden files
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
    // Embed scenarios use sqlc.embed() which is not yet supported by the JDBC analyzer
    private val EMBED_SCENARIOS =
      setOf("basic_embeds", "complex_embed_mixing", "consecutive_embeds", "nested_joins_embeds")

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = PostgreSQLContainer(
      DockerImageName.parse("postgres:18").asCompatibleSubstituteFor("postgres"),
    ).apply {
      withDatabaseName("norm_generate_test")
      waitingFor(
        WaitAllStrategy()
          .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
          .withStrategy(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(60)),
      )
    }

    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::connection.isInitialized) connection.close()
    }

    @JvmStatic
    fun scenarios() = Path("../test-scenarios").toAbsolutePath()
      .listDirectoryEntries()
      .filter(Files::isDirectory)
      .filter { it.fileName.toString() !in EMBED_SCENARIOS }
      .sorted()

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
  }
}
