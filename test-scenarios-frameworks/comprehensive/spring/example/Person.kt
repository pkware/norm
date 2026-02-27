package example

import com.example.JsonData
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `person` table.
 */
@JvmRecord
public data class Person(
  public val id: Int,
  public val name: String,
  public val contact_email: EmailAddress,
  public val current_mood: Mood,
  public val bio: JsonData?,
)
