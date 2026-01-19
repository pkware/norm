package example

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Many
import norm.NormDriver
import norm.RealTransacter
import norm.combineExecBatchResults
import norm.setInt

public class PostgresQueries(
  driver: NormDriver,
) : RealTransacter(driver),
    Queries {
  @Throws(SQLException::class)
  override fun <T : Any> getUserByEmail(email: String, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
  ) -> T): T {
    val sql = "SELECT id, email, age, zip_code FROM users WHERE email = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3).takeUnless { wasNull() },
        getString(4),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, email)
    }
  }

  private fun <T : Any, R> listUsersByAge(
    age: Int?,
    mapper: (
      id: Int,
      email: String,
      age: Int?,
      zip_code: String?,
    ) -> T,
    block: (String, ResultSet.() -> T) -> R,
  ): R {
    val sql = "SELECT id, email, age, zip_code FROM users WHERE age > ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3).takeUnless { wasNull() },
        getString(4),
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> listUsersByAge(age: Int?, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
  ) -> T): Many<T> = listUsersByAge(age, mapper, driver::queryMany)

  private fun <T : Any, R> getUsersByZipCode(
    zip_code: String?,
    mapper: (
      id: Int,
      email: String,
      age: Int?,
      zip_code: String?,
    ) -> T,
    block: (String, ResultSet.() -> T) -> R,
  ): R {
    val sql = "SELECT id, email, age, zip_code FROM users WHERE zip_code = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3).takeUnless { wasNull() },
        getString(4),
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> getUsersByZipCode(zip_code: String?, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
  ) -> T): Many<T> = getUsersByZipCode(zip_code, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun createUser(
    email: String,
    age: Int?,
    zip_code: String?,
  ) {
    val sql = """
        |INSERT INTO users (email, age, zip_code)
        |VALUES (?, ?, ?)
        """.trimMargin()
    driver.execute(sql) {
      setString(1, email)
      setInt(2, age)
      setString(3, zip_code)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> createUser(
    stream: Iterable<Input>,
    email: Input.() -> String,
    age: Input.() -> Int?,
    zip_code: Input.() -> String?,
    batchSize: Int,
  ): IntArray {
    val sql = """
        |INSERT INTO users (email, age, zip_code)
        |VALUES (?, ?, ?)
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.email())
        setInt(2, entry.age())
        setString(3, entry.zip_code())
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
