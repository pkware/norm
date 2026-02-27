package example

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
  public val zip_code: UsPostalCode?,
  public val current_mood: Mood,
  public val previous_mood: Mood?,
)
