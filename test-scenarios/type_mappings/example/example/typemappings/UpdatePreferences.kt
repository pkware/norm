package example.typemappings

import com.example.UserPreferences
import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * UPDATE users SET preferences = ? WHERE id = ?
 * RETURNING id, preferences AS old_preferences
 * ```
 *
 * @property id (`users.id`)
 * @property old_preferences (`users.preferences`)
 */
@JvmRecord
public data class UpdatePreferences(
  public val id: Int,
  public val old_preferences: UserPreferences,
)
