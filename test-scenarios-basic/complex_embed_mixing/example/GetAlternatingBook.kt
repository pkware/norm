package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetAlternatingBook(
  public val title: String,
  public val author: Author,
  public val isbn: String,
  public val publisher: Publisher,
  public val published_year: Int,
) {
  public constructor(
    title: String,
    author_id: Int,
    author_name: String,
    isbn: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    published_year: Int,
  ) : this(title, Author(
    author_id,
    author_name,
  ), isbn, Publisher(
    publisher_id,
    publisher_company_name,
    publisher_country,
  ), published_year)
}
