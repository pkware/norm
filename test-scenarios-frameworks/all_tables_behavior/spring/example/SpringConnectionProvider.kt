package example

import norm.BorrowedConnection
import norm.ConnectionProvider
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

@Component
public class SpringConnectionProvider(private val dataSource: DataSource) : ConnectionProvider {
  override fun <R> withConnection(block: (Connection) -> R): R {
    val connection = DataSourceUtils.getConnection(dataSource)
    try {
      return block(connection)
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource)
    }
  }

  override fun borrowConnection(): BorrowedConnection {
    val connection = DataSourceUtils.getConnection(dataSource)
    return BorrowedConnection(connection) { DataSourceUtils.releaseConnection(connection, dataSource) }
  }
}
