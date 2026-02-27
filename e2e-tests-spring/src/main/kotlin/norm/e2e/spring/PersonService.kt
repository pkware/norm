package norm.e2e.spring

import com.example.JsonData
import example.EmailAddress
import example.Mood
import example.PostgresQueries
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Service that executes Norm queries with adapted types inside a `@Transactional` scope.
 *
 * Used by integration tests to verify that adapter-encoded values participate in
 * Spring-managed transactions (i.e., rollback correctly discards the encoded writes).
 */
@Service
class PersonService(private val queries: PostgresQueries) {

  /**
   * Creates a person inside a new transaction, then throws to trigger rollback.
   *
   * [Propagation.REQUIRES_NEW] ensures this runs in its own transaction, independent of any
   * caller's transaction (e.g., the test's wrapping transaction). When [error] throws,
   * only this transaction rolls back.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun createPersonThenFail(name: String, contactEmail: EmailAddress, currentMood: Mood, bio: JsonData?) {
    queries.createPerson(name, contactEmail, currentMood, bio)
    error("Deliberate failure to trigger rollback")
  }
}
