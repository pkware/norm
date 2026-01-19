package example

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
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
  @Throws(SQLException::class)
  override fun <T : Any> getAuthor(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    author_email: String,
  ) -> T): T {
    val sql = "SELECT author.id, author.name, author.email FROM author WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  private fun <T : Any, R> listBooksWithAuthors(mapper: (
    title: String,
    author_id: Int,
    author_name: String,
    author_email: String,
  ) -> T, block: (String, ResultSet.() -> T) -> R): R {
    val sql = """
        |SELECT b.title, author.id, author.name, author.email
        |FROM book b
        |JOIN author ON b.author_id = author.id
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getInt(2),
        getString(3),
        getString(4),
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> listBooksWithAuthors(mapper: (
    title: String,
    author_id: Int,
    author_name: String,
    author_email: String,
  ) -> T): Many<T> = listBooksWithAuthors(mapper, driver::queryMany)

  override fun <T : Any> listBooksWithAuthorsDynamically(mapper: (
    title: String,
    author_id: Int,
    author_name: String,
    author_email: String,
  ) -> T): Query<T> = listBooksWithAuthors(mapper, driver::dynamic)

  @Throws(SQLException::class)
  override fun <T : Any> getAuthorWithBookTitle(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    author_email: String,
    title: String,
  ) -> T): T {
    val sql = """
        |SELECT author.id, author.name, author.email, b.title
        |FROM book b
        |JOIN author ON b.author_id = author.id
        |WHERE b.id = ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
        getString(4),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }
}
