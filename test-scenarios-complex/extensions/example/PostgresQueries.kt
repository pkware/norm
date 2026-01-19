package example

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Many
import norm.NormDriver
import norm.RealTransacter
import norm.combineExecBatchResults

public class PostgresQueries(
  driver: NormDriver,
) : RealTransacter(driver),
    Queries {
  @Throws(SQLException::class)
  override fun createUser(username: String, crypt: String) {
    val sql = """
        |INSERT INTO user_credentials (username, password_hash)
        |VALUES (?, crypt(?, gen_salt('bf')))
        """.trimMargin()
    driver.execute(sql) {
      setString(1, username)
      setString(2, crypt)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> createUser(
    stream: Iterable<Input>,
    username: Input.() -> String,
    crypt: Input.() -> String,
    batchSize: Int,
  ): IntArray {
    val sql = """
        |INSERT INTO user_credentials (username, password_hash)
        |VALUES (?, crypt(?, gen_salt('bf')))
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.username())
        setString(2, entry.crypt())
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
  override fun <T : Any> verifyPassword(
    username: String,
    crypt: String,
    mapper: (valid: Boolean) -> T,
  ): T {
    val sql = """
        |SELECT EXISTS(
        |  SELECT 1 FROM user_credentials
        |  WHERE username = ?
        |  AND password_hash = crypt(?, password_hash)
        |) AS valid
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getBoolean(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, username)
      setString(2, crypt)
    }
  }

  private fun <T : Any, R> getUserSettings(
    user_id: Int,
    mapper: (setting_key: String, setting_value: String?) -> T,
    block: (String, ResultSet.() -> T) -> R,
  ): R {
    val sql = """
        |SELECT setting_key, setting_value
        |FROM user_settings
        |WHERE user_id = ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> getUserSettings(user_id: Int, mapper: (setting_key: String, setting_value: String?) -> T): Many<T> = getUserSettings(user_id, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun setSetting(
    user_id: Int,
    setting_key: String,
    setting_value: String?,
  ) {
    val sql = """
        |INSERT INTO user_settings (user_id, setting_key, setting_value)
        |VALUES (?, ?, ?)
        |ON CONFLICT (user_id, setting_key)
        |DO UPDATE SET setting_value = EXCLUDED.setting_value
        """.trimMargin()
    driver.execute(sql) {
      setInt(1, user_id)
      setString(2, setting_key)
      setString(3, setting_value)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> setSetting(
    stream: Iterable<Input>,
    user_id: Input.() -> Int,
    setting_key: Input.() -> String,
    setting_value: Input.() -> String?,
    batchSize: Int,
  ): IntArray {
    val sql = """
        |INSERT INTO user_settings (user_id, setting_key, setting_value)
        |VALUES (?, ?, ?)
        |ON CONFLICT (user_id, setting_key)
        |DO UPDATE SET setting_value = EXCLUDED.setting_value
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, entry.user_id())
        setString(2, entry.setting_key())
        setString(3, entry.setting_value())
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
