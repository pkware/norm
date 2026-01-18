package example

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.String
import kotlin.jvm.Throws
import norm.NormDriver
import norm.RealTransacter

public class PostgresQueries(
  driver: NormDriver,
) : RealTransacter(driver),
    Queries {
  /**
   * THE CRITICAL TEST: regular, embed, regular pattern (TODO at TypeRepository.kt:89-90)
   * Expected indices:
   * 1: b.id
   * 2: b.title
   * 3-4: author (id, name)
   * 5: b.isbn
   * 6: b.published_year
   * BUG HYPOTHESIS: After the embed, isbn and published_year may use wrong indices
   */
  @Throws(SQLException::class)
  override fun <T : Any> getComplexBook(id: Int, mapper: (
    id: Int,
    title: String,
    author_id: Int,
    author_name: String,
    isbn: String,
    published_year: Int,
  ) -> T): T {
    val sql = """
        |SELECT
        |  b.id,
        |  b.title,
        |  author.id, author.name,
        |  b.isbn,
        |  b.published_year
        |FROM book b
        |JOIN author ON b.author_id = author.id
        |WHERE b.id = ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3),
        getString(4),
        getString(5),
        getInt(6),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  /**
   * Sandwich pattern: regular columns on both sides of 3-column embed
   * Expected indices:
   * 1: b.title
   * 2: b.isbn
   * 3-5: publisher (id, company_name, country)
   * 6: b.page_count
   * 7: b.published_year
   */
  @Throws(SQLException::class)
  override fun <T : Any> getSandwichBook(id: Int, mapper: (
    title: String,
    isbn: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    page_count: Int,
    published_year: Int,
  ) -> T): T {
    val sql = """
        |SELECT
        |  b.title,
        |  b.isbn,
        |  publisher.id, publisher.company_name, publisher.country,
        |  b.page_count,
        |  b.published_year
        |FROM book b
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
        getInt(6),
        getInt(7),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  /**
   * Multiple embeds with regular columns between
   * Expected indices:
   * 1: b.title
   * 2-3: author (id, name)
   * 4: b.isbn
   * 5-7: publisher (id, company_name, country)
   * 8: b.published_year
   */
  @Throws(SQLException::class)
  override fun <T : Any> getAlternatingBook(id: Int, mapper: (
    title: String,
    author_id: Int,
    author_name: String,
    isbn: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    published_year: Int,
  ) -> T): T {
    val sql = """
        |SELECT
        |  b.title,
        |  author.id, author.name,
        |  b.isbn,
        |  publisher.id, publisher.company_name, publisher.country,
        |  b.published_year
        |FROM book b
        |JOIN author ON b.author_id = author.id
        |JOIN publisher ON b.publisher_id = publisher.id
        |WHERE b.id = ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getInt(2),
        getString(3),
        getString(4),
        getInt(5),
        getString(6),
        getString(7),
        getInt(8),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }
}
