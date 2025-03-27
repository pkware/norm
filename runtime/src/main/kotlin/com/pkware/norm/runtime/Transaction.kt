package com.pkware.norm.runtime

import java.sql.Connection
import java.sql.SQLException
import kotlin.jvm.Throws

/**
 * A SQL transaction. Can be created through the driver via [NormDriver.newTransaction] or
 * through an implementation of [Transacter] by calling [Transacter.transaction].
 *
 * A transaction is expected never to escape the thread it is created on, or more specifically,
 * never to escape the lambda scope of [Transacter.transaction] and [Transacter.transactionWithResult].
 *
 * @param enclosingTransaction The parent transaction, if there is any.
 */
public class Transaction(
  internal val enclosingTransaction: Transaction?,
  private val connectionManager: NormDriver,
  internal val connection: Connection,
) {
  private val ownerThreadId = Thread.currentThread().threadId()

  internal var successful: Boolean = false

  internal var childrenSuccessful: Boolean = true
  internal var transacter: Transacter? = null

  /**
   * @throws SQLException if a database access error occurs.
   */
  @Throws(SQLException::class)
  internal fun endTransaction() {
    checkThreadConfinement()
    return endTransaction(successful && childrenSuccessful)
  }

  /**
   * Signal to the underlying SQL driver that this transaction should be finished.
   *
   * @param successful Whether the transaction completed successfully or not.
   *
   * @throws SQLException if a database access error occurs.
   */
  @Throws(SQLException::class)
  private fun endTransaction(successful: Boolean) {
    if (enclosingTransaction == null) {
      if (successful) {
        connection.commit()
      } else {
        connection.rollback()
      }
      connection.autoCommit = true
      connection.close()
    }
    connectionManager.transaction = enclosingTransaction
  }

  internal fun checkThreadConfinement() = check(ownerThreadId == Thread.currentThread().threadId()) {
    """
		Transaction objects (`TransactionWithReturn` and `TransactionWithoutReturn`) must be used
		only within the transaction lambda scope.
    """.trimIndent()
  }
}
