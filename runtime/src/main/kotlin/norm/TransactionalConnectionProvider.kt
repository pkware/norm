package norm

import java.sql.Connection
import java.sql.SQLException
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
 * @property dataSource The data source from which to acquire connections.
 */
public class TransactionalConnectionProvider(private val dataSource: DataSource) :
  ConnectionProvider,
  Transactable {

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

  @Throws(SQLException::class, IllegalStateException::class)
  override fun transaction(readOnly: Boolean, body: TransactionScope.() -> Unit) {
    try {
      transactionWithResult(readOnly, body)
    } catch (_: RollbackException) {
      // Explicit rollback already happened inside transactionWithResult; a void transaction has
      // nothing to return, so the signal ends here.
    }
  }

  @Throws(SQLException::class, IllegalStateException::class)
  override fun <R> transactionWithResult(readOnly: Boolean, body: TransactionScope.() -> R): R {
    val parent = activeTransaction.get()
    return if (parent != null) {
      executeNested(parent, readOnly, body)
    } else {
      executeOutermost(readOnly, body)
    }
  }

  private fun <R> executeOutermost(readOnly: Boolean, body: TransactionScope.() -> R): R {
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

  private fun <R> executeNested(parent: Transaction, readOnly: Boolean, body: TransactionScope.() -> R): R {
    val tx = beginNested(parent, readOnly)
    try {
      val connection = tx.connection
      val savepoint = connection.setSavepoint()
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
      if (tx.poisoned) {
        connection.rollback(savepoint)
        parent.poisoned = true
      } else {
        connection.releaseSavepoint(savepoint)
      }
      return result
    } finally {
      activeTransaction.set(parent)
    }
  }

  private fun beginNested(parent: Transaction, readOnly: Boolean): Transaction {
    check(!parent.readOnly || readOnly) {
      "Cannot open a read-write transaction nested inside a read-only transaction"
    }
    val tx = Transaction(
      connection = parent.connection,
      readOnly = readOnly,
    )
    activeTransaction.set(tx)
    return tx
  }

  private class TransactionScopeImpl : TransactionScope {
    override fun rollback(): Nothing = throw RollbackException()
  }
}
