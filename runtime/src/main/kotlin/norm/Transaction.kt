package norm

import java.sql.Connection
import java.sql.Savepoint

/**
 * Internal representation of an active transaction on the current thread.
 *
 * @property connection The JDBC connection used for this transaction.
 * @property savepoint The savepoint created for a nested transaction, or `null` for the outermost
 *   transaction.
 * @property parent The enclosing [Transaction], or `null` if this is the outermost transaction.
 * @property readOnly Whether the transaction is read-only.
 * @property poisoned Whether this transaction has been poisoned by an exception in a nested
 *   transaction. A poisoned transaction is rolled back instead of committed when it completes.
 */
internal class Transaction(
  val connection: Connection,
  val savepoint: Savepoint?,
  val parent: Transaction?,
  val readOnly: Boolean,
  var poisoned: Boolean = false,
)
