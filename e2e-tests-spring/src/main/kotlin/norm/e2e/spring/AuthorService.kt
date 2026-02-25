package norm.e2e.spring

import example.PostgresQueries
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Service that executes Norm queries inside a `@Transactional` scope.
 *
 * Used by integration tests to verify that Norm queries participate in Spring-managed transactions.
 */
@Service
class AuthorService(private val queries: PostgresQueries) {

  /**
   * Inserts an author inside a new transaction, then throws to trigger rollback.
   *
   * [Propagation.REQUIRES_NEW] ensures this runs in its own transaction, independent of any
   * caller's transaction (e.g., the test's wrapping transaction). When [error] throws,
   * only this transaction rolls back.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun addAuthorThenFail(name: String, email: String?) {
    queries.addAuthor(name, email)
    error("Deliberate failure to trigger rollback")
  }
}
