package norm

import org.intellij.lang.annotations.Language
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.util.stream.Stream
import java.util.stream.StreamSupport
import javax.sql.DataSource
import kotlin.jvm.Throws

/**
 * Maintains connections to an underlying SQL database and provides APIs for managing transactions
 * and executing SQL statements.
 *
 * A transaction is expected never to escape the thread it is created on, or more specifically,
 * never to escape the lambda scope of [Transacter.transaction] and [Transacter.transactionWithResult].
 */
public class NormDriver(private val dataSource: DataSource) {

  // TODO ScopedValue instead of ThreadLocal
  private val transactions = ThreadLocal<Transaction>()

  internal var transaction: Transaction?
    get() = transactions.get()
    set(value) {
      transactions.set(value)
    }

  private fun Connection.beginTransaction() {
    check(autoCommit) {
      """
			Expected autoCommit to be true by default. For compatibility with NORM make sure it is
			set to true when returning a connection from [JdbcDriver.getConnection()]
      """.trimIndent()
    }
    autoCommit = false
  }

  /**
   * Returns a [Connection] and handler which closes the connection after the transaction finished.
   *
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   */
  @Throws(SQLException::class, SQLTimeoutException::class)
  public fun connectionAndClose(): Pair<Connection, () -> Unit> {
    val enclosing = transaction
    return if (enclosing != null) {
      enclosing.connection to {}
    } else {
      val connection = dataSource.connection
      return connection to { connection.close() }
    }
  }

  /**
   * Start a new [Transaction] on the database.
   *
   * This call will block until a transaction can be started.
   *
   * See the [NormDriver] class documentation for threading specifics.
   *
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   */
  @Throws(SQLException::class, SQLTimeoutException::class)
  internal fun newTransaction(): Transaction {
    val enclosing = transaction
    val connection = enclosing?.connection ?: dataSource.connection

    @Suppress("UseLet") // Let can't help here. This is a detekt false positive.
    val savepoint = if (enclosing != null) connection.setSavepoint() else null
    val transaction = Transaction(enclosing, this, connection, savepoint)
    this.transaction = transaction

    if (enclosing == null) {
      connection.beginTransaction()
    }

    return transaction
  }

  /**
   * Executes a SQL statement.
   *
   * @param sql to execute.
   * @param action to execute with the [PreparedStatement].
   * @param RowType Type to return.
   *
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   */
  @Throws(SQLException::class, SQLTimeoutException::class)
  public fun <RowType> execute(@Language("PostgreSQL") sql: String, action: PreparedStatement.() -> RowType): RowType {
    val (connection, onClose) = connectionAndClose()
    try {
      return connection.prepareStatement(sql).use(action)
    } finally {
      onClose()
    }
  }

  /**
   * Executes a query that returns exactly 1 row.
   *
   * @param sql to execute.
   * @param rowReader Expression to extract an [RowType] from the [ResultSet].
   * @param queryBinder Expression to populate and prepare the [PreparedStatement]. `null` if no changes need to be made
   * to the [PreparedStatement], such as when not providing query arguments.
   * @param RowType Type to return.
   * @return A single [RowType].
   *
   * @throws IllegalStateException if the query returns 0 results or more than one result.
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   *
   * @see queryMany if multiple results should be allowed.
   */
  @Throws(IllegalStateException::class, SQLException::class, SQLTimeoutException::class)
  public fun <RowType> queryOne(
    @Language("PostgreSQL") sql: String,
    rowReader: (ResultSet) -> RowType,
    queryBinder: (PreparedStatement.() -> Unit)? = null,
  ): RowType = execute(sql) {
    if (queryBinder != null) queryBinder(this)
    executeQuery().use { resultSet ->
      check(resultSet.next()) { "No results returned for $sql" }
      val result = rowReader(resultSet)
      check(!resultSet.next()) { "ResultSet returned more than 1 row for $sql" }
      result
    }
  }

