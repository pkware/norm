package example.typemappings

import com.example.CustomMood
import com.example.JsonData
import com.example.UserPreferences
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
  private val jsonbAdapter: ColumnAdapter<JsonData, String>,
  private val moodAdapter: ColumnAdapter<CustomMood, String>,
  private val usersPreferencesAdapter: ColumnAdapter<UserPreferences, String>,
  private val emailAdapter: ColumnAdapter<Email, String> = EmailAdapter(),
  private val positiveIntegerAdapter:
      ColumnAdapter<PositiveInteger, Int> = PositiveIntegerAdapter(),
) : RealTransactable(connectionProvider),
    Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  @Throws(SQLException::class)
  override fun <T : Any> getUserById(id: Int, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    current_mood: CustomMood,
    metadata: JsonData,
    preferences: UserPreferences,
    past_moods: Array<CustomMood?>?,
    tag_list: Array<JsonData?>?,
  ) -> T): T {
    val sql = "SELECT * FROM users WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        emailAdapter.decode(getString(2)),
        getInt(3).takeUnless { wasNull() }?.let { positiveIntegerAdapter.decode(it) },
        moodAdapter.decode(getString(4)),
        jsonbAdapter.decode(getString(5)),
        usersPreferencesAdapter.decode(getString(6)),
        getArray(7)?.decodeArray(moodAdapter),
        getArray(8)?.decodeArray(jsonbAdapter),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun createUser(
    email: Email,
    age: PositiveInteger?,
    current_mood: CustomMood,
    metadata: JsonData,
    preferences: UserPreferences,
  ) {
    val sql = """
        |INSERT INTO users (email, age, current_mood, metadata, preferences)
        |VALUES (?, ?, ?, ?, ?)
        """.trimMargin()
    driver.execute(sql) {
      setString(1, emailAdapter.encode(email))
      age?.let { setInt(2, positiveIntegerAdapter.encode(it)) } ?: setNull(2, Types.INTEGER)
      setObject(3, moodAdapter.encode(current_mood), Types.OTHER)
      setObject(4, jsonbAdapter.encode(metadata), Types.OTHER)
      setObject(5, usersPreferencesAdapter.encode(preferences), Types.OTHER)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> createUser(
    stream: Iterable<Input>,
    email: (Input) -> Email,
    age: (Input) -> PositiveInteger?,
    current_mood: (Input) -> CustomMood,
    metadata: (Input) -> JsonData,
    preferences: (Input) -> UserPreferences,
    batchSize: Int,
  ): IntArray {
    val sql = """
        |INSERT INTO users (email, age, current_mood, metadata, preferences)
        |VALUES (?, ?, ?, ?, ?)
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, emailAdapter.encode(email(entry)))
        age(entry)?.let { setInt(2, positiveIntegerAdapter.encode(it)) } ?: setNull(2, Types.INTEGER)
        setObject(3, moodAdapter.encode(current_mood(entry)), Types.OTHER)
        setObject(4, jsonbAdapter.encode(metadata(entry)), Types.OTHER)
        setObject(5, usersPreferencesAdapter.encode(preferences(entry)), Types.OTHER)
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
  override fun updatePastMoods(
    past_moods: Array<CustomMood?>?,
    tag_list: Array<JsonData?>?,
    id: Int,
  ) {
    val sql = "UPDATE users SET past_moods = ?, tag_list = ? WHERE id = ?"
    driver.execute(sql) {
      past_moods?.let { setArray(1, it.encodeToSqlArray(connection, "mood", moodAdapter)) } ?: setNull(1, Types.ARRAY)
      tag_list?.let { setArray(2, it.encodeToSqlArray(connection, "jsonb", jsonbAdapter)) } ?: setNull(2, Types.ARRAY)
      setInt(3, id)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updatePastMoods(
    stream: Iterable<Input>,
    past_moods: (Input) -> Array<CustomMood?>?,
    tag_list: (Input) -> Array<JsonData?>?,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "UPDATE users SET past_moods = ?, tag_list = ? WHERE id = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        past_moods(entry)?.let { setArray(1, it.encodeToSqlArray(connection, "mood", moodAdapter)) } ?: setNull(1, Types.ARRAY)
        tag_list(entry)?.let { setArray(2, it.encodeToSqlArray(connection, "jsonb", jsonbAdapter)) } ?: setNull(2, Types.ARRAY)
        setInt(3, id(entry))
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

  private fun <T : Any, Return> updatePreferences(
    preferences: UserPreferences,
    id: Int,
    mapper: (id: Int, old_preferences: UserPreferences) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = """
        |UPDATE users SET preferences = ? WHERE id = ?
        |RETURNING id, preferences AS old_preferences
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        usersPreferencesAdapter.decode(getString(2)),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setObject(1, usersPreferencesAdapter.encode(preferences), Types.OTHER)
      setInt(2, id)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> updatePreferences(
    preferences: UserPreferences,
    id: Int,
    mapper: (id: Int, old_preferences: UserPreferences) -> T,
  ): Many<T> = updatePreferences(preferences, id, mapper, driver::queryMany)
}
