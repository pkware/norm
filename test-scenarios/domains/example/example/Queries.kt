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
  @Throws(SQLException::class)
  public fun <T : Any> getUserByEmail(email: String, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
  ) -> T): T

  @Throws(SQLException::class)
  public fun getUserByEmail(email: String): Users = getUserByEmail(email, ::Users)

  public fun <T : Any> listUsersByAge(age: Int, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
  ) -> T): Many<T>

  public fun listUsersByAge(age: Int): Many<Users> = listUsersByAge(age, ::Users)

  public fun <T : Any> getUsersByZipCode(zip_code: String, mapper: (
    id: Int,
    email: String,
    age: Int?,
    zip_code: String?,
  ) -> T): Many<T>

  public fun getUsersByZipCode(zip_code: String): Many<Users> = getUsersByZipCode(zip_code, ::Users)

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
    email: Input.() -> String,
    age: Input.() -> Int?,
    zip_code: Input.() -> String?,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [createUser] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> createUser(
    stream: Iterable<Input>,
    email: Input.() -> String,
    age: Input.() -> Int?,
    zip_code: Input.() -> String?,
  ): IntArray = createUser(stream, email, age, zip_code, 100)

  /**
   * Norm: Executes a SQL statement.
   */
  @Throws(SQLException::class)
  public fun createUser(
    email: String,
    age: Int?,
    zip_code: String?,
  )

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
  public fun <Input : Any> updateUser(
    stream: Iterable<Input>,
    email: Input.() -> String,
    age: Input.() -> Int,
    zipCode: Input.() -> String,
    id: Input.() -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [updateUser] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateUser(
    stream: Iterable<Input>,
    email: Input.() -> String,
    age: Input.() -> Int,
    zipCode: Input.() -> String,
    id: Input.() -> Int,
  ): IntArray = updateUser(stream, email, age, zipCode, id, 100)

  /**
   * Norm: Executes a SQL statement.
   */
  @Throws(SQLException::class)
  public fun updateUser(
    email: String,
    age: Int,
    zipCode: String,
    id: Int,
  )
}
