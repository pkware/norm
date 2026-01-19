package example

import java.sql.SQLException
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Many
import norm.Transacter
import norm.inputValue

public interface Queries : Transacter {
  /**
   * Norm: Executes a SQL statement.
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
    username: Input.() -> String,
    crypt: Input.() -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [createUser] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> createUser(
    stream: Iterable<Input>,
    username: Input.() -> String,
    crypt: Input.() -> String,
  ): IntArray = createUser(stream, username, crypt, 100)

  /**
   * Query using pgcrypto for password hashing
   *
   * Norm: Executes a SQL statement.
   */
  @Throws(SQLException::class)
  public fun createUser(username: String, crypt: String)

  /**
   * Query using pgcrypto for password verification
   */
  @Throws(SQLException::class)
  public fun <T : Any> verifyPassword(
    username: String,
    crypt: String,
    mapper: (valid: Boolean) -> T,
  ): T

  /**
   * Query using pgcrypto for password verification
   */
  @Throws(SQLException::class)
  public fun verifyPassword(username: String, crypt: String): Boolean = verifyPassword(username, crypt, ::inputValue)

  /**
   * Query using settings table (for potential tablefunc pivot)
   */
  public fun <T : Any> getUserSettings(user_id: Int, mapper: (setting_key: String, setting_value: String?) -> T): Many<T>

  /**
   * Query using settings table (for potential tablefunc pivot)
   */
  public fun getUserSettings(user_id: Int): Many<GetUserSettings> = getUserSettings(user_id, ::GetUserSettings)

  /**
   * Norm: Executes a SQL statement.
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
  public fun <Input : Any> setSetting(
    stream: Iterable<Input>,
    user_id: Input.() -> Int,
    setting_key: Input.() -> String,
    setting_value: Input.() -> String?,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [setSetting] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> setSetting(
    stream: Iterable<Input>,
    user_id: Input.() -> Int,
    setting_key: Input.() -> String,
    setting_value: Input.() -> String?,
  ): IntArray = setSetting(stream, user_id, setting_key, setting_value, 100)

  /**
   * Norm: Executes a SQL statement.
   */
  @Throws(SQLException::class)
  public fun setSetting(
    user_id: Int,
    setting_key: String,
    setting_value: String?,
  )
}
