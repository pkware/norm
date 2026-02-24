package example

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Double
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
  override fun createUser(username: String, crypt_param1: String) {
    val sql = """
        |INSERT INTO user_credentials (username, password_hash)
        |VALUES (?, crypt(?, gen_salt('bf')))
        """.trimMargin()
    driver.execute(sql) {
      setString(1, username)
      setString(2, crypt_param1)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> createUser(
    stream: Iterable<Input>,
    username: Input.() -> String,
    crypt_param1: Input.() -> String,
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
        setString(2, entry.crypt_param1())
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
    crypt_param1: String,
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
      setString(2, crypt_param1)
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

  @Throws(SQLException::class)
  override fun <T : Any> computeDigest(
    digest_param1: String,
    digest_param2: String,
    mapper: (hash: ByteArray) -> T,
  ): T {
    val sql = "SELECT digest(?, ?) AS hash"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getBytes(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, digest_param1)
      setString(2, digest_param2)
    }
  }

  @Throws(SQLException::class)
  override fun <T : Any> computeHmac(
    hmac_param1: String,
    hmac_param2: String,
    hmac_param3: String,
    mapper: (signature: ByteArray) -> T,
  ): T {
    val sql = "SELECT hmac(?, ?, ?) AS signature"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getBytes(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, hmac_param1)
      setString(2, hmac_param2)
      setString(3, hmac_param3)
    }
  }

  @Throws(SQLException::class)
  override fun <T : Any> computeEncodedHash(
    digest_param1: String,
    digest_param2: String,
    encode_param2: String,
    mapper: (encoded_hash: String) -> T,
  ): T {
    val sql = "SELECT encode(digest(?, ?), ?) AS encoded_hash"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, digest_param1)
      setString(2, digest_param2)
      setString(3, encode_param2)
    }
  }

  @Throws(SQLException::class)
  override fun <T : Any> decodeData(
    decode_param1: String,
    decode_param2: String,
    mapper: (decoded: ByteArray) -> T,
  ): T {
    val sql = "SELECT decode(?, ?) AS decoded"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getBytes(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, decode_param1)
      setString(2, decode_param2)
    }
  }

  private fun <T, R> generateRandomNumbers(
    normal_rand_param1: Int,
    normal_rand_param2: Double,
    normal_rand_param3: Double,
    mapper: (normal_rand: Double?) -> T,
    block: (String, ResultSet.() -> T) -> R,
  ): R {
    val sql = "SELECT * FROM normal_rand(?, ?, ?)"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getDouble(1).takeUnless { wasNull() },
      )
    }
    return block(sql, rowReader)
  }

  override fun <T> generateRandomNumbers(
    normal_rand_param1: Int,
    normal_rand_param2: Double,
    normal_rand_param3: Double,
    mapper: (normal_rand: Double?) -> T,
  ): Many<T> = generateRandomNumbers(normal_rand_param1, normal_rand_param2, normal_rand_param3, mapper, driver::queryMany)

  private fun <T : Any, R> getUserSettingsPivot(
    crosstab_param1: String,
    mapper: (
      user_id: Int?,
      setting1: String?,
      setting2: String?,
    ) -> T,
    block: (String, ResultSet.() -> T) -> R,
  ): R {
    val sql = """
        |SELECT user_id, setting1, setting2
        |FROM crosstab(?) AS ct(user_id int, setting1 text, setting2 text)
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1).takeUnless { wasNull() },
        getString(2),
        getString(3),
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> getUserSettingsPivot(crosstab_param1: String, mapper: (
    user_id: Int?,
    setting1: String?,
    setting2: String?,
  ) -> T): Many<T> = getUserSettingsPivot(crosstab_param1, mapper, driver::queryMany)

  private fun <T : Any, R> getUserSettingsByCategory(
    crosstab_param1: String,
    crosstab_param2: String,
    mapper: (
      row_name: String?,
      category1: Int?,
      category2: Int?,
      category3: Int?,
    ) -> T,
    block: (String, ResultSet.() -> T) -> R,
  ): R {
    val sql = """
        |SELECT row_name, category1, category2, category3
        |FROM crosstab(?, ?) AS ct(row_name text, category1 int, category2 int, category3 int)
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getInt(2).takeUnless { wasNull() },
        getInt(3).takeUnless { wasNull() },
        getInt(4).takeUnless { wasNull() },
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> getUserSettingsByCategory(
    crosstab_param1: String,
    crosstab_param2: String,
    mapper: (
      row_name: String?,
      category1: Int?,
      category2: Int?,
      category3: Int?,
    ) -> T,
  ): Many<T> = getUserSettingsByCategory(crosstab_param1, crosstab_param2, mapper, driver::queryMany)
}
