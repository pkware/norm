package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetThreeConsecutiveEmbeds(
  public val author: Author,
  public val publisher: Publisher,
  public val reviewer: Reviewer,
) {
  public constructor(
    author_id: Int,
    author_name: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    reviewer_id: Int,
    reviewer_reviewer_name: String,
  ) : this(Author(
    author_id,
    author_name,
  ), Publisher(
    publisher_id,
    publisher_company_name,
    publisher_country,
  ), Reviewer(
    reviewer_id,
    reviewer_reviewer_name,
  ))
}
