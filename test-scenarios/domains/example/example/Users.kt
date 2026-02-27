package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `users` table.
 */
@JvmRecord
public data class Users(
  public val id: Int,
  public val email: String,
  public val age: Int?,
  public val zip_code: String?,
  public val current_mood: Mood,
  public val previous_mood: Mood?,
)
