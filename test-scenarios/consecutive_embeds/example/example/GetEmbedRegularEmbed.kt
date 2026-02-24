package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetEmbedRegularEmbed(
  public val author: Author,
  public val title: String,
  public val publisher: Publisher,
) {
  public constructor(
    author_id: Int,
    author_name: String,
    title: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
  ) : this(Author(
    author_id,
    author_name,
  ), title, Publisher(
    publisher_id,
    publisher_company_name,
    publisher_country,
  ))
}
