package norm

import java.sql.SQLException
import kotlin.jvm.Throws

/**
 * Base class for generated query classes that provides [Transactable] without a framework.
 *
 * Requires a [TransactionalConnectionProvider]. Passing any other [ConnectionProvider] throws
 * [IllegalStateException] at construction time.
 */
public abstract class RealTransactable(connectionProvider: ConnectionProvider) : Transactable {

  private val connectionProvider: TransactionalConnectionProvider =
    connectionProvider as? TransactionalConnectionProvider
      ?: error(
        "This ConnectionProvider does not support Norm-managed transactions. " +
          "Use TransactionalConnectionProvider for standalone transaction support, " +
          "or use your framework's @Transactional annotation.",
      )

  @Throws(SQLException::class, IllegalStateException::class)
  override fun transaction(readOnly: Boolean, body: TransactionScope.() -> Unit) {
    connectionProvider.transaction(readOnly, body)
  }

  @Throws(SQLException::class, IllegalStateException::class)
  override fun <R> transactionWithResult(readOnly: Boolean, body: TransactionScope.() -> R): R =
    connectionProvider.transactionWithResult(readOnly, body)
}
