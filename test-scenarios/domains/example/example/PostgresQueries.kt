package example

import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.ColumnAdapter
import norm.ConnectionProvider
import norm.Many
import norm.NormDriver
import norm.combineExecBatchResults
import norm.setInt

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
  private val moodAdapter: ColumnAdapter<Mood, String> = MoodAdapter(),
) : Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  @Throws(SQLException::class)
  override fun <T : Any> getUserByEmail(email: String, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
    current_mood: Mood,
    previous_mood: Mood?,
  ) -> T): T {
    val sql = "SELECT * FROM users WHERE email = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3).takeUnless { wasNull() },
        getString(4),
        moodAdapter.decode(getString(5)),
        getString(6)?.let { moodAdapter.decode(it) },
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, email)
    }
  }

  private fun <T : Any, R> listUsersByAge(
    age: Int,
    mapper: (
      id: Int,
      email: String,
      age: Int?,
      zip_code: String?,
      current_mood: Mood,
      previous_mood: Mood?,
    ) -> T,
    block: (String, ResultSet.() -> T) -> R,
  ): R {
    val sql = "SELECT * FROM users WHERE age > ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3).takeUnless { wasNull() },
        getString(4),
        moodAdapter.decode(getString(5)),
        getString(6)?.let { moodAdapter.decode(it) },
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> listUsersByAge(age: Int, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
    current_mood: Mood,
    previous_mood: Mood?,
  ) -> T): Many<T> = listUsersByAge(age, mapper, driver::queryMany)

  private fun <T : Any, R> getUsersByZipCode(
    zip_code: String,
    mapper: (
      id: Int,
      email: String,
      age: Int?,
      zip_code: String?,
      current_mood: Mood,
      previous_mood: Mood?,
    ) -> T,
    block: (String, ResultSet.() -> T) -> R,
  ): R {
    val sql = "SELECT * FROM users WHERE zip_code = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3).takeUnless { wasNull() },
        getString(4),
        moodAdapter.decode(getString(5)),
        getString(6)?.let { moodAdapter.decode(it) },
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> getUsersByZipCode(zip_code: String, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
    current_mood: Mood,
    previous_mood: Mood?,
  ) -> T): Many<T> = getUsersByZipCode(zip_code, mapper, driver::queryMany)

  private fun <T : Any, R> getUsersByMood(
    current_mood: Mood,
    mapper: (
      id: Int,
      email: String,
      age: Int?,
      zip_code: String?,
      current_mood: Mood,
      previous_mood: Mood?,
    ) -> T,
    block: (String, ResultSet.() -> T) -> R,
  ): R {
    val sql = "SELECT * FROM users WHERE current_mood = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3).takeUnless { wasNull() },
        getString(4),
        moodAdapter.decode(getString(5)),
        getString(6)?.let { moodAdapter.decode(it) },
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> getUsersByMood(current_mood: Mood, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
    current_mood: Mood,
    previous_mood: Mood?,
  ) -> T): Many<T> = getUsersByMood(current_mood, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun createUser(
    email: String,
    age: Int?,
    zip_code: String?,
    current_mood: Mood,
    previous_mood: Mood?,
  ) {
    val sql = """
        |INSERT INTO users (email, age, zip_code, current_mood, previous_mood)
        |VALUES (?, ?, ?, ?, ?)
        """.trimMargin()
    driver.execute(sql) {
      setString(1, email)
      setInt(2, age)
      setString(3, zip_code)
      setString(4, moodAdapter.encode(current_mood))
      previous_mood?.let { setString(5, moodAdapter.encode(it)) } ?: setNull(5, Types.VARCHAR)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> createUser(
    stream: Iterable<Input>,
    email: Input.() -> String,
    age: Input.() -> Int?,
    zip_code: Input.() -> String?,
    current_mood: Input.() -> Mood,
    previous_mood: Input.() -> Mood?,
    batchSize: Int,
  ): IntArray {
    val sql = """
        |INSERT INTO users (email, age, zip_code, current_mood, previous_mood)
        |VALUES (?, ?, ?, ?, ?)
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.email())
        setInt(2, entry.age())
        setString(3, entry.zip_code())
        setString(4, moodAdapter.encode(entry.current_mood()))
        entry.previous_mood()?.let { setString(5, moodAdapter.encode(it)) } ?: setNull(5, Types.VARCHAR)
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
  override fun updateUser(
    email: String?,
    age: Int?,
    zipCode: String?,
    id: Int,
  ) {
    val sql = """
        |UPDATE users
        |SET
        |  email = coalesce(?, users.email),
        |  age = coalesce(?, users.age),
        |  zip_code = coalesce(?, users.zip_code)
        |WHERE id = ?
        """.trimMargin()
    driver.execute(sql) {
      setString(1, email)
      setInt(2, age)
      setString(3, zipCode)
      setInt(4, id)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateUser(
    stream: Iterable<Input>,
    email: Input.() -> String?,
    age: Input.() -> Int?,
    zipCode: Input.() -> String?,
    id: Input.() -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = """
        |UPDATE users
        |SET
        |  email = coalesce(?, users.email),
        |  age = coalesce(?, users.age),
        |  zip_code = coalesce(?, users.zip_code)
        |WHERE id = ?
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.email())
        setInt(2, entry.age())
        setString(3, entry.zipCode())
        setInt(4, entry.id())
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
  override fun updateMood(
    current_mood: Mood,
    previous_mood: Mood,
    id: Int,
  ) {
    val sql = """
        |UPDATE users
        |SET
        |  current_mood = ?,
        |  previous_mood = ?
        |WHERE id = ?
        """.trimMargin()
    driver.execute(sql) {
      setString(1, moodAdapter.encode(current_mood))
      setString(2, moodAdapter.encode(previous_mood))
      setInt(3, id)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateMood(
    stream: Iterable<Input>,
    current_mood: Input.() -> Mood,
    previous_mood: Input.() -> Mood,
    id: Input.() -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = """
        |UPDATE users
        |SET
        |  current_mood = ?,
        |  previous_mood = ?
        |WHERE id = ?
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, moodAdapter.encode(entry.current_mood()))
        setString(2, moodAdapter.encode(entry.previous_mood()))
        setInt(3, entry.id())
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
