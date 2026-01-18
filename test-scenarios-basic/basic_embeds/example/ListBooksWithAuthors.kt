package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class ListBooksWithAuthors(
  public val title: String,
  public val author: Author,
) {
  public constructor(
    title: String,
    author_id: Int,
    author_name: String,
    author_email: String,
  ) : this(title, Author(
    author_id,
    author_name,
    author_email,
  ))
}
