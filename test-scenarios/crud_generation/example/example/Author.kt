package example

import java.time.OffsetDateTime
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `author` table.
 */
@JvmRecord
public data class Author(
  public val id: Int,
  public val name: String,
  public val bio: String?,
  public val created_at: OffsetDateTime,
)
