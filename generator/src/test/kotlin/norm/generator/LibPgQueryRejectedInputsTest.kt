package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException

private const val OLD_NEW_RETURNING = "UPDATE t SET x = 1 RETURNING WITH (OLD AS o, NEW AS n) o.x, n.x"

private fun PostgreSQLContainer<*>.execute(sql: String): String? =
  DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
    connection.autoCommit = false
    connection.createStatement().use { it.execute("CREATE TABLE t(x int, id int, name text)") }
    try {
      connection.createStatement().use { it.execute(sql) }
      null
    } catch (error: SQLException) {
      error.message
    } finally {
      connection.rollback()
    }
  }

/**
 * Executes the inputs the prototype fails to parse on PostgreSQL, to sort the failures of
 * [LibPgQuerySqlOutputClauseTest] into inputs PostgreSQL rejects and inputs it accepts.
 */
@Testcontainers
class LibPgQueryRejectedInputsTest {
  @Nested
  inner class PostgreSql18 {
    private val rejected = listOf(
      "UPDATE t SET x = 1 RETURNING id, a[b AS c",
      "UPDATE t SET x = 1 RETURNING id, ) (bogus AS alias",
      "SELECT 2€, email FROM users",
      "SELECT \"\" FROM c",
    )

    @Test
    fun `rejects every input the prototype degrades to an empty list`() {
      for (sql in rejected) {
        val serverError = container.execute(sql)
        println("PG18 REJECT $sql -> $serverError")
        assertThat(serverError ?: "accepted").contains("ERROR")
        assertThat(libPgQueryParseSelectItems(sql)).isEmpty()
      }
    }

    @Test
    fun `accepts the OLD NEW RETURNING prologue`() {
      val serverError = container.execute(OLD_NEW_RETURNING)
      println("PG18 ACCEPT $OLD_NEW_RETURNING -> ${serverError ?: "executed"}")
      assertThat(serverError).isEqualTo(null)
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["16", "17"])
  fun `earlier majors reject the OLD NEW RETURNING prologue`(major: String) {
    PostgreSQLContainer(DockerImageName.parse("postgres:$major-alpine").asCompatibleSubstituteFor("postgres")).use {
      it.start()
      val serverError = it.execute(OLD_NEW_RETURNING)
      println("PG$major REJECT $OLD_NEW_RETURNING -> $serverError")
      assertThat(serverError ?: "accepted").contains("syntax error")
    }
  }

  companion object {
    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_libpgquery_rejected", inMemory = true)
  }
}
