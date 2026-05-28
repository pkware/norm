package example

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.String
import kotlin.Unit
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.ConnectionProvider
import norm.Many
import norm.ManyProcessor
import norm.NormDriver
import norm.RealTransactable
import norm.combineExecBatchResults

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : RealTransactable(connectionProvider),
    Queries {
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
    name: (Input) -> String,
    bio: (Input) -> String?,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO author (name, bio) VALUES (?, ?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, name(entry))
        setString(2, bio(entry))
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

  @Throws(SQLException::class)
  override fun updateBookTitle(title: String, id: Int): Int {
    val sql = "UPDATE book SET title = ? WHERE id = ?"
    return driver.executeRows(sql) {
      setString(1, title)
      setInt(2, id)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateBookTitle(
    stream: Iterable<Input>,
    title: (Input) -> String,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "UPDATE book SET title = ? WHERE id = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, title(entry))
        setInt(2, id(entry))
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          results.add(executeBatch())
          batchCount = 0
          // Performance optimization to reduce register updates per loop iteration
          totalCount += batchSize
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
  override fun <T : Any> getBookByIsbn(isbn: String, mapper: (
    id: Int,
    title: String,
    author_id: Int,
    published_year: Int?,
    isbn: String?,
  ) -> T): T {
    val sql = "SELECT * FROM book WHERE isbn = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3),
        getInt(4).takeUnless { wasNull() },
        getString(5),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, isbn)
    }
  }

  private fun <T : Any, Return> listBooksByAuthor(
    author_id: Int,
    mapper: (
      id: Int,
      title: String,
      author_id: Int,
      published_year: Int?,
      isbn: String?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM book WHERE author_id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3),
        getInt(4).takeUnless { wasNull() },
        getString(5),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setInt(1, author_id)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> listBooksByAuthor(author_id: Int, mapper: (
    id: Int,
    title: String,
    author_id: Int,
    published_year: Int?,
    isbn: String?,
  ) -> T): Many<T> = listBooksByAuthor(author_id, mapper, driver::queryMany)

  private fun <T : Any, Return> listBooksByYearRange(
    published_year: Int,
    published_year2: Int,
    mapper: (
      id: Int,
      title: String,
      author_id: Int,
      published_year: Int?,
      isbn: String?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM book WHERE published_year >= ? AND published_year <= ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3),
        getInt(4).takeUnless { wasNull() },
        getString(5),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setInt(1, published_year)
      setInt(2, published_year2)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> listBooksByYearRange(
    published_year: Int,
    published_year2: Int,
    mapper: (
      id: Int,
      title: String,
      author_id: Int,
      published_year: Int?,
      isbn: String?,
    ) -> T,
  ): Many<T> = listBooksByYearRange(published_year, published_year2, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun createAccount(username: String, crypt_param1: String) {
    val sql = "INSERT INTO account (username, password) VALUES (?, crypt(?, gen_salt('bf')))"
    driver.execute(sql) {
      setString(1, username)
      setString(2, crypt_param1)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> createAccount(
    stream: Iterable<Input>,
    username: (Input) -> String,
    crypt_param1: (Input) -> String,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO account (username, password) VALUES (?, crypt(?, gen_salt('bf')))"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, username(entry))
        setString(2, crypt_param1(entry))
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
}
