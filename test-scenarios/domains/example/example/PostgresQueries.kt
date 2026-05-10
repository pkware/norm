package example

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import kotlin.Any
import kotlin.Array
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.Unit
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.ColumnAdapter
import norm.ConnectionProvider
import norm.Many
import norm.ManyProcessor
import norm.NormDriver
import norm.RealTransactable
import norm.combineExecBatchResults
import norm.decodeArray
import norm.encodeToSqlArray

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
  private val emailAdapter: ColumnAdapter<Email, String> = EmailAdapter(),
  private val moodAdapter: ColumnAdapter<Mood, String> = MoodAdapter(),
  private val positiveIntegerAdapter:
      ColumnAdapter<PositiveInteger, Int> = PositiveIntegerAdapter(),
  private val usPostalCodeAdapter: ColumnAdapter<UsPostalCode, String> = UsPostalCodeAdapter(),
) : RealTransactable(connectionProvider),
    Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  @Throws(SQLException::class)
  override fun <T : Any> getUserByEmail(email: Email, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
  ) -> T): T {
    val sql = "SELECT * FROM users WHERE email = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        emailAdapter.decode(getString(2)),
        getInt(3).takeUnless { wasNull() }?.let { positiveIntegerAdapter.decode(it) },
        getString(4)?.let { usPostalCodeAdapter.decode(it) },
        moodAdapter.decode(getString(5)),
        getString(6)?.let { moodAdapter.decode(it) },
        getArray(7)?.decodeArray(moodAdapter),
        getArray(8)?.decodeArray(positiveIntegerAdapter),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, emailAdapter.encode(email))
    }
  }

  private fun <T : Any, Return> listUsersByAge(
    age: PositiveInteger,
    mapper: (
      id: Int,
      email: Email,
      age: PositiveInteger?,
      zip_code: UsPostalCode?,
      current_mood: Mood,
      previous_mood: Mood?,
      past_moods: Array<Mood?>?,
      scores: Array<PositiveInteger?>?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM users WHERE age > ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        emailAdapter.decode(getString(2)),
        getInt(3).takeUnless { wasNull() }?.let { positiveIntegerAdapter.decode(it) },
        getString(4)?.let { usPostalCodeAdapter.decode(it) },
        moodAdapter.decode(getString(5)),
        getString(6)?.let { moodAdapter.decode(it) },
        getArray(7)?.decodeArray(moodAdapter),
        getArray(8)?.decodeArray(positiveIntegerAdapter),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setInt(1, positiveIntegerAdapter.encode(age))
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> listUsersByAge(age: PositiveInteger, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
  ) -> T): Many<T> = listUsersByAge(age, mapper, driver::queryMany)

  private fun <T : Any, Return> getUsersByZipCode(
    zip_code: UsPostalCode,
    mapper: (
      id: Int,
      email: Email,
      age: PositiveInteger?,
      zip_code: UsPostalCode?,
      current_mood: Mood,
      previous_mood: Mood?,
      past_moods: Array<Mood?>?,
      scores: Array<PositiveInteger?>?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM users WHERE zip_code = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        emailAdapter.decode(getString(2)),
        getInt(3).takeUnless { wasNull() }?.let { positiveIntegerAdapter.decode(it) },
        getString(4)?.let { usPostalCodeAdapter.decode(it) },
        moodAdapter.decode(getString(5)),
        getString(6)?.let { moodAdapter.decode(it) },
        getArray(7)?.decodeArray(moodAdapter),
        getArray(8)?.decodeArray(positiveIntegerAdapter),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setString(1, usPostalCodeAdapter.encode(zip_code))
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> getUsersByZipCode(zip_code: UsPostalCode, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
  ) -> T): Many<T> = getUsersByZipCode(zip_code, mapper, driver::queryMany)

  private fun <T : Any, Return> getUsersByMood(
    current_mood: Mood,
    mapper: (
      id: Int,
      email: Email,
      age: PositiveInteger?,
      zip_code: UsPostalCode?,
      current_mood: Mood,
      previous_mood: Mood?,
      past_moods: Array<Mood?>?,
      scores: Array<PositiveInteger?>?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM users WHERE current_mood = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        emailAdapter.decode(getString(2)),
        getInt(3).takeUnless { wasNull() }?.let { positiveIntegerAdapter.decode(it) },
        getString(4)?.let { usPostalCodeAdapter.decode(it) },
        moodAdapter.decode(getString(5)),
        getString(6)?.let { moodAdapter.decode(it) },
        getArray(7)?.decodeArray(moodAdapter),
        getArray(8)?.decodeArray(positiveIntegerAdapter),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setObject(1, moodAdapter.encode(current_mood), Types.OTHER)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> getUsersByMood(current_mood: Mood, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
  ) -> T): Many<T> = getUsersByMood(current_mood, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun createUser(
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
  ) {
    val sql = """
        |INSERT INTO users (email, age, zip_code, current_mood, previous_mood)
        |VALUES (?, ?, ?, ?, ?)
        """.trimMargin()
    driver.execute(sql) {
      setString(1, emailAdapter.encode(email))
      age?.let { setInt(2, positiveIntegerAdapter.encode(it)) } ?: setNull(2, Types.INTEGER)
      zip_code?.let { setString(3, usPostalCodeAdapter.encode(it)) } ?: setNull(3, Types.VARCHAR)
      setObject(4, moodAdapter.encode(current_mood), Types.OTHER)
      previous_mood?.let { setObject(5, moodAdapter.encode(it), Types.OTHER) } ?: setNull(5, Types.OTHER)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> createUser(
    stream: Iterable<Input>,
    email: Input.() -> Email,
    age: Input.() -> PositiveInteger?,
    zip_code: Input.() -> UsPostalCode?,
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
        setString(1, emailAdapter.encode(entry.email()))
        entry.age()?.let { setInt(2, positiveIntegerAdapter.encode(it)) } ?: setNull(2, Types.INTEGER)
        entry.zip_code()?.let { setString(3, usPostalCodeAdapter.encode(it)) } ?: setNull(3, Types.VARCHAR)
        setObject(4, moodAdapter.encode(entry.current_mood()), Types.OTHER)
        entry.previous_mood()?.let { setObject(5, moodAdapter.encode(it), Types.OTHER) } ?: setNull(5, Types.OTHER)
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
    email: Email?,
    age: PositiveInteger?,
    zipCode: UsPostalCode?,
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
      email?.let { setString(1, emailAdapter.encode(it)) } ?: setNull(1, Types.VARCHAR)
      age?.let { setInt(2, positiveIntegerAdapter.encode(it)) } ?: setNull(2, Types.INTEGER)
      zipCode?.let { setString(3, usPostalCodeAdapter.encode(it)) } ?: setNull(3, Types.VARCHAR)
      setInt(4, id)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateUser(
    stream: Iterable<Input>,
    email: Input.() -> Email?,
    age: Input.() -> PositiveInteger?,
    zipCode: Input.() -> UsPostalCode?,
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
        entry.email()?.let { setString(1, emailAdapter.encode(it)) } ?: setNull(1, Types.VARCHAR)
        entry.age()?.let { setInt(2, positiveIntegerAdapter.encode(it)) } ?: setNull(2, Types.INTEGER)
        entry.zipCode()?.let { setString(3, usPostalCodeAdapter.encode(it)) } ?: setNull(3, Types.VARCHAR)
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
    previous_mood: Mood?,
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
      setObject(1, moodAdapter.encode(current_mood), Types.OTHER)
      previous_mood?.let { setObject(2, moodAdapter.encode(it), Types.OTHER) } ?: setNull(2, Types.OTHER)
      setInt(3, id)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateMood(
    stream: Iterable<Input>,
    current_mood: Input.() -> Mood,
    previous_mood: Input.() -> Mood?,
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
        setObject(1, moodAdapter.encode(entry.current_mood()), Types.OTHER)
        entry.previous_mood()?.let { setObject(2, moodAdapter.encode(it), Types.OTHER) } ?: setNull(2, Types.OTHER)
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

  @Throws(SQLException::class)
  override fun updateArrayColumns(
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    id: Int,
  ) {
    val sql = """
        |UPDATE users
        |SET
        |  past_moods = ?,
        |  scores = ?
        |WHERE id = ?
        """.trimMargin()
    driver.execute(sql) {
      past_moods?.let { setArray(1, it.encodeToSqlArray(connection, "mood", moodAdapter)) } ?: setNull(1, Types.ARRAY)
      scores?.let { setArray(2, it.encodeToSqlArray(connection, "positive_integer", positiveIntegerAdapter)) } ?: setNull(2, Types.ARRAY)
      setInt(3, id)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateArrayColumns(
    stream: Iterable<Input>,
    past_moods: Input.() -> Array<Mood?>?,
    scores: Input.() -> Array<PositiveInteger?>?,
    id: Input.() -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = """
        |UPDATE users
        |SET
        |  past_moods = ?,
        |  scores = ?
        |WHERE id = ?
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        entry.past_moods()?.let { setArray(1, it.encodeToSqlArray(connection, "mood", moodAdapter)) } ?: setNull(1, Types.ARRAY)
        entry.scores()?.let { setArray(2, it.encodeToSqlArray(connection, "positive_integer", positiveIntegerAdapter)) } ?: setNull(2, Types.ARRAY)
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
