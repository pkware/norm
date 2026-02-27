package norm.e2e.micronaut

import assertk.assertThat
import assertk.assertions.isEqualTo
import example.PostgresQueries
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionDefinition
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.sql.Connection
import javax.sql.DataSource

/**
 * Demonstrates programmatic transaction patterns using Micronaut's [TransactionOperations] API.
 *
 * These tests show how the same workflows are achieved using framework-native transaction management:
 *
 * - Scoped transactions wrapping multiple Norm queries
 * - Explicit rollback via `setRollbackOnly()`
 * - Nested transactions (savepoints) via `NESTED` propagation
 *
 * The nested transaction tests are currently disabled due to a Micronaut Data bug where `NESTED`
 * propagation does not create savepoints. See https://github.com/micronaut-projects/micronaut-data/issues/3334
 */
@MicronautTest
class NormMicronautTransactionTest {

  @Inject
  lateinit var queries: PostgresQueries

  @Inject
  lateinit var transactionOperations: TransactionOperations<Connection>

  @Inject
  lateinit var dataSource: DataSource

  private val nested = TransactionDefinition.of(TransactionDefinition.Propagation.NESTED)
  private val requiresNew = TransactionDefinition.of(TransactionDefinition.Propagation.REQUIRES_NEW)

  @BeforeEach
  fun cleanDatabase() {
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("DELETE FROM person")
        statement.execute("DELETE FROM book")
        statement.execute("DELETE FROM author")
      }
    }
  }

  /**
   * [TransactionOperations.executeWrite] scopes a transaction — all Norm queries inside the block
   * share the same connection and commit atomically on success.
   */
  @Test
  fun `scoped transaction wraps multiple Norm queries`() {
    transactionOperations.executeWrite {
      queries.addAuthor("Author 1", "a1@example.com")
      queries.addAuthor("Author 2", "a2@example.com")
    }

    assertThat(countAuthors()).isEqualTo(2L)
  }

  /**
   * `setRollbackOnly()` marks the transaction for rollback without throwing an exception.
   * When the block returns, the framework sees the flag and rolls back instead of committing.
   */
  @Test
  fun `scoped transaction rolls back explicitly`() {
    transactionOperations.execute(requiresNew) { status ->
      queries.addAuthor("Ghost", "ghost@example.com")
      status.setRollbackOnly()
    }

    assertThat(countAuthors()).isEqualTo(0L)
  }

  /**
   * `NESTED` propagation creates a savepoint when a transaction is already active, or starts a
   * new transaction when none is. This is the key to context-independent code — a function using
   * `NESTED` doesn't need to know whether the caller already started a transaction.
   *
   * When the nested block fails, only the savepoint is rolled back. The outer transaction
   * continues unaffected.
   */
  @Disabled("Blocked by https://github.com/micronaut-projects/micronaut-data/issues/3334")
  @Test
  fun `nested transaction rolls back without affecting outer transaction`() {
    transactionOperations.executeWrite {
      queries.addAuthor("Persisted", "p@example.com")

      runCatching {
        transactionOperations.execute(nested) {
          queries.addAuthor("Rolled Back", "rb@example.com")
          error("Trigger savepoint rollback")
        }
      }
    }

    assertThat(countAuthorsByName("Persisted")).isEqualTo(1L)
    assertThat(countAuthorsByName("Rolled Back")).isEqualTo(0L)
  }

  /**
   * Multiple nested transactions can be composed independently. Each `NESTED` call creates its
   * own savepoint — some can commit (by returning normally) while others roll back.
   */
  @Disabled("Blocked by https://github.com/micronaut-projects/micronaut-data/issues/3334")
  @Test
  fun `multiple nested transactions with selective rollback`() {
    transactionOperations.executeWrite {
      queries.addAuthor("Author 1", "a1@example.com")

      // First nested transaction — commits (returns normally)
      transactionOperations.execute(nested) {
        queries.addAuthor("Author 2", "a2@example.com")
      }

      // Second nested transaction — rolls back
      runCatching {
        transactionOperations.execute(nested) {
          queries.addAuthor("Author 3", "a3@example.com")
          error("Trigger savepoint rollback")
        }
      }
    }

    assertThat(countAuthorsByName("Author 1")).isEqualTo(1L)
    assertThat(countAuthorsByName("Author 2")).isEqualTo(1L)
    assertThat(countAuthorsByName("Author 3")).isEqualTo(0L)
  }

  private fun countAuthors(): Long = dataSource.connection.use { connection ->
    connection.prepareStatement("SELECT COUNT(*) FROM author").use { statement ->
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getLong(1)
      }
    }
  }

  private fun countAuthorsByName(name: String): Long = dataSource.connection.use { connection ->
    connection.prepareStatement("SELECT COUNT(*) FROM author WHERE name = ?").use { statement ->
      statement.setString(1, name)
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getLong(1)
      }
    }
  }
}
