package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetComplexBook(
  public val id: Int,
  public val title: String,
  public val author: Author,
  public val isbn: String,
  public val published_year: Int,
) {
  public constructor(
    id: Int,
    title: String,
    author_id: Int,
    author_name: String,
    isbn: String,
    published_year: Int,
  ) : this(id, title, Author(
    author_id,
    author_name,
  ), isbn, published_year)
}
