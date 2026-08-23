package example

import java.sql.SQLException
import java.sql.Statement.EXECUTE_FAILED
import java.sql.Statement.SUCCESS_NO_INFO
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Many
import norm.Query
import norm.Transactable

public interface Queries : Transactable {
  /**
   * ```sql
   * SELECT * FROM author WHERE id = ?
   * ```
   *
   * @param id Unique identifier for the author.
   */
  @Throws(SQLException::class)
  public fun <T : Any> getAuthorById(id: Int, mapper: (
    id: Int,
    name: String,
    bio: String?,
  ) -> T): T

  /**
   * ```sql
   * SELECT * FROM author WHERE id = ?
   * ```
   *
   * @param id Unique identifier for the author.
   */
  @Throws(SQLException::class)
  public fun getAuthorById(id: Int): Author = getAuthorById(id, ::Author)

  /**
   * Ad-hoc projection: only some columns returned, so generates a query-specific type.
   *
   * ```sql
   * SELECT title, published_year FROM book WHERE id = ?
   * ```
   *
   * @param id Unique identifier for the book.
   */
  @Throws(SQLException::class)
  public fun <T : Any> getBookTitleAndYear(id: Int, mapper: (title: String, published_year: Int?) -> T): T

  /**
   * Ad-hoc projection: only some columns returned, so generates a query-specific type.
   *
   * ```sql
   * SELECT title, published_year FROM book WHERE id = ?
   * ```
   *
   * @param id Unique identifier for the book.
   */
  @Throws(SQLException::class)
  public fun getBookTitleAndYear(id: Int): GetBookTitleAndYear = getBookTitleAndYear(id, ::GetBookTitleAndYear)

  /**
   * ```sql
   * INSERT INTO author (name, bio) VALUES (?, ?)
   * ```
   *
   * @param name Full name of the author.
   * @param bio Short biography. Null if not provided.
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> addAuthor(
    stream: Iterable<Input>,
    name: (Input) -> String,
    bio: (Input) -> String?,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO author (name, bio) VALUES (?, ?)
   * ```
   *
   * Uses a batch size of 100.
   *
   * @param name Full name of the author.
   * @param bio Short biography. Null if not provided.
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> addAuthor(
    stream: Iterable<Input>,
    name: (Input) -> String,
    bio: (Input) -> String?,
  ): IntArray = addAuthor(stream, name, bio, 100)

  /**
   * ```sql
   * INSERT INTO author (name, bio) VALUES (?, ?)
   * ```
   *
   * @param name Full name of the author.
   * @param bio Short biography. Null if not provided.
   */
  @Throws(SQLException::class)
  public fun addAuthor(name: String, bio: String?)

  /**
   * Cross-table join: projection columns come from two different tables.
   *
   * ```sql
   * SELECT book.title, book.published_year, author.name AS author_name
   * FROM book
   * JOIN author ON author.id = book.author_id
   * WHERE book.id = ?
   * ```
   *
   * @param id Unique identifier for the book.
   */
  @Throws(SQLException::class)
  public fun <T : Any> getBookWithAuthorName(id: Int, mapper: (
    title: String,
    published_year: Int?,
    author_name: String,
  ) -> T): T

  /**
   * Cross-table join: projection columns come from two different tables.
   *
   * ```sql
   * SELECT book.title, book.published_year, author.name AS author_name
   * FROM book
   * JOIN author ON author.id = book.author_id
   * WHERE book.id = ?
   * ```
   *
   * @param id Unique identifier for the book.
   */
  @Throws(SQLException::class)
  public fun getBookWithAuthorName(id: Int): GetBookWithAuthorName = getBookWithAuthorName(id, ::GetBookWithAuthorName)

  /**
   * Function result: COUNT has no source table or column.
   *
   * ```sql
   * SELECT author.name, COUNT(*) AS book_count
   * FROM author
   * JOIN book ON book.author_id = author.id
   * WHERE author.id = ?
   * GROUP BY author.name
   * ```
   *
   * @param id Unique identifier for the author.
   */
  @Throws(SQLException::class)
  public fun <T : Any> countBooksByAuthor(id: Int, mapper: (name: String, book_count: Long) -> T): T

