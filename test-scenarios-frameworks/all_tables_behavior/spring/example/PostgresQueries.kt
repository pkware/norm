package example

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.ConnectionProvider
import norm.NormDriver
import norm.combineExecBatchResults
import org.springframework.stereotype.Component

@Component
public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  @Throws(SQLException::class)
  override fun <T : Any> getAuthor(id: Int, mapper: (
    id: Int,
    name: String,
    email: String?,
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
  override fun <T : Any> getBook(id: Int, mapper: (
    id: Int,
    title: String,
    authorId: Int,
  ) -> T): T {
    val sql = "SELECT * FROM book WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun addAuthor(name: String, email: String?) {
    val sql = "INSERT INTO author(name, email) VALUES (?, ?)"
    driver.execute(sql) {
      setString(1, name)
      setString(2, email)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> addAuthor(
    stream: Iterable<Input>,
    name: Input.() -> String,
    email: Input.() -> String?,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO author(name, email) VALUES (?, ?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.name())
        setString(2, entry.email())
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
