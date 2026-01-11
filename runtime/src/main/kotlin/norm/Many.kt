package norm

import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.util.stream.Stream
import kotlin.jvm.Throws

/**
 * Lazy-evaluation of a SQL query that potentially returns many results.
 *
 * @param RowType The type of object produced from each row of the result set.
 */
public interface Many<RowType> {
  /**
   * Consumes the underlying [ResultSet] as a [Stream].
   *
   * You must call [Stream.close] after completion, or you will leak the SQL resources. This is typically done via
   * try-with-resources, or Kotlin's [use] function.
   *
   * Prefer [list] when practical, as the lifecycle of the SQL resources is better managed.
   *
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   */
  @Throws(SQLException::class, SQLTimeoutException::class)
  public fun stream(): Stream<RowType> = list().stream()

  /**
   * Consumes the underlying [ResultSet] as a [MutableList].
   *
   * The caller receives possession of the list. It will not be modified after being returned.
   *
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   */
  @Throws(SQLException::class, SQLTimeoutException::class)
  public fun list(): MutableList<RowType> = collection(::mutableListOf)

  /**
   * Consumes the underlying [ResultSet] as a [MutableSet].
   *
   * Be cautious about using this method. The uniqueness of [RowType] may not be the same as how `DISTINCT` behaves in SQL.
   * Generally, this function is useful for simple cases such as a set of primary keys, but prefer [list] for more
   * complex [RowType]s.
   *
   * The caller receives possession of the set. It will not be modified after being returned.
   *
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   */
  @Throws(SQLException::class, SQLTimeoutException::class)
  public fun distinct(): MutableSet<RowType> = collection(::mutableSetOf)

  /**
   * Consumes the underlying [ResultSet] as a [TCollection].
   *
   * Prefer [list] when practical.
   *
   * The caller receives possession of the collection. It will not be modified after being returned.
   *
   * @param factory for producing a [TCollection].
   *
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   */
  @Throws(SQLException::class, SQLTimeoutException::class)
  public fun <TCollection : MutableCollection<RowType>> collection(factory: () -> TCollection): TCollection

  /**
   * Consumes a single [RowType] from the underlying [ResultSet].
   *
   * If the [ResultSet] has no entries, returns `null`. Note that if [RowType] is naturally nullable, you cannot
   * distinguish between a `null` [RowType] and an empty [ResultSet].
   *
   * @throws IllegalStateException if the query returns more than one result.
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   */
  @Throws(IllegalStateException::class, SQLException::class, SQLTimeoutException::class)
  public fun firstOrNull(): RowType? = list().firstOrNull()
}
