package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT book.title, book.published_year, author.name AS author_name
 * FROM book
 * JOIN author ON author.id = book.author_id
 * WHERE book.id = ?
 * ```
 *
 * @property title Title of the book. (`book.title`)
 * @property published_year Year the book was published. Null if unknown. (`book.published_year`)
 * @property author_name Full name of the author. (`author.name`)
 */
@JvmRecord
public data class GetBookWithAuthorName(
  public val title: String,
  public val published_year: Int?,
  public val author_name: String,
)
