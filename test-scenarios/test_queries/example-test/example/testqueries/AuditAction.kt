package example.testqueries

import kotlin.String

/**
 * @property databaseValue The representation of this enum in Postgres.
 */
public enum class AuditAction(
  public val databaseValue: String,
) {
  CREATED("created"),
  UPDATED("updated"),
  DELETED("deleted"),
  ;

  public companion object {
    /**
     * @returns the enum constant matching [value], or `null` if no match exists.
     */
    public fun fromDatabaseValue(`value`: String): AuditAction? = entries.firstOrNull { it.databaseValue == value }
  }
}
