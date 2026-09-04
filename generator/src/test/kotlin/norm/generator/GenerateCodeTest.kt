package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
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

    // Scenarios opt in to CRUD generation via norm.properties. The default here is false (not true
    // as in production) because most test scenarios' golden files were written without CRUD output.
    val scenarioProperties = Properties().apply {
      val propsFile = scenarioDirectory.resolve("norm.properties")
      if (propsFile.exists()) propsFile.inputStream().use { load(it) }
    }
    val allParsedQueries = if (scenarioProperties.getProperty("generateCrud", "false").toBoolean()) {
      // Must match NormGenerateTask's production call: without the real quoter, a synthesized
      // statement referencing a quoted/mixed-case/space-containing column (e.g. the
      // crud_generation scenario's "quoted_columns" table) comes back unquoted and fails with a
      // PostgreSQL syntax error at analysis time, before reaching golden comparison.
      CrudQuerySynthesizer.synthesizeAndMerge(catalog, parsedQueries, analyzer.buildIdentifierQuoter())
    } else {
      parsedQueries
    }

    val analyzedQueries = allParsedQueries.map { analyzer.analyzeQuery(it, catalog) }

    val typeMappings = parseTypeMappings(scenarioProperties)

    val effectivePackageName = scenarioProperties.getProperty("packageName") ?: packageName
    val result = generateCode(
      catalog,
      analyzedQueries,
      effectivePackageName,
      frameworks,
      // Must match NormGenerateTask's production call: without the live-fetched reserved word
      // set, a scenario naming a relation/column after a reserved word (e.g. "order", "user")
      // renders an unquoted source reference that never runs against PostgreSQL, and golden
      // comparison would silently accept text SourceReferenceLiveVerificationTest would reject.
      analyzer.fetchReservedWords(),
      typeMappings,
    )
    val createdFiles = result.associate { spec ->
      Pair(spec.name, spec.contents)
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

  /**
   * Goes through [generateCode] rather than calling `resolveColumnPostgresType` directly, which is
   * private. Looking that column up under the name the user configured, rather than the truncated
   * one the catalog holds, used to fail the whole generation with "not found in catalog".
   */
  @Test
  fun `column-level type mapping on an over-length table and column name resolves through the catalog`() {
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

    // Both names are exactly 70 ASCII characters -- one byte over PostgreSQL's 63-byte identifier
    // limit -- so the server truncates the real relation/column names on CREATE TABLE.
    val overLengthTableName = "over_length_table_used_for_main_kt_column_mapping_crash_regressionzzzz"
    val overLengthColumnName = "over_length_column_used_for_main_kt_column_mapping_crash_regressionzzz"
    connection.createStatement().use {
      it.execute(
        """
        CREATE TABLE $overLengthTableName (
          id integer PRIMARY KEY,
          $overLengthColumnName jsonb NOT NULL
        );
        """.trimIndent(),
      )
    }

    val analyzer = JdbcAnalyzer(connection)
    val catalog = analyzer.buildCatalog()
    val truncatedTableName = truncateIdentifier(overLengthTableName)

    val parsedQueries = QueryFileParser.parse(
      """
      -- name: getRow :one
      SELECT * FROM $truncatedTableName WHERE id = ?;
      """.trimIndent(),
    )
    val analyzedQueries = parsedQueries.map { analyzer.analyzeQuery(it, catalog) }

    // Configured with the full, untruncated names -- what a user would write, taken from their
    // own DDL -- not the server-truncated forms the catalog actually holds.
    val mapping = TypeMapping(
      "",
      overLengthTableName,
      overLengthColumnName,
      "com.example.CustomJson",
      "com.example.CustomJsonAdapter",
    )

    val result = generateCode(
      catalog,
      analyzedQueries,
      "example",
      emptySet(),
      analyzer.fetchReservedWords(),
      listOf(mapping),
    )

    val expectedAdapterPropertyName = userAdapterPropertyName(mapping)
    val implementationFile = result.first { it.name.endsWith("PostgresQueries.kt") }
    assertThat(implementationFile.contents).contains(expectedAdapterPropertyName)
    assertThat(implementationFile.contents).contains("CustomJson")
  }

  companion object {
    // Embed scenarios use sqlc.embed() which is not yet supported by the JDBC analyzer
    private val EMBED_SCENARIOS =
      setOf("basic_embeds", "complex_embed_mixing", "consecutive_embeds", "nested_joins_embeds")

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_generate_test")

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

    /**
     * Parses [TypeMapping] entries from scenario properties.
     *
     * Format:
     * - `typeMapping.type.<postgresType>=<kotlinType>:<adapterType>`
     * - `typeMapping.column.<table>.<column>=<kotlinType>:<adapterType>`
     */
    private fun parseTypeMappings(properties: Properties): List<TypeMapping> = buildList {
      for ((key, value) in properties) {
        val keyStr = key.toString()
        val valueStr = value.toString()
        if (!keyStr.startsWith("typeMapping.")) continue
        val parts = valueStr.split(":")
        require(parts.size == 2) { "Invalid type mapping value: $valueStr" }
        val (kotlinType, adapterType) = parts

        if (keyStr.startsWith("typeMapping.type.")) {
          val postgresType = keyStr.removePrefix("typeMapping.type.")
          add(TypeMapping(postgresType, null, null, kotlinType, adapterType))
        } else if (keyStr.startsWith("typeMapping.column.")) {
          val remainder = keyStr.removePrefix("typeMapping.column.")
          val dotIndex = remainder.indexOf('.')
          require(dotIndex > 0) { "Invalid column mapping key: $keyStr" }
          val table = remainder.substring(0, dotIndex)
          val column = remainder.substring(dotIndex + 1)
          add(TypeMapping("", table, column, kotlinType, adapterType))
        }
      }
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
            FrameworkScenario(scenarioDir, setOf(Framework.MICRONAUT_DATA), "micronaut"),
            FrameworkScenario(scenarioDir, setOf(Framework.MICRONAUT), "micronaut-di"),
            FrameworkScenario(scenarioDir, setOf(Framework.SPRING_DATA), "spring"),
          )
        }
    }
  }
}
