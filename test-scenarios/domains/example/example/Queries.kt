package example

import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Many

public interface Queries {
  /**
   * ```sql
   * SELECT * FROM users WHERE email = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getUserByEmail(email: String, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
    current_mood: Mood,
    previous_mood: Mood?,
  ) -> T): T

  /**
   * ```sql
   * SELECT * FROM users WHERE email = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getUserByEmail(email: String): Users = getUserByEmail(email, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE age > ?
   * ```
   */
  public fun <T : Any> listUsersByAge(age: Int, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
    current_mood: Mood,
    previous_mood: Mood?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE age > ?
   * ```
   */
  public fun listUsersByAge(age: Int): Many<Users> = listUsersByAge(age, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE zip_code = ?
   * ```
   */
  public fun <T : Any> getUsersByZipCode(zip_code: String, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
    current_mood: Mood,
    previous_mood: Mood?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE zip_code = ?
   * ```
   */
  public fun getUsersByZipCode(zip_code: String): Many<Users> = getUsersByZipCode(zip_code, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE current_mood = ?
   * ```
   */
  public fun <T : Any> getUsersByMood(current_mood: Mood, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
    current_mood: Mood,
    previous_mood: Mood?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE current_mood = ?
   * ```
   */
  public fun getUsersByMood(current_mood: Mood): Many<Users> = getUsersByMood(current_mood, ::Users)

  /**
   * ```sql
   * INSERT INTO users (email, age, zip_code, current_mood, previous_mood)
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
    email: Input.() -> String,
    age: Input.() -> Int?,
    zip_code: Input.() -> String?,
    current_mood: Input.() -> Mood,
    previous_mood: Input.() -> Mood?,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO users (email, age, zip_code, current_mood, previous_mood)
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
    email: Input.() -> String,
    age: Input.() -> Int?,
    zip_code: Input.() -> String?,
    current_mood: Input.() -> Mood,
    previous_mood: Input.() -> Mood?,
  ): IntArray = createUser(stream, email, age, zip_code, current_mood, previous_mood, 100)

  /**
   * ```sql
   * INSERT INTO users (email, age, zip_code, current_mood, previous_mood)
   * VALUES (?, ?, ?, ?, ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun createUser(
    email: String,
    age: Int?,
    zip_code: String?,
    current_mood: Mood,
    previous_mood: Mood?,
  )

  /**
   * ```sql
   * UPDATE users
   * SET
   *   email = coalesce(?, users.email),
   *   age = coalesce(?, users.age),
   *   zip_code = coalesce(?, users.zip_code)
   * WHERE id = ?
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
  public fun <Input : Any> updateUser(
    stream: Iterable<Input>,
    email: Input.() -> String?,
    age: Input.() -> Int?,
    zipCode: Input.() -> String?,
    id: Input.() -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * UPDATE users
   * SET
   *   email = coalesce(?, users.email),
   *   age = coalesce(?, users.age),
   *   zip_code = coalesce(?, users.zip_code)
   * WHERE id = ?
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
  public fun <Input : Any> updateUser(
    stream: Iterable<Input>,
    email: Input.() -> String?,
    age: Input.() -> Int?,
    zipCode: Input.() -> String?,
    id: Input.() -> Int,
  ): IntArray = updateUser(stream, email, age, zipCode, id, 100)

  /**
   * ```sql
   * UPDATE users
   * SET
   *   email = coalesce(?, users.email),
   *   age = coalesce(?, users.age),
   *   zip_code = coalesce(?, users.zip_code)
   * WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun updateUser(
    email: String?,
    age: Int?,
    zipCode: String?,
    id: Int,
  )

  /**
   * ```sql
   * UPDATE users
   * SET
   *   current_mood = ?,
   *   previous_mood = ?
   * WHERE id = ?
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
  public fun <Input : Any> updateMood(
    stream: Iterable<Input>,
    current_mood: Input.() -> Mood,
    previous_mood: Input.() -> Mood,
    id: Input.() -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * UPDATE users
   * SET
   *   current_mood = ?,
   *   previous_mood = ?
   * WHERE id = ?
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
  public fun <Input : Any> updateMood(
    stream: Iterable<Input>,
    current_mood: Input.() -> Mood,
    previous_mood: Input.() -> Mood,
    id: Input.() -> Int,
  ): IntArray = updateMood(stream, current_mood, previous_mood, id, 100)

  /**
   * ```sql
   * UPDATE users
   * SET
   *   current_mood = ?,
   *   previous_mood = ?
   * WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun updateMood(
    current_mood: Mood,
    previous_mood: Mood,
    id: Int,
  )
}