  /**
   * Function result: COUNT has no source table or column.
   *
   * ```sql
   * SELECT author.name, COUNT(*) AS book_count
   * FROM author
   * JOIN book ON book.author_id = author.id
   * WHERE author.id = ?
   * GROUP BY author.name
   * ```
   *
   * @param id Unique identifier for the author.
   */
  @Throws(SQLException::class)
  public fun countBooksByAuthor(id: Int): CountBooksByAuthor = countBooksByAuthor(id, ::CountBooksByAuthor)

  /**
   * UPDATE: SET params and WHERE params both get column comments.
   *
   * ```sql
   * UPDATE book SET title = ? WHERE id = ?
   * ```
   *
   * @param title Title of the book.
   * @param id Unique identifier for the book.
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateBookTitle(
    stream: Iterable<Input>,
    title: (Input) -> String,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * UPDATE: SET params and WHERE params both get column comments.
   *
   * ```sql
   * UPDATE book SET title = ? WHERE id = ?
   * ```
   *
   * Uses a batch size of 100.
   *
   * @param title Title of the book.
   * @param id Unique identifier for the book.
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateBookTitle(
    stream: Iterable<Input>,
    title: (Input) -> String,
    id: (Input) -> Int,
  ): IntArray = updateBookTitle(stream, title, id, 100)

  /**
   * UPDATE: SET params and WHERE params both get column comments.
   *
   * ```sql
   * UPDATE book SET title = ? WHERE id = ?
   * ```
   *
   * @param title Title of the book.
   * @param id Unique identifier for the book.
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateBookTitle(title: String, id: Int): Int

  /**
   * Column with no COMMENT ON: isbn should not produce a @param tag.
   *
   * ```sql
   * SELECT * FROM book WHERE isbn = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getBookByIsbn(isbn: String, mapper: (
    id: Int,
    title: String,
    author_id: Int,
    published_year: Int?,
    isbn: String?,
  ) -> T): T

  /**
   * Column with no COMMENT ON: isbn should not produce a @param tag.
   *
   * ```sql
   * SELECT * FROM book WHERE isbn = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getBookByIsbn(isbn: String): Book = getBookByIsbn(isbn, ::Book)

  /**
   * :many with parameters: verify params flow through the Many code path.
   *
   * ```sql
   * SELECT * FROM book WHERE author_id = ?
   * ```
   *
   * @param author_id Foreign key to the author who wrote the book.
   */
  public fun <T : Any> listBooksByAuthor(author_id: Int, mapper: (
    id: Int,
    title: String,
    author_id: Int,
    published_year: Int?,
    isbn: String?,
  ) -> T): Many<T>

  /**
   * :many with parameters: verify params flow through the Many code path.
   *
   * ```sql
   * SELECT * FROM book WHERE author_id = ?
   * ```
   *
   * @param author_id Foreign key to the author who wrote the book.
   */
  public fun listBooksByAuthor(author_id: Int): Many<Book> = listBooksByAuthor(author_id, ::Book)

  /**
   * Range: two params on the same column get deduplicated names and both get comments.
   *
   * ```sql
   * SELECT * FROM book WHERE published_year >= ? AND published_year <= ?
   * ```
   *
   * @param published_year Year the book was published. Null if unknown.
   * @param published_year2 Year the book was published. Null if unknown.
   */
  public fun <T : Any> listBooksByYearRange(
    published_year: Int,
    published_year2: Int,
    mapper: (
      id: Int,
      title: String,
      author_id: Int,
      published_year: Int?,
      isbn: String?,
    ) -> T,
  ): Many<T>

  /**
   * Range: two params on the same column get deduplicated names and both get comments.
   *
   * ```sql
   * SELECT * FROM book WHERE published_year >= ? AND published_year <= ?
   * ```
   *
   * @param published_year Year the book was published. Null if unknown.
   * @param published_year2 Year the book was published. Null if unknown.
   */
  public fun listBooksByYearRange(published_year: Int, published_year2: Int): Many<Book> = listBooksByYearRange(published_year, published_year2, ::Book)

