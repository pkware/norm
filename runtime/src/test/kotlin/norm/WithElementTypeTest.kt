package norm

import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class WithElementTypeTest {

  private lateinit var connection: Connection

  @BeforeEach
  fun connect() {
    connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    connection.createStatement().use { statement ->
      statement.execute("DROP DOMAIN IF EXISTS event_date CASCADE")
      statement.execute("DROP DOMAIN IF EXISTS positive_integer CASCADE")
      statement.execute("DROP DOMAIN IF EXISTS note_text CASCADE")
      statement.execute("CREATE DOMAIN event_date AS date")
      statement.execute("CREATE DOMAIN positive_integer AS int4")
      statement.execute("CREATE DOMAIN note_text AS text")
    }
  }

  @AfterEach
  fun disconnect() {
    connection.close()
  }

  @Test
  fun `date domain array elements read as LocalDate through the date codec`() {
    val domainArray = selectArray("SELECT ARRAY['2024-02-29', '1999-12-31']::event_date[]")

    val elements = domainArray.withElementType(connection, "date").mapElements { getObject(2, LocalDate::class.java) }

    assertThat(elements.toList()).containsExactly(LocalDate.of(2024, 2, 29), LocalDate.of(1999, 12, 31))
  }

  @Test
  fun `date domain array keeps a NULL element as null`() {
    val domainArray = selectArray("SELECT ARRAY['2024-02-29', NULL]::event_date[]")

    val elements = domainArray.withElementType(connection, "date").mapElements { getObject(2, LocalDate::class.java) }

    assertThat(elements.toList()).containsExactly(LocalDate.of(2024, 2, 29), null)
  }

  @Test
  fun `int4 domain array elements read through getInt`() {
    val domainArray = selectArray("SELECT ARRAY[1, NULL, 2000000000]::positive_integer[]")

    val elements = domainArray.withElementType(connection, "int4").mapElements {
      getInt(2).takeUnless { wasNull() }
    }

    assertThat(elements.toList()).containsExactly(1, null, 2_000_000_000)
  }

  @Test
  fun `text domain array preserves quote, backslash, comma and brace characters`() {
    val domainArray = selectArray("""SELECT ARRAY['a"b\c,d{e}', 'plain', NULL]::note_text[]""")

    val elements = domainArray.withElementType(connection, "text").mapElements { getString(2) }

    assertThat(elements.toList()).containsExactly("""a"b\c,d{e}""", "plain", null)
  }

  @Test
  fun `empty domain array yields an empty element array`() {
    val domainArray = selectArray("SELECT ARRAY[]::event_date[]")

    val elements = domainArray.withElementType(connection, "date").mapElements { getObject(2, LocalDate::class.java) }

    assertThat(elements.toList()).containsExactly()
  }

  private fun selectArray(sql: String): java.sql.Array = connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { resultSet ->
      resultSet.next()
      resultSet.getArray(1)
    }
  }

  companion object {
    @Container
    @JvmStatic
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:18-alpine")
      .withDatabaseName("test")
      .withUsername("test")
      .withPassword("test")
      .waitingFor(Wait.forListeningPort())
  }
}
