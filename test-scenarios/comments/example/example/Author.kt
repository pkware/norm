package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * A person who writes books.
 *
 * Maps to the `author` table.
 *
 * @property id Unique identifier for the author.
 * @property name Full name of the author.
 * @property bio Short biography. Null if not provided.
 */
@JvmRecord
public data class Author(
  public val id: Int,
  public val name: String,
  public val bio: String?,
)
