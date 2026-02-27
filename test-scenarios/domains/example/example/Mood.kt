package example

import kotlin.String

/**
 * Represents the emotional state of a person. Used in the users table to track current and historical moods.
 *
 * @property databaseValue The representation of this enum in Postgres.
 */
public enum class Mood(
  public val databaseValue: String,
) {
  HAPPY("happy"),
  SAD("sad"),
  ANGRY("angry"),
  ;

  public companion object {
    /**
     * @returns the enum constant matching [value], or `null` if no match exists.
     */
    public fun fromDatabaseValue(`value`: String): Mood? = entries.firstOrNull { it.databaseValue == value }
  }
}
