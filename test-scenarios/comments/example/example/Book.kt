package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * A published book in the catalog.
 *
 * Maps to the `book` table.
 *
 * @property id Unique identifier for the book.
 * @property title Title of the book.
 * @property author_id Foreign key to the author who wrote the book.
 * @property published_year Year the book was published. Null if unknown.
 */
@JvmRecord
public data class Book(
  public val id: Int,
  public val title: String,
  public val author_id: Int,
  public val published_year: Int?,
  public val isbn: String?,
)
