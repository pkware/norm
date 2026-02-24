package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT title, published_year FROM book WHERE id = ?
 * ```
 *
 * @property title Title of the book. (`book.title`)
 * @property published_year Year the book was published. Null if unknown. (`book.published_year`)
 */
@JvmRecord
public data class GetBookTitleAndYear(
  public val title: String,
  public val published_year: Int?,
)
