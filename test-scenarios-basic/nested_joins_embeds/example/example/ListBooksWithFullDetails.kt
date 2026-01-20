package example

import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class ListBooksWithFullDetails(
  public val id: Int,
  public val author: Author,
  public val title: String,
  public val isbn: String,
  public val publisher: Publisher,
  public val published_year: Int,
  public val in_stock: Boolean,
) {
  public constructor(
    id: Int,
    author_id: Int,
    author_name: String,
    author_email: String,
    author_bio: String?,
    title: String,
    isbn: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    published_year: Int,
    in_stock: Boolean,
  ) : this(id, Author(
    author_id,
    author_name,
    author_email,
    author_bio,
  ), title, isbn, Publisher(
    publisher_id,
    publisher_company_name,
    publisher_country,
  ), published_year, in_stock)
}
