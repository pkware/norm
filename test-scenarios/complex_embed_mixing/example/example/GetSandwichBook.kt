package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetSandwichBook(
  public val title: String,
  public val isbn: String,
  public val publisher: Publisher,
  public val page_count: Int,
  public val published_year: Int,
) {
  public constructor(
    title: String,
    isbn: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    page_count: Int,
    published_year: Int,
  ) : this(title, isbn, Publisher(
    publisher_id,
    publisher_company_name,
    publisher_country,
  ), page_count, published_year)
}
