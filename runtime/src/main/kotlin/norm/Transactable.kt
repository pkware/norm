package norm

import java.sql.SQLException
import kotlin.jvm.Throws

/**
 * Explicit transaction management for Norm queries.
 *
 * Transactions are read-only by default; pass `readOnly = false` for writes. The JDBC driver
 * enforces read-only mode — writes in a read-only transaction throw a database error.
 * Connection-routing drivers (e.g., AWS JDBC Driver) use the read-only flag to route queries
 * to read replicas.
 *
 * Implemented by generated query classes when no framework is configured. For Micronaut or
 * Spring, use the framework's `@Transactional` annotation instead.
 */
public interface Transactable {

  /**
   * Runs [body] in a transaction.
   *
   * All queries in [body] share one connection. On success the transaction commits; on exception
   * it rolls back. Call [TransactionScope.rollback] for a clean rollback without throwing.
   *
   * Nested calls use savepoints automatically. An exception in a nested call rolls back its
   * savepoint and poisons the enclosing transaction. An explicit [TransactionScope.rollback] in
   * a nested call rolls back only that savepoint — the enclosing transaction is unaffected.
   *
   * Nesting a read-write transaction inside a read-only transaction throws [IllegalStateException].
   *
   * @param readOnly Whether the transaction should be read-only.
   * @param body The operations to run within the transaction.
   * @throws SQLException if a database access error occurs.
   * @throws IllegalStateException if a read-write transaction is nested inside a read-only one.
   */
  @Throws(SQLException::class, IllegalStateException::class)
  public fun transaction(readOnly: Boolean = true, body: TransactionScope.() -> Unit)

  /**
   * Runs [body] in a transaction and returns its result.
   *
   * Behaves like [transaction] but returns a value. Call [TransactionScope.rollback] to roll
   * back — `RollbackException` propagates to the caller since there is no result to return.
   * In a nested call, the enclosing transaction is not poisoned by an explicit rollback.
   *
   * Nesting a read-write transaction inside a read-only one throws [IllegalStateException].
   *
   * @param readOnly Whether the transaction should be read-only.
   * @param body The operations to run within the transaction.
   * @return The result produced by [body].
   * @throws SQLException if a database access error occurs.
   * @throws IllegalStateException if a read-write transaction is nested inside a read-only one.
   */
  @Throws(SQLException::class, IllegalStateException::class)
  public fun <R> transactionWithResult(readOnly: Boolean = true, body: TransactionScope.() -> R): R
}
