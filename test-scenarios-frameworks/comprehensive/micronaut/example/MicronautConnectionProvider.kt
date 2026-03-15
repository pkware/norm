package example

import io.micronaut.context.annotation.Requires
import io.micronaut.data.connection.ConnectionDefinition
import io.micronaut.data.connection.ConnectionOperations
import jakarta.inject.Singleton
import norm.BorrowedConnection
import norm.ConnectionProvider
import java.sql.Connection
import javax.sql.DataSource

@Singleton
@Requires(missingBeans = [ConnectionProvider::class])
@Requires(beans = [DataSource::class])
public class MicronautConnectionProvider(private val connectionOperations: ConnectionOperations<Connection>) :
  ConnectionProvider {
  override fun <R> withConnection(block: (Connection) -> R): R =
    connectionOperations.execute(ConnectionDefinition.DEFAULT) { status -> block(status.connection) }

  override fun borrowConnection(): BorrowedConnection {
    val existing = connectionOperations.findConnectionStatus()
    if (existing.isPresent) {
      return BorrowedConnection(existing.get().connection) {}
    }
    val status = connectionOperations.execute(ConnectionDefinition.DEFAULT) { it }
    return BorrowedConnection(status.connection) {}
  }
}
