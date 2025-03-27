package norm

import org.postgresql.jdbc.PgConnection

public interface Transaction {
  public fun transaction(block: (Transaction) -> Unit)
  public fun <ReturnType> transactionWithResult(block: (Transaction) -> ReturnType): ReturnType

  // TODO This is basically an escape hatch to allow existing multi threaded code to share a transaction across threads. In the future we'd like to use ScopedValue instead.
  public fun attachToThread(transaction: Transaction)
}

// TODO Consider an alternative API where Transaction and a new SavePoint class are AutoCloseable and we use `use` instead of passing blocks. That makes a little more sense in multi-threaded scenarios I think.
internal class ConnectionTransaction(internal val connection: PgConnection) : Transaction {

  override fun transaction(block: (Transaction) -> Unit) {
    val savepoint = connection.setSavepoint()
    try {
      block(this)
    } catch (e: Exception) {
      connection.rollback(savepoint)
      throw e
    } finally {
      connection.releaseSavepoint(savepoint)
    }
  }

  override fun <ReturnType> transactionWithResult(block: (Transaction) -> ReturnType): ReturnType {
    val savepoint = connection.setSavepoint()
    try {
      return block(this)
    } catch (e: Exception) {
      connection.rollback(savepoint)
      throw e
    } finally {
      connection.releaseSavepoint(savepoint)
    }
  }

  public override fun attachToThread(transaction: Transaction) {
    if (transaction is ConnectionTransaction) JdbcQueries.transactions.set(transaction)
  }
}
