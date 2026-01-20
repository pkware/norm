package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetAuthorWithBookTitle(
  public val author: Author,
  public val title: String,
) {
  public constructor(
    author_id: Int,
    author_name: String,
    author_email: String,
    title: String,
  ) : this(Author(
    author_id,
    author_name,
    author_email,
  ), title)
}
