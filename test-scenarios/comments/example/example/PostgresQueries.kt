package example

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.ConnectionProvider
import norm.NormDriver
import norm.combineExecBatchResults

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  @Throws(SQLException::class)
  override fun <T : Any> getAuthorById(id: Int, mapper: (
    id: Int,
    name: String,
    bio: String?,
  ) -> T): T {
    val sql = "SELECT * FROM author WHERE id = ?"
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

  @Throws(SQLException::class)
  override fun <T : Any> getBookTitleAndYear(id: Int, mapper: (title: String, published_year: Int?) -> T): T {
    val sql = "SELECT title, published_year FROM book WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getInt(2).takeUnless { wasNull() },
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  /**
   * @param name Full name of the author.
   * @param bio Short biography. Null if not provided.
   */
  @Throws(SQLException::class)
  override fun addAuthor(name: String, bio: String?) {
    val sql = "INSERT INTO author (name, bio) VALUES (?, ?)"
    driver.execute(sql) {
      setString(1, name)
      setString(2, bio)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> addAuthor(
    stream: Iterable<Input>,
    name: Input.() -> String,
    bio: Input.() -> String?,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO author (name, bio) VALUES (?, ?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.name())
        setString(2, entry.bio())
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          executeBatch()
          batchCount = 0
        }
      }
      if (batchCount > 0) {
        results.add(executeBatch())
        totalCount += batchCount
      }
      combineExecBatchResults(results, totalCount, batchSize)
    }
  }

  @Throws(SQLException::class)
  override fun <T : Any> getBookWithAuthorName(id: Int, mapper: (
    title: String,
    published_year: Int?,
    author_name: String,
  ) -> T): T {
    val sql = """
        |SELECT book.title, book.published_year, author.name AS author_name
        |FROM book
        |JOIN author ON author.id = book.author_id
        |WHERE book.id = ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getInt(2).takeUnless { wasNull() },
        getString(3),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun <T : Any> countBooksByAuthor(id: Int, mapper: (name: String, book_count: Long) -> T): T {
    val sql = """
        |SELECT author.name, COUNT(*) AS book_count
        |FROM author
        |JOIN book ON book.author_id = author.id
        |WHERE author.id = ?
        |GROUP BY author.name
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getLong(2),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }
}
