package norm.e2e.micronaut

import example.PostgresQueries
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

/**
 * Service that executes Norm queries inside a `@Transactional` scope.
 *
 * Used by integration tests to verify that Norm queries participate in Micronaut-managed transactions.
 */
@Singleton
open class AuthorService(private val queries: PostgresQueries) {

  /**
   * Inserts an author inside a transaction, then throws to trigger rollback.
   *
   * If the transaction integration works correctly, the insert will be rolled back
   * and the author will not be persisted.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  open fun addAuthorThenFail(name: String, email: String?) {
    queries.addAuthor(name, email)
    error("Deliberate failure to trigger rollback")
  }
}
