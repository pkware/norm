package example

import kotlin.Long
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT author.name, COUNT(*) AS book_count
 * FROM author
 * JOIN book ON book.author_id = author.id
 * WHERE author.id = ?
 * GROUP BY author.name
 * ```
 *
 * @property name Full name of the author. (`author.name`)
 * @property book_count (`COUNT(*)`)
 */
@JvmRecord
public data class CountBooksByAuthor(
  public val name: String,
  public val book_count: Long,
)
