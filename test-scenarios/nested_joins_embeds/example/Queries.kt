package example

import java.math.BigDecimal
import java.sql.SQLException
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.jvm.Throws
import norm.Many
import norm.Query
import norm.Transacter

public interface Queries : Transacter {
  /**
   * Real-world complex query: 10+ columns with multiple embeds
   * This simulates a realistic join-heavy query with mixed regular and embed columns
   * Expected indices:
   * 1: b.title
   * 2: b.isbn
   * 3-6: author (id, name, email, bio) - 4 columns
   * 7: b.published_year
   * 8-10: publisher (id, company_name, country) - 3 columns
   * 11: b.page_count
   * 12: b.price
   * BUG HYPOTHESIS: Catastrophic failures in middle columns - published_year, page_count, price
   * likely to have wrong indices after multiple embeds
   */
  @Throws(SQLException::class)
  public fun <T : Any> getBookDetails(id: Int, mapper: (
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
  ) -> T): T

  /**
   * Real-world complex query: 10+ columns with multiple embeds
   * This simulates a realistic join-heavy query with mixed regular and embed columns
   * Expected indices:
   * 1: b.title
   * 2: b.isbn
   * 3-6: author (id, name, email, bio) - 4 columns
   * 7: b.published_year
   * 8-10: publisher (id, company_name, country) - 3 columns
   * 11: b.page_count
   * 12: b.price
   * BUG HYPOTHESIS: Catastrophic failures in middle columns - published_year, page_count, price
   * likely to have wrong indices after multiple embeds
   */
  @Throws(SQLException::class)
  public fun getBookDetails(id: Int): GetBookDetails = getBookDetails(id, ::GetBookDetails)

  /**
   * Similar complex pattern for :many queries
   */
  public fun <T : Any> listBooksWithFullDetails(mapper: (
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
  ) -> T): Many<T>

  /**
   * Similar complex pattern for :many queries
   */
  public fun listBooksWithFullDetails(): Many<ListBooksWithFullDetails> = listBooksWithFullDetails(::ListBooksWithFullDetails)

  public fun <T : Any> listBooksWithFullDetailsDynamically(mapper: (
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
  ) -> T): Query<T>

  public fun listBooksWithFullDetailsDynamically(): Query<ListBooksWithFullDetails> = listBooksWithFullDetailsDynamically(::ListBooksWithFullDetails)

  /**
   * Mix of embeds and aggregates
   */
  @Throws(SQLException::class)
  public fun <T : Any> getBookWithReviewCount(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    author_email: String,
    author_bio: String?,
    title: String,
    review_count: Int,
  ) -> T): T

  /**
   * Mix of embeds and aggregates
   */
  @Throws(SQLException::class)
  public fun getBookWithReviewCount(id: Int): GetBookWithReviewCount = getBookWithReviewCount(id, ::GetBookWithReviewCount)
}
