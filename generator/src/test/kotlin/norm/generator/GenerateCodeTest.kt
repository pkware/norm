package norm.generator

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasClass
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
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

    // Scenarios opt in to CRUD generation via norm.properties. The default here is `false` (production
    // uses `true`) because most test scenarios' golden files were written without CRUD output.
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
   * Exercises `resolveColumnPostgresType` through [generateCode] because the function is private.
   * The user configures the full over-length name and the catalog holds the truncated one; a lookup
   * by the configured name that misses fails generation with "not found in catalog".
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
    val mapping = TypeMapping.ByColumn(
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

  @Test
  fun `column-level type mapping resolves when an earlier schema has a same-named table without the column`() {
    connection.createStatement().use {
      it.execute(
        """
        CREATE SCHEMA first_schema;
        CREATE TABLE first_schema.shared_table (id integer PRIMARY KEY);
        CREATE SCHEMA second_schema;
        CREATE TABLE second_schema.shared_table (id integer PRIMARY KEY, metadata jsonb NOT NULL);
        """.trimIndent(),
      )
    }
    try {
      val analyzer = JdbcAnalyzer(connection)
      val catalog = analyzer.buildCatalog(listOf("first_schema", "second_schema"))
      val mapping = TypeMapping.ByColumn(
        "shared_table",
        "metadata",
        "com.example.CustomJson",
        "com.example.CustomJsonAdapter",
      )

      val result = generateCode(
        catalog,
        emptyList(),
        "example",
        emptySet(),
        analyzer.fetchReservedWords(),
        listOf(mapping),
      )

      val implementationFile = result.first { it.name.endsWith("PostgresQueries.kt") }
      assertThat(implementationFile.contents).contains(userAdapterPropertyName(mapping))
    } finally {
      connection.createStatement().use {
        it.execute("DROP SCHEMA first_schema CASCADE; DROP SCHEMA second_schema CASCADE;")
      }
    }
  }

  /**
   * `adapterParameters` must run after `generateQueryInterface`, since a query parameter's column
   * type is only resolved while building interface methods, not while constructing `SqlStatement`.
   * An `UPDATE` with no result columns whose only reference to the enum is a `WHERE` parameter is the
   * only shape that can catch a regression that computes `adapterParameters` too early.
   */
  @Test
  fun `enum referenced only as an exec query parameter still gets a constructor adapter with its default`() {
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

    connection.createStatement().use {
      it.execute(
        """
        CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy');
        CREATE TABLE person (
          id integer PRIMARY KEY,
          name text NOT NULL,
          current_mood mood NOT NULL
        );
        """.trimIndent(),
      )
    }

    val analyzer = JdbcAnalyzer(connection)
    val catalog = analyzer.buildCatalog()

    val parsedQueries = QueryFileParser.parse(
      """
      -- name: updateName :exec
      UPDATE person SET name = ? WHERE current_mood = ?;
      """.trimIndent(),
    )
    val analyzedQueries = parsedQueries.map { analyzer.analyzeQuery(it, catalog) }

    val result = generateCode(
      catalog,
      analyzedQueries,
      "example",
      emptySet(),
      analyzer.fetchReservedWords(),
    )

    val implementationFile = result.first { it.name.endsWith("PostgresQueries.kt") }
    assertThat(implementationFile.contents).contains("moodAdapter: ColumnAdapter<Mood, String> = MoodAdapter()")
  }

  @Nested
  inner class AdapterNameCollisions {

    private val jsonAdapterTypes = "com.example.Json" to "com.example.JsonAdapter"
    private val otherAdapterTypes = "com.example.Other" to "com.example.OtherAdapter"

    private fun resetSchema(schemaSql: String) {
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
      connection.createStatement().use { it.execute(schemaSql) }
    }

    private fun generatePostgresQueries(querySql: String, mappings: List<TypeMapping>): String {
      val analyzer = JdbcAnalyzer(connection)
      val catalog = analyzer.buildCatalog()
      val analyzedQueries = QueryFileParser.parse(querySql).map { analyzer.analyzeQuery(it, catalog) }
      val result =
        generateCode(catalog, analyzedQueries, "example", emptySet(), analyzer.fetchReservedWords(), mappings)
      return result.first { it.name.endsWith("PostgresQueries.kt") }.contents
    }

    private fun byType(postgresType: String, types: Pair<String, String> = jsonAdapterTypes) =
      TypeMapping.ByType(postgresType, types.first, types.second)

    private fun byColumn(table: String, column: String, types: Pair<String, String> = jsonAdapterTypes) =
      TypeMapping.ByColumn(table, column, types.first, types.second)

    @Test
    fun `type and column mappings producing the same property name fail naming both`() {
      resetSchema(
        """
        CREATE DOMAIN users_metadata AS jsonb;
        CREATE TABLE users (id integer PRIMARY KEY, metadata jsonb NOT NULL);
        """.trimIndent(),
      )

      assertFailure {
        generatePostgresQueries(
          "-- name: listUsers :many\nSELECT id FROM users;",
          listOf(byType("users_metadata"), byColumn("users", "metadata")),
        )
      }.also {
        it.hasClass(IllegalStateException::class)
        it.messageContains("usersMetadataAdapter")
        it.messageContains("""type("users_metadata")""")
        it.messageContains("""column("users", "metadata")""")
      }
    }

    @Test
    fun `column mappings on different columns producing the same property name fail naming both`() {
      resetSchema(
        """
        CREATE TABLE user_s (id integer PRIMARY KEY, x jsonb NOT NULL);
        CREATE TABLE "user" (id integer PRIMARY KEY, s_x jsonb NOT NULL);
        """.trimIndent(),
      )

      assertFailure {
        generatePostgresQueries(
          "-- name: listUserS :many\nSELECT id FROM user_s;",
          listOf(byColumn("user_s", "x"), byColumn("user", "s_x")),
        )
      }.also {
        it.hasClass(IllegalStateException::class)
        it.messageContains("userSXAdapter")
        it.messageContains("""column("user_s", "x")""")
        it.messageContains("""column("user", "s_x")""")
      }
    }

    @Test
    fun `two different type mappings for the same Postgres type fail naming both`() {
      resetSchema("CREATE TABLE users (id integer PRIMARY KEY, metadata jsonb NOT NULL);")

      assertFailure {
        generatePostgresQueries(
          "-- name: listUsers :many\nSELECT id, metadata FROM users;",
          listOf(byType("jsonb", jsonAdapterTypes), byType("jsonb", otherAdapterTypes)),
        )
      }.also {
        it.hasClass(IllegalStateException::class)
        it.messageContains("""type("jsonb")""")
        it.messageContains("com.example.JsonAdapter")
        it.messageContains("com.example.OtherAdapter")
      }
    }

    @Test
    fun `two different column mappings for the same truncated column fail naming both`() {
      val longTable = "t".repeat(63)
      resetSchema("CREATE TABLE $longTable (id integer PRIMARY KEY, metadata jsonb NOT NULL);")

      assertFailure {
        generatePostgresQueries(
          "-- name: listRows :many\nSELECT id FROM $longTable;",
          listOf(
            byColumn("${longTable}aaa", "metadata", jsonAdapterTypes),
            byColumn("${longTable}bbb", "metadata", otherAdapterTypes),
          ),
        )
      }.also {
        it.hasClass(IllegalStateException::class)
        it.messageContains("""column("${longTable}aaa", "metadata")""")
        it.messageContains("""column("${longTable}bbb", "metadata")""")
      }
    }

    @Test
    fun `column mapping colliding with a referenced enum adapter fails naming the mapping and the enum`() {
      resetSchema(
        """
        CREATE TYPE users_metadata AS ENUM ('a', 'b');
        CREATE TABLE users (id integer PRIMARY KEY, metadata jsonb NOT NULL, kind users_metadata NOT NULL);
        """.trimIndent(),
      )

      assertFailure {
        generatePostgresQueries(
          "-- name: listUsers :many\nSELECT id, kind FROM users;",
          listOf(byColumn("users", "metadata")),
        )
      }.also {
        it.hasClass(IllegalStateException::class)
        it.messageContains("usersMetadataAdapter")
        it.messageContains("""column("users", "metadata")""")
        it.messageContains("""enum "users_metadata"""")
      }
    }

    @Test
    fun `column mapping colliding with a referenced domain adapter fails naming the mapping and the domain`() {
      resetSchema(
        """
        CREATE DOMAIN users_metadata AS text;
        CREATE TABLE users (id integer PRIMARY KEY, metadata jsonb NOT NULL, kind users_metadata NOT NULL);
        """.trimIndent(),
      )

      assertFailure {
        generatePostgresQueries(
          "-- name: listUsers :many\nSELECT id FROM users WHERE kind = ?;",
          listOf(byColumn("users", "metadata")),
        )
      }.also {
        it.hasClass(IllegalStateException::class)
        it.messageContains("usersMetadataAdapter")
        it.messageContains("""column("users", "metadata")""")
        it.messageContains("""domain "users_metadata"""")
      }
    }

    @Test
    fun `equal duplicate mappings generate one constructor parameter`() {
      resetSchema("CREATE TABLE users (id integer PRIMARY KEY, metadata jsonb NOT NULL);")

      val contents = generatePostgresQueries(
        "-- name: listUsers :many\nSELECT id, metadata FROM users;",
        listOf(byColumn("users", "metadata"), byColumn("users", "metadata")),
      )

      assertThat(Regex("usersMetadataAdapter: ColumnAdapter").findAll(contents).count()).isEqualTo(1)
    }
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
          add(TypeMapping.ByType(postgresType, kotlinType, adapterType))
        } else if (keyStr.startsWith("typeMapping.column.")) {
          val remainder = keyStr.removePrefix("typeMapping.column.")
          val dotIndex = remainder.indexOf('.')
          require(dotIndex > 0) { "Invalid column mapping key: $keyStr" }
          val table = remainder.substring(0, dotIndex)
          val column = remainder.substring(dotIndex + 1)
          add(TypeMapping.ByColumn(table, column, kotlinType, adapterType))
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
