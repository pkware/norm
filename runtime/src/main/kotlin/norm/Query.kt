package norm

import java.sql.SQLException
import java.sql.SQLTimeoutException

/**
 * A deferred SQL query that supports dynamic composition and parameter binding.
 *
 * Queries are obtained from generated query class methods and executed lazily—no database
 * interaction occurs until a terminal operation ([single], [list], [stream], etc.) is invoked.
 * This allows queries to be composed incrementally by appending SQL fragments and binding parameters.
 *
 * ## Parameter Binding
 *
 * Two styles of parameter binding are supported, but they **cannot be mixed** within a single query:
 *
 * - **Named parameters**: Use `:paramName` syntax in SQL and bind with [bind(name, value)][bind].
 *   Recommended for readability and maintainability.
 *
 * - **Positional parameters**: Use `?` placeholders in SQL and bind with [bind(value)][bind].
 *   Suitable for simple cases where intent is obvious.
 *
 * ## Example Usage
 *
 * ```kotlin
 * // Named parameters (recommended)
 * val authors = queries.listAuthorsDynamically().run {
 *     append(" WHERE name LIKE :pattern")
 *     bind("pattern", "%George%")
 * }.list()
 *
 * // Positional parameters
 * val author = queries.getAuthorDynamically()
 *     .append(" WHERE id = ?")
 *     .bind(authorId)
 *     .single()
 * ```
 *
 * @param RowType The type of object produced from each row of the result set.
 */
public interface Query<RowType> : Many<RowType> {

  /**
   * Executes the query and returns the only result.
   *
   * @return A single [RowType].
   *
   * @throws IllegalStateException if the query returns 0 results or more than one result.
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   */
  @Throws(IllegalStateException::class, SQLException::class, SQLTimeoutException::class)
  public fun single(): RowType

  /**
   * Appends the given SQL to the query.
   *
   * @return This query.
   */
  public fun append(sql: String): Query<RowType>

  /**
   * Binds the given value to the given name in the query.
   *
   * Note that positional and named arguments cannot be mixed. Unused arguments are ignored.
   * Overwriting a previously supplied argument is supported.
   *
   * @param name The name of the parameter to bind.
   * @param value The value to bind.
   * @return This query.
   * @throws IllegalStateException if positional arguments have been supplied.
   */
  @Throws(IllegalStateException::class)
  public fun bind(name: String, value: Any?): Query<RowType>

  /**
   * Binds the given value positionally in the query.
   *
   * It's likely that named parameters are a better choice for readability.
   * This method is provided for use in simple cases where the intent and correctness are obvious.
   *
   * Note that positional and named arguments cannot be mixed.
   *
   * @param value The value to bind.
   * @return This query.
   * @throws IllegalStateException if named arguments have been supplied.
   */
  @Throws(IllegalStateException::class)
  public fun bind(value: Any?): Query<RowType>
}
