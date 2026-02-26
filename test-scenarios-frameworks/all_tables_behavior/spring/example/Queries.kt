package example

import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws

public interface Queries {
  /**
   * ```sql
   * SELECT * FROM author WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getAuthor(id: Int, mapper: (
    id: Int,
    name: String,
    email: String?,
  ) -> T): T

  /**
   * ```sql
   * SELECT * FROM author WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getAuthor(id: Int): Author = getAuthor(id, ::Author)

  /**
   * ```sql
   * SELECT * FROM book WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getBook(id: Int, mapper: (
    id: Int,
    title: String,
    authorId: Int,
  ) -> T): T

  /**
   * ```sql
   * SELECT * FROM book WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getBook(id: Int): Book = getBook(id, ::Book)

  /**
   * ```sql
   * INSERT INTO author(name, email) VALUES (?, ?)
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
  public fun <Input : Any> addAuthor(
    stream: Iterable<Input>,
    name: Input.() -> String,
    email: Input.() -> String?,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO author(name, email) VALUES (?, ?)
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
  public fun <Input : Any> addAuthor(
    stream: Iterable<Input>,
    name: Input.() -> String,
    email: Input.() -> String?,
  ): IntArray = addAuthor(stream, name, email, 100)

  /**
   * ```sql
   * INSERT INTO author(name, email) VALUES (?, ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun addAuthor(name: String, email: String?)
}
