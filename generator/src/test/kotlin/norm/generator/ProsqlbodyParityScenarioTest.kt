package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
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
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Stage 1 `prosqlbody`-cutover parity harness for the `queries.sql` corpus under `test-scenarios`.
 *
 * For every named query in every scenario's `queries.sql`, asserts that
 * [PgCatalogLoader.queryColumnNullabilityViaProsqlbody] agrees EXACTLY with
 * [PgCatalogLoader.queryColumnNullability] (the production `CREATE VIEW` route), against the
 * scenario's own real `schema.sql`. This is the second half of the required Stage 1 evidence — see
 * [QueryAnalysisTest.assertProsqlbodyParity]'s KDoc for the first half, which covers every test in
 * [QueryAnalysisTest] itself.
 *
 * Remove this file once the cutover reaches the stage where
 * [PgCatalogLoader.queryColumnNullability]'s `CREATE VIEW` route is deleted — without it there is
 * nothing left to compare against.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class ProsqlbodyParityScenarioTest {

  @ParameterizedTest
  @MethodSource("scenarios")
  fun `prosqlbody path agrees with the view path for every query in the scenario`(
    scenarioDirectory: java.nio.file.Path,
  ) {
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

    val parsedQueries = QueryFileParser.parse(scenarioDirectory.resolve("queries.sql").readText())
    val loader = PgCatalogLoader(connection)
    for (parsedQuery in parsedQueries) {
      val prosqlbodyResult = loader.queryColumnNullabilityViaProsqlbody(parsedQuery.sql) ?: continue
      val expected = KNOWN_ACCEPTED_DIFFERENCES[parsedQuery.name] ?: loader.queryColumnNullability(parsedQuery.sql)
      assertThat(prosqlbodyResult, "query '${parsedQuery.name}' in $scenarioDirectory").isEqualTo(expected)
    }
  }

  companion object {
    /**
     * Documented, individually-verified exceptions to the strict parity assertion above, keyed by
     * [ParsedQuery.name] — see [QueryAnalysisTest]'s identical `knownProsqlbodyImprovement`
     * mechanism for the general idea. Every entry here is the OPPOSITE direction from that one:
     * `updateParentReturningDescriptionUpper`'s `WHERE id = ?` disables
     * [PgCatalogLoader.queryColumnNullabilityViaProsqlbody]'s `:targetList`-substitution for the
     * WHOLE statement (see that method's `trustAssignedExpressions` KDoc — the gate is
     * per-statement, not per-assignment, because there is no structural way from here to tell
     * WHICH assignment a parameter feeds), even though the assignment `description_upper` actually
     * depends on (`description = 'UPDATED'`) is a plain literal, unrelated to the parameter. This
     * makes prosqlbody's answer here SAFE but LESS precise than production's (nullable vs. the
     * true, verified-live NOT NULL) — the accepted cost of a whole-statement safety gate, not a bug.
     */
    private val KNOWN_ACCEPTED_DIFFERENCES: Map<String, List<Boolean>> = mapOf(
      "updateParentReturningDescriptionUpper" to listOf(false, false, true),
    )

    // Embed scenarios use sqlc.embed(), a marker function with no real pg_proc definition — the
    // SQL never prepares against a live connection at all, on EITHER path, the same reason
    // GenerateCodeTest.EMBED_SCENARIOS excludes them from its own scenario list.
    private val EMBED_SCENARIOS =
      setOf("basic_embeds", "complex_embed_mixing", "consecutive_embeds", "nested_joins_embeds")

    private val pgVersion = System.getProperty("norm.test.pgVersion", "18")

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = PostgreSQLContainer(
      DockerImageName.parse("postgres:$pgVersion-alpine").asCompatibleSubstituteFor("postgres"),
    ).apply {
      withDatabaseName("norm_prosqlbody_parity_test")
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
  }
}
