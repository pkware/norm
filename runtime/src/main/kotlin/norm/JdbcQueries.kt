package norm

import org.postgresql.jdbc.PgConnection
import javax.sql.DataSource

public abstract class JdbcQueries(protected val dataSource: DataSource) : Transaction {

  protected val connection: PgConnection
    get() {
      val transaction = transactions.get()
      if (transaction == null) return dataSource.connection as PgConnection
      return transaction.connection
    }

  override fun transaction(block: (Transaction) -> Unit) {
    var transaction = transactions.get()
    if (transaction != null) return transaction.transaction(block)

    val connection = dataSource.connection as PgConnection
    var autoCommit: Boolean = connection.autoCommit
    // TODO Is this necessary? Probably good to have while we're also working with Hibernate
    connection.autoCommit = false
    transaction = ConnectionTransaction(connection).also(transactions::set)
    try {
      try {
        block(transaction)
        connection.commit()
      } finally {
        connection.rollback()
      }
    } finally {
      connection.autoCommit = autoCommit
    }
  }

  override fun <ReturnType> transactionWithResult(block: (Transaction) -> ReturnType): ReturnType {
    var transaction = transactions.get()
    if (transaction != null) return transaction.transactionWithResult(block)

    val connection = dataSource.connection as PgConnection
    var autoCommit: Boolean = connection.autoCommit
    // TODO Is this necessary? Probably good to have while we're also working with Hibernate
    connection.autoCommit = false
    transaction = ConnectionTransaction(connection).also(transactions::set)
    try {
      try {
        val result = block(transaction)
        connection.commit()
        return result
      } finally {
        connection.rollback()
      }
    } finally {
      connection.autoCommit = autoCommit
    }
  }

  internal companion object {
    internal val transactions = ThreadLocal<ConnectionTransaction>()
  }
}
