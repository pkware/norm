package norm.e2e.micronaut

import com.example.JsonData
import example.EmailAddress
import example.Mood
import example.PostgresQueries
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

/**
 * Service that executes Norm queries with adapted types inside a `@Transactional` scope.
 *
 * Used by integration tests to verify that adapter-encoded values participate in
 * Micronaut-managed transactions (i.e., rollback correctly discards the encoded writes).
 */
@Singleton
open class PersonService(private val queries: PostgresQueries) {

  /**
   * Creates a person inside a transaction, then throws to trigger rollback.
   *
   * If the transaction integration works correctly, the insert will be rolled back
   * and the person will not be persisted.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  open fun createPersonThenFail(name: String, contactEmail: EmailAddress, currentMood: Mood, bio: JsonData?) {
    queries.createPerson(name, contactEmail, currentMood, bio)
    error("Deliberate failure to trigger rollback")
  }
}
