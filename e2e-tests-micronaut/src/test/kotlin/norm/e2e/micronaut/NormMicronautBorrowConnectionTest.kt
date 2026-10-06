package norm.e2e.micronaut

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import example.PostgresQueries
import io.micronaut.data.connection.ConnectionOperations
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import norm.ConnectionProvider
import norm.NormDriver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * Tests `MicronautConnectionProvider.borrowConnection` for a caller streaming a query outside any transaction or
 * connection scope.
 *
 * Uses `transactional = false` so no test-framework transaction wraps the calls. Rows inserted by a test are
 * removed in [deleteInsertedRows].
 */
@MicronautTest(transactional = false)
class NormMicronautBorrowConnectionTest {

  @Inject
  lateinit var queries: PostgresQueries

  @Inject
  lateinit var connectionProvider: ConnectionProvider

  @Inject
  lateinit var connectionOperations: ConnectionOperations<Connection>

  @Inject
  lateinit var customizer: InvocationCountingCustomizer

  @AfterEach
  fun deleteInsertedRows() {
    connectionProvider.withConnection { connection ->
      connection.prepareStatement("DELETE FROM author WHERE email LIKE '%@borrow.example'").use { it.executeUpdate() }
    }
  }

  @Nested
  inner class Streaming {

    @Test
    fun `stream outside a scope yields every row`() {
      queries.addAuthor("Borrow One", "one@borrow.example")
      queries.addAuthor("Borrow Two", "two@borrow.example")
      queries.addAuthor("Borrow Three", "three@borrow.example")

      val names = NormDriver(connectionProvider).queryMany(
        "SELECT name FROM author WHERE email LIKE '%@borrow.example'",
        { getString(1) },
      ).stream().use { stream -> stream.toList() }

      assertThat(names).containsExactlyInAnyOrder("Borrow One", "Borrow Two", "Borrow Three")
    }
  }

  @Nested
  inner class Lifecycle {

    @Test
    fun `borrowed connection is open until released and closed afterwards`() {
      val borrowed = connectionProvider.borrowConnection()

      assertThat(borrowed.connection.isClosed).isFalse()

      borrowed.close()

      assertThat(borrowed.connection.isClosed).isTrue()
    }

    @Test
    fun `borrowed connection is usable for queries until released`() {
      connectionProvider.borrowConnection().use { borrowed ->
        val one = borrowed.connection.prepareStatement("SELECT 1").use { statement ->
          statement.executeQuery().use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
          }
        }

        assertThat(one).isEqualTo(1)
      }
    }

    @Test
    fun `no connection scope remains after the borrowed connection is released`() {
      connectionProvider.borrowConnection().close()

      assertThat(connectionOperations.findConnectionStatus().isPresent).isFalse()
    }
  }

  @Nested
  inner class Customizers {

    @Test
    fun `borrowing outside a scope does not invoke connection customizers`() {
      val countBefore = customizer.count.get()

      connectionProvider.borrowConnection().close()

      assertThat(customizer.count.get()).isEqualTo(countBefore)
    }
  }
}