  /**
   * Prepares a [Many] for loading multiple rows from a query.
   *
   * This function _does not_ execute the query.
   *
   * @param sql to execute.
   * @param rowReader Expression to extract an [RowType] from the [ResultSet].
   * @param queryBinder Expression to populate and prepare the [PreparedStatement].
   * @param RowType Type to return.
   * @return the deferred execution of the [sql] query.
   *
   * @see queryOne if a single result must exist.
   */
  public fun <RowType> queryMany(
    @Language("PostgreSQL") sql: String,
    rowReader: ResultSet.() -> RowType,
    queryBinder: (PreparedStatement.() -> Unit)? = null,
  ): Many<RowType> = JdbcMany(sql, rowReader, queryBinder)

  /**
   * Executes a SQL statement, returning the number of modified rows.
   *
   * @param sql to execute.
   * @param queryBinder Expression to populate and prepare the [PreparedStatement].
   * @return The number of rows updated.
   *
   * @throws SQLException if a database access error occurs.
   * @throws SQLTimeoutException when the driver has determined that the timeout value specified by the
   * `setLoginTimeout` method has been exceeded and has at least tried to cancel the current database connection
   * attempt.
   */
  @Throws(SQLException::class, SQLTimeoutException::class)
  public fun executeRows(
    @Language("PostgreSQL") sql: String,
    queryBinder: (PreparedStatement.() -> Unit)? = null,
  ): Int = execute(sql) {
    if (queryBinder != null) queryBinder(this)
    executeUpdate()
  }

  /**
   * Prepares a [Query] for executing a dynamic query.
   *
   * This function _does not_ execute the query.
   *
   * Unlike other methods in this class, this function doesn't take a `queryBinder`. That's because building a dynamic
   * query with arbitrary parameters in addition to parameters defined statically is extremely brittle. It requires
   * developers to look in multiple locations for the right combination of argument names, positions, and ordering.
   * Queries that are intended for dynamic use should define only the `SELECT` clause.
   *
   * @param sql to execute.
   * @param rowReader Expression to extract an [RowType] from the [ResultSet].
   * @param RowType Type to return.
   * @return the deferred execution of the [sql] query.
   */
  public fun <RowType> dynamic(
    @Language("PostgreSQL") sql: String,
    rowReader: ResultSet.() -> RowType,
  ): Query<RowType> = BindingQuery(sql, rowReader, this)

  /**
   * @param sql to execute.
   * @param rowReader Expression to extract an [RowType] from the [ResultSet].
   * @param queryBinder Expression to populate and prepare the [PreparedStatement].
   * @param RowType Type to return.
   */
  private inner class JdbcMany<RowType>(
    @Language("PostgreSQL") private val sql: String,
    private val rowReader: ResultSet.() -> RowType,
    private val queryBinder: (PreparedStatement.() -> Unit)? = null,
  ) : Many<RowType> {

    override fun stream(): Stream<RowType> {
      val (connection, onClose) = connectionAndClose()
      val statement = connection.prepareStatement(sql)
      queryBinder?.let { it(statement) }
      val resultSet = statement.executeQuery()
      val closeAll = {
        resultSet.close()
        statement.close()
        onClose()
      }
      val spliterator = ResultSetSpliterator(resultSet, closeAll, rowReader)
      val stream = StreamSupport.stream(spliterator, false)
      return stream.onClose(closeAll)
    }

    override fun <C : MutableCollection<RowType>> collection(factory: () -> C): C {
      val collection = factory()
      execute(sql) {
        queryBinder?.let { it(this) }
        executeQuery().use { resultSet ->
          while (resultSet.next()) {
            collection.add(rowReader(resultSet))
          }
        }
      }
      return collection
    }

    override fun firstOrNull(): RowType? = execute(sql) {
      queryBinder?.let { it(this) }
      executeQuery().use { resultSet ->
        if (!resultSet.next()) return@use null
        val result = rowReader(resultSet)
        check(!resultSet.next()) { "ResultSet returned more than 1 row for $sql" }
        result
      }
    }
  }
}