  /**
   * Function-wrapped parameter: the param maps to the column despite being inside crypt().
   *
   * ```sql
   * INSERT INTO account (username, password) VALUES (?, crypt(?, gen_salt('bf')))
   * ```
   *
   * @param username Login name for the account.
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> createAccount(
    stream: Iterable<Input>,
    username: (Input) -> String,
    crypt_param1: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Function-wrapped parameter: the param maps to the column despite being inside crypt().
   *
   * ```sql
   * INSERT INTO account (username, password) VALUES (?, crypt(?, gen_salt('bf')))
   * ```
   *
   * Uses a batch size of 100.
   *
   * @param username Login name for the account.
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> createAccount(
    stream: Iterable<Input>,
    username: (Input) -> String,
    crypt_param1: (Input) -> String,
  ): IntArray = createAccount(stream, username, crypt_param1, 100)

  /**
   * Function-wrapped parameter: the param maps to the column despite being inside crypt().
   *
   * ```sql
   * INSERT INTO account (username, password) VALUES (?, crypt(?, gen_salt('bf')))
   * ```
   *
   * @param username Login name for the account.
   */
  @Throws(SQLException::class)
  public fun createAccount(username: String, crypt_param1: String)

  /**
   * Quoted column reference WITH an alias: originalName must resolve through the quoted column's
   * own name ("Foo"), not through the alias ("bar") pgjdbc's own ResultSetMetaData.getColumnName
   * reports instead when AS is used -- otherwise comment lookup for "Foo" fails outright, since no
   * column is actually named "bar". A second column is selected so a data class (with @property
   * comment lines) is generated instead of a bare scalar, making the comment lookup outcome visible.
   *
   * ```sql
   * SELECT id, "Foo" AS bar FROM tq WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getQuotedColumnWithAlias(id: Int, mapper: (id: Int, bar: String) -> T): T

  /**
   * Quoted column reference WITH an alias: originalName must resolve through the quoted column's
   * own name ("Foo"), not through the alias ("bar") pgjdbc's own ResultSetMetaData.getColumnName
   * reports instead when AS is used -- otherwise comment lookup for "Foo" fails outright, since no
   * column is actually named "bar". A second column is selected so a data class (with @property
   * comment lines) is generated instead of a bare scalar, making the comment lookup outcome visible.
   *
   * ```sql
   * SELECT id, "Foo" AS bar FROM tq WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getQuotedColumnWithAlias(id: Int): GetQuotedColumnWithAlias = getQuotedColumnWithAlias(id, ::GetQuotedColumnWithAlias)

  /**
   * Quoted column reference containing a space, with no alias. A second column is selected for the
   * same reason as above -- so the comment lookup outcome shows up as a @property line.
   *
   * ```sql
   * SELECT id, "My Col" FROM tq WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getQuotedColumnWithSpace(id: Int, mapper: (id: Int, `My Col`: String?) -> T): T

  /**
   * Quoted column reference containing a space, with no alias. A second column is selected for the
   * same reason as above -- so the comment lookup outcome shows up as a @property line.
   *
   * ```sql
   * SELECT id, "My Col" FROM tq WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getQuotedColumnWithSpace(id: Int): GetQuotedColumnWithSpace = getQuotedColumnWithSpace(id, ::GetQuotedColumnWithSpace)

  /**
   * A trailing "--" comment on the query's OWN last line, with the terminating ";" appended to that
   * same comment line. Both columns are NOT NULL per schema, unaffected by any join, so this pins
   * the query analyzer's own nullability probe still succeeding rather than degrading every column
   * to nullable.
   *
   * ```sql
   * SELECT id, title FROM book
   * -- only rows with a title
   * ```
   */
  public fun <T : Any> getBooksWithTrailingComment(mapper: (id: Int, title: String) -> T): Many<T>

  /**
   * A trailing "--" comment on the query's OWN last line, with the terminating ";" appended to that
   * same comment line. Both columns are NOT NULL per schema, unaffected by any join, so this pins
   * the query analyzer's own nullability probe still succeeding rather than degrading every column
   * to nullable.
   *
   * ```sql
   * SELECT id, title FROM book
   * -- only rows with a title
   * ```
   */
  public fun getBooksWithTrailingComment(): Many<GetBooksWithTrailingComment> = getBooksWithTrailingComment(::GetBooksWithTrailingComment)

  public fun <T : Any> getBooksWithTrailingCommentDynamically(mapper: (id: Int, title: String) -> T): Query<T>

  public fun getBooksWithTrailingCommentDynamically(): Query<GetBooksWithTrailingComment> = getBooksWithTrailingCommentDynamically(::GetBooksWithTrailingComment)
}
