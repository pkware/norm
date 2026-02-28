package example.typemappings

import com.example.CustomMood
import com.example.JsonData
import com.example.UserPreferences
import kotlin.Array
import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * Maps to the `users` table.
 */
@JvmRecord
public data class Users(
  public val id: Int,
  public val email: Email,
  public val age: PositiveInteger?,
  public val current_mood: CustomMood,
  public val metadata: JsonData,
  public val preferences: UserPreferences,
  public val past_moods: Array<CustomMood?>?,
  public val tag_list: Array<JsonData?>?,
)
