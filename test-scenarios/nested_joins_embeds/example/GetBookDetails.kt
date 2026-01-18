package example

import java.math.BigDecimal
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetBookDetails(
  public val title: String,
  public val isbn: String,
  public val author: Author,
  public val published_year: Int,
  public val publisher: Publisher,
  public val page_count: Int,
  public val price: BigDecimal,
) {
  public constructor(
    title: String,
    isbn: String,
    author_id: Int,
    author_name: String,
    author_email: String,
    author_bio: String?,
    published_year: Int,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    page_count: Int,
    price: BigDecimal,
  ) : this(title, isbn, Author(
    author_id,
    author_name,
    author_email,
    author_bio,
  ), published_year, Publisher(
    publisher_id,
    publisher_company_name,
    publisher_country,
  ), page_count, price)
}
