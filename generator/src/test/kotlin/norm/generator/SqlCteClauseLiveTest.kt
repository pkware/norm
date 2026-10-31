package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
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

/** Compares [parseCteClause] results with a live PostgreSQL server. */
@Testcontainers
class SqlCteClauseLiveTest {

  @Test
  fun `the server truncates an over-length ASCII CTE name to the name parseCteClause reports`() {
    val sql = "WITH ${"c".repeat(70)} AS (SELECT 1 AS v) SELECT v FROM ${"c".repeat(63)}"
    assertThat(firstColumn(sql)).isEqualTo("1")
    assertThat(parseCteClause(sql)!!.definitions[0].name).isEqualTo("c".repeat(63))
  }

  @Test
  fun `the server truncates an over-length multi-byte CTE name to the name parseCteClause reports`() {
    val sql = "WITH ${"é".repeat(32)} AS (SELECT 1 AS v) SELECT v FROM ${"é".repeat(31)}"
    assertThat(firstColumn(sql)).isEqualTo("1")
    assertThat(parseCteClause(sql)!!.definitions[0].name).isEqualTo("é".repeat(31))
  }

  @Test
  fun `the server resolves a CTE declared inside a parenthesized statement`() {
    val sql = "(WITH x AS (SELECT 1 AS v) SELECT v FROM x)"
    assertThat(firstColumn(sql)).isEqualTo("1")
    assertThat(parseCteClause(sql)).isNotNull()
  }

  @Test
  fun `the server rejects a body holding a literal dollar template and parseCteClause returns null`() {
    val sql = "WITH renamed AS (\n  SELECT a\${'$'}b\${'$'}c AS msg FROM note\n),\n" +
      "counted AS (\n  SELECT length(msg) AS len FROM renamed\n)\nSELECT len FROM counted"
    assertThat(syntaxErrorState(sql)).isEqualTo(SYNTAX_ERROR_STATE)
    assertThat(parseCteClause(sql)).isNull()
  }

  @Test
  fun `the server rejects an INSERT body without INTO and parseCteClause returns null`() {
    val sql = "WITH w AS (INSERT  (a) VALUES (1) RETURNING a) SELECT a INTO c FROM w"
    assertThat(syntaxErrorState(sql)).isEqualTo(SYNTAX_ERROR_STATE)
    assertThat(parseCteClause(sql)).isNull()
  }

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
