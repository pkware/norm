package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetBookWithReviewCount(
  public val author: Author,
  public val title: String,
  public val review_count: Int,
) {
  public constructor(
    author_id: Int,
    author_name: String,
    author_email: String,
    author_bio: String?,
    title: String,
    review_count: Int,
  ) : this(Author(
    author_id,
    author_name,
    author_email,
    author_bio,
  ), title, review_count)
}
