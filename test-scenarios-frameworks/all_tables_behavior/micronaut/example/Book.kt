package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `book` table.
 */
@JvmRecord
public data class Book(
  public val id: Int,
  public val title: String,
  public val author_id: Int,
)
