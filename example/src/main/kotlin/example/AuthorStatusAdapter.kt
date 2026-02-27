package example

import jakarta.inject.Singleton
import norm.ColumnAdapter

/**
 * Bridges the Postgres `author_status` enum (delivered as a `String` via JDBC) and the Kotlin
 * [AuthorStatus] enum.
 *
 * Annotated with `@Singleton` so that Micronaut automatically injects it into the generated
 * `PostgresQueries` constructor. User-configured adapters have no default value in the constructor,
 * so a bean must be present in the DI context.
 */
@Singleton
class AuthorStatusAdapter : ColumnAdapter<AuthorStatus, String> {
  override fun decode(databaseValue: String): AuthorStatus = when (databaseValue) {
    "active" -> AuthorStatus.ACTIVE
    "inactive" -> AuthorStatus.INACTIVE
    "suspended" -> AuthorStatus.SUSPENDED
    else -> error("Unknown author_status: $databaseValue")
  }

  override fun encode(value: AuthorStatus): String = when (value) {
    AuthorStatus.ACTIVE -> "active"
    AuthorStatus.INACTIVE -> "inactive"
    AuthorStatus.SUSPENDED -> "suspended"
  }
}
