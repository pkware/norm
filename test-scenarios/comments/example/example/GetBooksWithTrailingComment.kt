package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT id, title FROM book
 * -- only rows with a title
 * ```
 *
 * @property id Unique identifier for the book. (`book.id`)
 * @property title Title of the book. (`book.title`)
 */
@JvmRecord
public data class GetBooksWithTrailingComment(
  public val id: Int,
  public val title: String,
)
