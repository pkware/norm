package example

import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Transacter

public interface Queries : Transacter {
  @Throws(SQLException::class)
  public fun <T : Any> getAuthorById(id: Int, mapper: (
    id: Int,
    name: String,
    bio: String?,
  ) -> T): T

  @Throws(SQLException::class)
  public fun getAuthorById(id: Int): Author = getAuthorById(id, ::Author)

  /**
   * Ad-hoc projection: only some columns returned, so generates a query-specific type.
   */
  @Throws(SQLException::class)
  public fun <T : Any> getBookTitleAndYear(id: Int, mapper: (title: String, published_year: Int?) -> T): T

  /**
   * Ad-hoc projection: only some columns returned, so generates a query-specific type.
   */
  @Throws(SQLException::class)
  public fun getBookTitleAndYear(id: Int): GetBookTitleAndYear = getBookTitleAndYear(id, ::GetBookTitleAndYear)

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
  public fun <Input : Any> addAuthor(
    stream: Iterable<Input>,
    name: Input.() -> String,
    bio: Input.() -> String?,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [addAuthor] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> addAuthor(
    stream: Iterable<Input>,
    name: Input.() -> String,
    bio: Input.() -> String?,
  ): IntArray = addAuthor(stream, name, bio, 100)

  /**
   * Norm: Executes a SQL statement.
   *
   * @param name Full name of the author.
   * @param bio Short biography. Null if not provided.
   */
  @Throws(SQLException::class)
  public fun addAuthor(name: String, bio: String?)

  /**
   * Cross-table join: projection columns come from two different tables.
   */
  @Throws(SQLException::class)
  public fun <T : Any> getBookWithAuthorName(id: Int, mapper: (
    title: String,
    published_year: Int?,
    author_name: String,
  ) -> T): T

  /**
   * Cross-table join: projection columns come from two different tables.
   */
  @Throws(SQLException::class)
  public fun getBookWithAuthorName(id: Int): GetBookWithAuthorName = getBookWithAuthorName(id, ::GetBookWithAuthorName)

  /**
   * Function result: COUNT has no source table or column.
   */
  @Throws(SQLException::class)
  public fun <T : Any> countBooksByAuthor(id: Int, mapper: (name: String, book_count: Long) -> T): T

  /**
   * Function result: COUNT has no source table or column.
   */
  @Throws(SQLException::class)
  public fun countBooksByAuthor(id: Int): CountBooksByAuthor = countBooksByAuthor(id, ::CountBooksByAuthor)
}
