package norm

import java.sql.Connection
import java.sql.SQLException
import java.sql.Savepoint
import javax.sql.DataSource
import kotlin.jvm.Throws

/**
 * A [ConnectionProvider] that supports [Transactable] transaction management.
 *
 * Routes [withConnection] to the active transaction's connection when a transaction is running on
 * the current thread, so all queries share one connection and participate in the same JDBC
 * transaction.
 *
 * For framework-managed transactions, use the framework's `ConnectionProvider` and `@Transactional`.
 *
 * @param dataSource The data source from which to acquire connections.
 */
public class TransactionalConnectionProvider(private val dataSource: DataSource) : ConnectionProvider {

  private val activeTransaction: ThreadLocal<Transaction> = ThreadLocal()

  /**
   * Returns a connection for use within [block].
   *
   * If a transaction is active on the current thread, the transaction's connection is passed to
   * [block] so that the operation participates in the ongoing transaction. Otherwise, a new
   * connection is acquired from the [DataSource], used for [block], and then closed.
   *
   * @param block The operation to run with the connection.
   * @return The result produced by [block].
   */
  override fun <R> withConnection(block: (Connection) -> R): R {
    val tx = activeTransaction.get()
    return if (tx != null) {
      block(tx.connection)
    } else {
      dataSource.connection.use(block)
    }
  }

  /**
   * Returns a [BorrowedConnection] for extended-lifecycle use (e.g., lazy streaming).
   *
   * Inside a transaction, returns the transaction's connection with a no-op release — do not
   * close it directly. Outside a transaction, acquires a new connection from the [DataSource].
   */
  override fun borrowConnection(): BorrowedConnection {
    val tx = activeTransaction.get()
    if (tx != null) {
      // Return the transaction's connection with a no-op release. The transaction owns the
      // connection lifecycle. Callers must not call connection.close() directly on a borrowed
      // connection inside a transaction — doing so would close the transaction's connection and
      // corrupt all subsequent operations in that transaction.
      return BorrowedConnection(tx.connection) {}
    }
    val connection = dataSource.connection
    return BorrowedConnection(connection, connection::close)
  }

  /**
   * Runs [body] in a transaction. See [Transactable.transaction] for full semantics.
   *
   * @throws SQLException if a database access error occurs.
   * @throws IllegalStateException if a read-write transaction is nested inside a read-only one.
   */
  @Throws(SQLException::class, IllegalStateException::class)
  public fun transaction(readOnly: Boolean = true, body: TransactionScope.() -> Unit) {
    val parent = activeTransaction.get()
    if (parent != null) {
      executeNestedVoid(parent, readOnly, body)
    } else {
      executeOutermostVoid(readOnly, body)
    }
  }

  /**
   * Runs [body] in a transaction and returns its result. See [Transactable.transactionWithResult]
   * for full semantics.
   *
   * @throws SQLException if a database access error occurs.
   * @throws IllegalStateException if a read-write transaction is nested inside a read-only one.
   */
  @Throws(SQLException::class, IllegalStateException::class)
  public fun <R> transactionWithResult(readOnly: Boolean = true, body: TransactionScope.() -> R): R {
    val parent = activeTransaction.get()
    return if (parent != null) {
      executeNestedWithResult(parent, readOnly, body)
    } else {
      executeOutermostWithResult(readOnly, body)
    }
  }

  private fun executeOutermostVoid(readOnly: Boolean, body: TransactionScope.() -> Unit) {
    val connection = dataSource.connection
    try {
      val tx = beginOutermost(connection, readOnly)
      val scope = TransactionScopeImpl()
      try {
        scope.body()
      } catch (_: RollbackException) {
        connection.rollback()
        return
      } catch (expected: Throwable) {
        connection.rollback()
        throw expected
      }
      commitOrRollback(tx, connection)
    } finally {
      cleanupOutermost(connection)
    }
  }

  private fun <R> executeOutermostWithResult(readOnly: Boolean, body: TransactionScope.() -> R): R {
    val connection = dataSource.connection
    try {
      val tx = beginOutermost(connection, readOnly)
      val scope = TransactionScopeImpl()
      val result: R
      try {
        result = scope.body()
      } catch (expected: Throwable) {
        connection.rollback()
        throw expected
      }
      commitOrRollback(tx, connection)
      return result
    } finally {
      cleanupOutermost(connection)
    }
  }

  private fun beginOutermost(connection: Connection, readOnly: Boolean): Transaction {
    connection.autoCommit = false
    connection.isReadOnly = readOnly
    val tx = Transaction(
      connection = connection,
      savepoint = null,
      parent = null,
      readOnly = readOnly,
    )
    activeTransaction.set(tx)
    return tx
  }

  private fun commitOrRollback(tx: Transaction, connection: Connection) {
    if (tx.poisoned) {
      connection.rollback()
    } else {
      connection.commit()
    }
  }

  private fun cleanupOutermost(connection: Connection) {
    activeTransaction.remove()
    // Order is significant: both isReadOnly and autoCommit must be reset before close()
    // because pooled connections may not reset them on return to the pool.
    connection.isReadOnly = false
    connection.autoCommit = true
    connection.close()
  }

  private fun executeNestedVoid(parent: Transaction, readOnly: Boolean, body: TransactionScope.() -> Unit) {
    val (connection, savepoint) = beginNested(parent, readOnly)
    try {
      val scope = TransactionScopeImpl()
      try {
        scope.body()
      } catch (_: RollbackException) {
        connection.rollback(savepoint)
        return
      } catch (expected: Throwable) {
        connection.rollback(savepoint)
        parent.poisoned = true
        throw expected
      }
      connection.releaseSavepoint(savepoint)
    } finally {
      activeTransaction.set(parent)
    }
  }

  private fun <R> executeNestedWithResult(parent: Transaction, readOnly: Boolean, body: TransactionScope.() -> R): R {
    val (connection, savepoint) = beginNested(parent, readOnly)
    try {
      val scope = TransactionScopeImpl()
      val result: R
      try {
        result = scope.body()
      } catch (_: RollbackException) {
        // Explicit rollback: roll back this savepoint without poisoning the enclosing transaction.
        connection.rollback(savepoint)
        throw RollbackException()
      } catch (expected: Throwable) {
        connection.rollback(savepoint)
        parent.poisoned = true
        throw expected
      }
      connection.releaseSavepoint(savepoint)
      return result
    } finally {
      activeTransaction.set(parent)
    }
  }

  private fun beginNested(parent: Transaction, readOnly: Boolean): Pair<Connection, Savepoint> {
    check(!parent.readOnly || readOnly) {
      "Cannot open a read-write transaction nested inside a read-only transaction"
    }
    val connection = parent.connection
    val savepoint = connection.setSavepoint()
    val tx = Transaction(
      connection = connection,
      savepoint = savepoint,
      parent = parent,
      readOnly = readOnly,
    )
    activeTransaction.set(tx)
    return Pair(connection, savepoint)
  }

  private class TransactionScopeImpl : TransactionScope {
    override fun rollback(): Nothing = throw RollbackException()
  }
}
