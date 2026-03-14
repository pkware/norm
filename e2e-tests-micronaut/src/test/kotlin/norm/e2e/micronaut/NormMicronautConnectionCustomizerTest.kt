package norm.e2e.micronaut

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import example.PostgresQueries
import io.micronaut.data.connection.ConnectionOperations
import io.micronaut.data.connection.support.ConnectionCustomizer
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import norm.ConnectionProvider
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * Tests that Micronaut's [ConnectionCustomizer] mechanism
 * works correctly when Norm queries execute within an existing connection context.
 *
 * The bug: `MicronautConnectionProvider.withConnection` used
 * [ConnectionOperations.findConnectionStatus]
 * to detect an existing connection and then called the block directly, bypassing
 * [ConnectionOperations.execute]. Since `execute()` is
 * the call that applies [ConnectionCustomizer]s, customizers never fired for Norm queries running inside an existing
 * transaction.
 *
 * This matters for features like multi-tenant Row-Level Security, where a [ConnectionCustomizer] sets a PostgreSQL
 * session variable (`app.current_tenant_id`) on each operation.
 *
 * Uses default `transactional = true` so the test framework wraps each test in a transaction, providing the
 * "existing connection" context naturally.
 */
@MicronautTest
class NormMicronautConnectionCustomizerTest {

  @Inject
  lateinit var queries: PostgresQueries

  @Inject
  lateinit var connectionProvider: ConnectionProvider

  @Inject
  lateinit var connectionOperations: ConnectionOperations<Connection>

  @Inject
  lateinit var customizer: InvocationCountingCustomizer

  @Nested
  inner class WithConnection {

    @Test
    fun `connection customizers fire for Norm queries within existing connection`() {
      // The test framework's transaction creates an existing connection context.
      // When queries.addAuthor() calls withConnection(), it should go through
      // connectionOperations.execute() — which applies ConnectionCustomizers.
      val countBefore = customizer.count.get()
      queries.addAuthor("Customizer Test", "ct@example.com")
      assertThat(customizer.count.get()).isGreaterThan(countBefore)
    }

    @Test
    fun `withConnection returns the transaction-bound connection`() {
      val transactionPid = connectionOperations.findConnectionStatus().get().connection
        .prepareStatement("SELECT pg_backend_pid()").use { statement ->
          statement.executeQuery().use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
          }
        }

      val normPid = connectionProvider.withConnection { connection ->
        connection.prepareStatement("SELECT pg_backend_pid()").use { statement ->
          statement.executeQuery().use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
          }
        }
      }

      assertThat(normPid).isEqualTo(transactionPid)
    }
  }

  @Nested
  inner class BorrowConnection {

    @Test
    fun `borrowed connection within transaction is the transaction-bound connection`() {
      val transactionPid = connectionOperations.findConnectionStatus().get().connection
        .prepareStatement("SELECT pg_backend_pid()").use { statement ->
          statement.executeQuery().use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
          }
        }

      val borrowed = connectionProvider.borrowConnection()
      val borrowedPid = borrowed.connection.prepareStatement("SELECT pg_backend_pid()").use { statement ->
        statement.executeQuery().use { resultSet ->
          resultSet.next()
          resultSet.getInt(1)
        }
      }

      assertThat(borrowedPid).isEqualTo(transactionPid)
    }
  }
}
