package norm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JdbcQueriesTest {

  @Nested
  inner class TransactionWithoutResult {
    @Test
    fun `can be committed`() {
    }

    @Test
    fun `can be rolled back`() {
    }

    @Test
    fun `opening a transaction when one is already active makes use of savepoints`() {
      val queries: JdbcQueries
      queries.transaction { outer ->
        // Assume that we're now many calls deeper and someone opens a transaction
        queries.transaction { inner ->
          // If it's the same instance, then the inner will commit/rollback the outer, which is wrong
          assertThat(inner).isSameInstanceAs(outer)
        }
        TODO("Verify that the outer transaction is still open")
      }
    }

    @Test
    fun `connection auto-commit is restored`() {
      TODO("Set autocommit to false")
      TODO("Do a transaction")
      TODO("Verify connection autocommit is false")

      TODO("Set autocommit to true")
      TODO("Do a transaction")
      TODO("Verify connection autocommit is true")
    }
  }

  @Nested
  inner class TransactionWithResult {
    @Test
    fun `can be committed`() {
    }

    @Test
    fun `can be rolled back`() {
    }

    @Test
    fun `opening a transaction with result when one is already active makes use of savepoints`() {
      val queries: JdbcQueries
      queries.transactionWithResult { outer ->
        // Assume that we're now many calls deeper and someone opens a transaction
        queries.transactionWithResult { inner ->
          // If it's the same instance, then the inner will commit/rollback the outer, which is wrong
          assertThat(inner).isSameInstanceAs(outer)
        }
        TODO("Verify that the outer transaction is still open")
      }
    }

    @Test
    fun `connection auto-commit is restored`() {
      TODO("Set autocommit to false")
      TODO("Do a transaction")
      TODO("Verify connection autocommit is false")

      TODO("Set autocommit to true")
      TODO("Do a transaction")
      TODO("Verify connection autocommit is true")
    }
  }

  @Test
  fun `queries outside a transaction are auto committed`() {}
}
