package example.typemappings

import com.example.CustomMood
import com.example.JsonData
import com.example.UserPreferences
import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.collections.Iterable
import kotlin.jvm.Throws

public interface Queries {
  /**
   * ```sql
   * SELECT * FROM users WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getUserById(id: Int, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    current_mood: CustomMood,
    metadata: JsonData,
    preferences: UserPreferences,
  ) -> T): T

  /**
   * ```sql
   * SELECT * FROM users WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getUserById(id: Int): Users = getUserById(id, ::Users)

  /**
   * ```sql
   * INSERT INTO users (email, age, current_mood, metadata, preferences)
   * VALUES (?, ?, ?, ?, ?)
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [java.sql.Statement.SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [java.sql.Statement.EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> createUser(
    stream: Iterable<Input>,
    email: Input.() -> Email,
    age: Input.() -> PositiveInteger?,
    current_mood: Input.() -> CustomMood,
    metadata: Input.() -> JsonData,
    preferences: Input.() -> UserPreferences,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO users (email, age, current_mood, metadata, preferences)
   * VALUES (?, ?, ?, ?, ?)
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [java.sql.Statement.SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [java.sql.Statement.EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> createUser(
    stream: Iterable<Input>,
    email: Input.() -> Email,
    age: Input.() -> PositiveInteger?,
    current_mood: Input.() -> CustomMood,
    metadata: Input.() -> JsonData,
    preferences: Input.() -> UserPreferences,
  ): IntArray = createUser(stream, email, age, current_mood, metadata, preferences, 100)

  /**
   * ```sql
   * INSERT INTO users (email, age, current_mood, metadata, preferences)
   * VALUES (?, ?, ?, ?, ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun createUser(
    email: Email,
    age: PositiveInteger?,
    current_mood: CustomMood,
    metadata: JsonData,
    preferences: UserPreferences,
  )
}
