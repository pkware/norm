package norm.e2e.spring

import assertk.assertThat
import assertk.assertions.isEqualTo
import example.PostgresQueries
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * Demonstrates programmatic transaction patterns using Spring's [TransactionTemplate] API.
 *
 * These tests show how the same workflows are achieved using framework-native transaction management:
 *
 * - Scoped transactions wrapping multiple Norm queries
 * - Explicit rollback via `setRollbackOnly()`
 * - Nested transactions (savepoints) via `PROPAGATION_NESTED`
 */
@SpringBootTest
@ActiveProfiles("test")
class NormSpringTransactionTest {

  @Autowired
  lateinit var queries: PostgresQueries

  @Autowired
  lateinit var dataSource: DataSource

  lateinit var transactionTemplate: TransactionTemplate
  lateinit var nestedTransactionTemplate: TransactionTemplate

  @Autowired
  fun initTransactionTemplates(transactionManager: PlatformTransactionManager) {
    transactionTemplate = TransactionTemplate(transactionManager)
    nestedTransactionTemplate = TransactionTemplate(transactionManager).apply {
      propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
    }
  }

  @BeforeEach
  fun cleanDatabase() {
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("DELETE FROM book")
        statement.execute("DELETE FROM author")
      }
    }
  }

  /**
   * [TransactionTemplate.execute] scopes a transaction — all Norm queries inside the block
   * share the same connection and commit atomically on success.
   */
  @Test
  fun `scoped transaction wraps multiple Norm queries`() {
    transactionTemplate.execute {
      queries.addAuthor("Author 1", "a1@example.com")
      queries.addAuthor("Author 2", "a2@example.com")
    }

    assertThat(countAuthors()).isEqualTo(2L)
  }

  /**
   * `setRollbackOnly()` marks the transaction for rollback without throwing an exception.
   * When [TransactionTemplate.execute] returns, Spring sees the flag and rolls back instead
   * of committing.
   */
  @Test
  fun `scoped transaction rolls back explicitly`() {
    transactionTemplate.execute { status ->
      queries.addAuthor("Ghost", "ghost@example.com")
      status.setRollbackOnly()
    }

    assertThat(countAuthors()).isEqualTo(0L)
  }

  /**
   * `PROPAGATION_NESTED` creates a savepoint when a transaction is already active, or starts a
   * new transaction when none is. This is the key to context-independent code — a function using
   * `PROPAGATION_NESTED` doesn't need to know whether the caller already started a transaction.
   *
   * When the nested block rolls back (via `setRollbackOnly()` or an exception), only the savepoint
   * is rolled back. The outer transaction continues unaffected.
   */
  @Test
  fun `nested transaction rolls back without affecting outer transaction`() {
    transactionTemplate.execute {
      queries.addAuthor("Persisted", "p@example.com")

      runCatching {
        nestedTransactionTemplate.execute {
          queries.addAuthor("Rolled Back", "rb@example.com")
          error("Trigger savepoint rollback")
        }
      }
    }

    assertThat(countAuthorsByName("Persisted")).isEqualTo(1L)
    assertThat(countAuthorsByName("Rolled Back")).isEqualTo(0L)
  }

  /**
   * Multiple nested transactions can be composed independently. Each `PROPAGATION_NESTED` call
   * creates its own savepoint — some can commit (by returning normally) while others roll back.
   */
  @Test
  fun `multiple nested transactions with selective rollback`() {
    transactionTemplate.execute {
      queries.addAuthor("Author 1", "a1@example.com")

      // First nested transaction — commits (returns normally)
      nestedTransactionTemplate.execute {
        queries.addAuthor("Author 2", "a2@example.com")
      }

      // Second nested transaction — rolls back
      runCatching {
        nestedTransactionTemplate.execute {
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
