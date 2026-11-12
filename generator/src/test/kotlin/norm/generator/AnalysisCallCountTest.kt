package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.RecordedCalls
import norm.generator.libpgquery.RecordingMachine
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs [JdbcAnalyzer.analyzeQuery] against a live PostgreSQL and records every text that reaches
 * [LibPgQuery.parse] and [LibPgQuery.scan] through a [RecordingMachine].
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class AnalysisCallCountTest {

  @Test
  fun `a plain query is scanned once and parsed once plus one probe`() {
    val sql = "SELECT id, name FROM author"

    val calls = recordAnalysis(sql)

    assertThat(calls.scanTexts).isEqualTo(listOf(sql))
    assertThat(calls.parseTexts).isEqualTo(listOf(sql, "SELECT id,  1  FROM author"))
  }

  @Test
  fun `a query with a placeholder and a CTE is scanned once and its parser form parsed once`() {
    val sql = "WITH a AS (SELECT id, name FROM author WHERE id = ?) SELECT id, name || 'x' AS n FROM a"
    val parserForm = ParsedSql(sql).parserForm

    val calls = recordAnalysis(sql)

    assertThat(calls.scanTexts.count { it == sql }).isEqualTo(1)
    assertThat(calls.scanTexts.count { it == parserForm }).isEqualTo(0)
    assertThat(calls.parseTexts.count { it == parserForm }).isEqualTo(1)
    assertThat(calls.parseTexts.distinct()).isEqualTo(calls.parseTexts)
    assertThat(calls.scanTexts.distinct()).isEqualTo(calls.scanTexts)
  }

  @Test
  fun `a CTE body is read from the parse of the whole statement without a second parse of any text`() {
    val sql = "WITH a AS (SELECT id, name FROM author) SELECT id, name FROM a"

    val calls = recordAnalysis(sql)

    assertThat(calls.parseTexts.distinct()).isEqualTo(calls.parseTexts)
    assertThat(calls.scanTexts).isEqualTo(listOf(sql))
  }

  @Test
  fun `reading the normalized expression of a parsed item scans and parses nothing`() {
    val items = ParsedSql("SELECT UPPER(name /* c */ ||  'x') AS n, id FROM author").outputItems

    val calls = RecordingMachine.record { items.forEach { it.selectItem.normalizedExpression } }

    assertThat(items.map { it.selectItem.normalizedExpression }).isEqualTo(listOf("UPPER(name || 'x')", "id"))
    assertThat(calls.scanTexts).isEqualTo(emptyList())
    assertThat(calls.parseTexts).isEqualTo(emptyList())
  }

  private fun recordAnalysis(sql: String): RecordedCalls = RecordingMachine.record {
    analyzer.analyzeQuery(ParsedQuery("probe", Command.MANY, sql, emptyList()), catalog)
  }

  companion object {
    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_call_count_test")

    private lateinit var connection: Connection
    private lateinit var analyzer: JdbcAnalyzer
    private lateinit var catalog: Catalog

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
      connection.createStatement().use {
        it.execute("CREATE TABLE author (id int PRIMARY KEY, name text NOT NULL)")
      }
      analyzer = JdbcAnalyzer(connection)
      catalog = analyzer.buildCatalog()
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::connection.isInitialized) connection.close()
    }
  }
}
