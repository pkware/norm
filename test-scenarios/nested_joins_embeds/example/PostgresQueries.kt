package example

import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.jvm.Throws
import norm.Many
import norm.NormDriver
import norm.Query
import norm.RealTransacter

public class PostgresQueries(
  driver: NormDriver,
) : RealTransacter(driver),
    Queries {
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
  override fun <T : Any> getBookDetails(id: Int, mapper: (
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
  ) -> T): T {
    val sql = """
        |SELECT
        |  b.title,
        |  b.isbn,
        |  author.id, author.name, author.email, author.bio,
        |  b.published_year,
        |  publisher.id, publisher.company_name, publisher.country,
        |  b.page_count,
        |  b.price
        |FROM book b
        |JOIN author ON b.author_id = author.id
        |JOIN publisher ON b.publisher_id = publisher.id
        |WHERE b.id = ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
        getInt(3),
        getString(4),
        getString(5),
        getString(6),
        getInt(7),
        getInt(8),
        getString(9),
        getString(10),
        getInt(11),
        getBigDecimal(12),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  private fun <T : Any, R> listBooksWithFullDetails(mapper: (
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
  ) -> T, block: (String, ResultSet.() -> T) -> R): R {
    val sql = """
        |SELECT
        |  b.id,
        |  author.id, author.name, author.email, author.bio,
        |  b.title,
        |  b.isbn,
        |  publisher.id, publisher.company_name, publisher.country,
        |  b.published_year,
        |  b.in_stock
        |FROM book b
        |JOIN author ON b.author_id = author.id
        |JOIN publisher ON b.publisher_id = publisher.id
        |ORDER BY b.published_year DESC
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getInt(2),
        getString(3),
        getString(4),
        getString(5),
        getString(6),
        getString(7),
        getInt(8),
        getString(9),
        getString(10),
        getInt(11),
        getBoolean(12),
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> listBooksWithFullDetails(mapper: (
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
  ) -> T): Many<T> = listBooksWithFullDetails(mapper, driver::queryMany)

  override fun <T : Any> listBooksWithFullDetailsDynamically(mapper: (
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
  ) -> T): Query<T> = listBooksWithFullDetails(mapper, driver::dynamic)

  /**
   * Mix of embeds and aggregates
   */
  @Throws(SQLException::class)
  override fun <T : Any> getBookWithReviewCount(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    author_email: String,
    author_bio: String?,
    title: String,
    review_count: Int,
  ) -> T): T {
    val sql = """
        |SELECT
        |  author.id, author.name, author.email, author.bio,
        |  b.title,
        |  COUNT(r.id)::int AS review_count
        |FROM book b
        |JOIN author ON b.author_id = author.id
        |LEFT JOIN review r ON r.book_id = b.id
        |WHERE b.id = ?
        |GROUP BY author.id, author.name, author.email, author.bio, b.title
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
        getString(4),
        getString(5),
        getInt(6),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }
}
