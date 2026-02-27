package example

/**
 * Idiomatic Kotlin representation of the Postgres `author_status` enum.
 *
 * Norm auto-generates a `AuthorStatus` enum by default, but the generated constants would use
 * lowercase names matching the database values (`active`, `inactive`, `suspended`). This custom
 * type uses standard Kotlin SCREAMING_SNAKE_CASE naming, which is why we configure a type
 * mapping in `build.gradle.kts`.
 */
enum class AuthorStatus {
  ACTIVE,
  INACTIVE,
  SUSPENDED,
}
