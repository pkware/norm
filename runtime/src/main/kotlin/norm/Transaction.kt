package norm

import java.sql.Connection

/**
 * Internal representation of an active transaction on the current thread.
 *
 * @property connection the JDBC connection used for this transaction.
 * @property readOnly whether the transaction is read-only.
 * @property poisoned whether an exception escaped a transaction nested within this one. A poisoned transaction rolls
 *   back when its body returns, and a poisoned nested transaction also poisons its parent.
 */
internal class Transaction(val connection: Connection, val readOnly: Boolean, var poisoned: Boolean = false)
