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
