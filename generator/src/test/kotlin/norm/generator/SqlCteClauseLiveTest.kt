package norm.generator

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import norm.generator.libpgquery.CommonTableExpression
import norm.generator.libpgquery.StatementNode
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Compares [StatementNode.commonTableExpressions] results with a live PostgreSQL server. */
@Testcontainers
class SqlCteClauseLiveTest {

  @Test
  fun `the server truncates an over-length ASCII CTE name to the name commonTableExpressions reports`() {
    val sql = "WITH ${"c".repeat(70)} AS (SELECT 1 AS v) SELECT v FROM ${"c".repeat(63)}"
    assertThat(firstColumn(sql)).isEqualTo("1")
    assertThat(definitions(sql)[0].name).isEqualTo("c".repeat(63))
  }

  @Test
  fun `the server truncates an over-length multi-byte CTE name to the name commonTableExpressions reports`() {
    val sql = "WITH ${"é".repeat(32)} AS (SELECT 1 AS v) SELECT v FROM ${"é".repeat(31)}"
    assertThat(firstColumn(sql)).isEqualTo("1")
    assertThat(definitions(sql)[0].name).isEqualTo("é".repeat(31))
  }

  @Test
  fun `the server resolves a CTE declared inside a parenthesized statement`() {
    val sql = "(WITH x AS (SELECT 1 AS v) SELECT v FROM x)"
    assertThat(firstColumn(sql)).isEqualTo("1")
    assertThat(definitions(sql)).hasSize(1)
  }

  @Test
  fun `the server rejects a body holding a literal dollar template and commonTableExpressions is empty`() {
    val sql = "WITH renamed AS (\n  SELECT a\${'$'}b\${'$'}c AS msg FROM note\n),\n" +
      "counted AS (\n  SELECT length(msg) AS len FROM renamed\n)\nSELECT len FROM counted"
    assertThat(syntaxErrorState(sql)).isEqualTo(SYNTAX_ERROR_STATE)
    assertThat(definitions(sql)).isEmpty()
  }

  @Test
  fun `the server rejects an INSERT body without INTO and commonTableExpressions is empty`() {
    val sql = "WITH w AS (INSERT  (a) VALUES (1) RETURNING a) SELECT a INTO c FROM w"
    assertThat(syntaxErrorState(sql)).isEqualTo(SYNTAX_ERROR_STATE)
    assertThat(definitions(sql)).isEmpty()
  }

  private fun definitions(sql: String): List<CommonTableExpression> =
    ParsedSql(sql).tree?.firstStatement?.commonTableExpressions.orEmpty()

  private fun firstColumn(@Language("PostgreSQL") sql: String): String = connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { resultSet ->
      check(resultSet.next()) { "No row returned by $sql" }
      resultSet.getString(1)
    }
  }

  private fun syntaxErrorState(@Language("PostgreSQL") sql: String): String? =
    assertThrows<SQLException> { connection.createStatement().use { it.execute(sql) } }.sqlState

  companion object {
    private const val SYNTAX_ERROR_STATE = "42601"

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_cte_clause_test", inMemory = true)

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
  }
}
