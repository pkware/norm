package example.typemappings

import com.example.UserPreferences
import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * INSERT INTO users (email, age, current_mood, metadata, preferences)
 * SELECT email, age, current_mood, metadata, preferences FROM users WHERE id = ?
 * RETURNING id, preferences AS duplicated_preferences
 * ```
 *
 * @property id (`users.id`)
 * @property duplicated_preferences (`users.preferences`)
 */
@JvmRecord
public data class DuplicateUserReturningAliasedPreferences(
  public val id: Int,
  public val duplicated_preferences: UserPreferences,
)
