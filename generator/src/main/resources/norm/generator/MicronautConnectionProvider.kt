package packages.placeholder

import io.micronaut.context.annotation.Requires
import io.micronaut.data.connection.ConnectionDefinition
import io.micronaut.data.connection.ConnectionOperations
import io.micronaut.data.connection.SynchronousConnectionManager
import jakarta.inject.Singleton
import norm.BorrowedConnection
import norm.ConnectionProvider
import java.sql.Connection
import javax.sql.DataSource

@Singleton
@Requires(missingBeans = [ConnectionProvider::class])
@Requires(beans = [DataSource::class])
public class MicronautConnectionProvider(
  private val connectionOperations: ConnectionOperations<Connection>,
  private val connectionManager: SynchronousConnectionManager<Connection>,
) : ConnectionProvider {
  override fun <R> withConnection(block: (Connection) -> R): R =
    connectionOperations.execute(ConnectionDefinition.DEFAULT) { status -> block(status.connection) }

  /**
   * Borrows a connection that stays open until the returned [BorrowedConnection] is closed.
   * Micronaut `ConnectionCustomizer`s do not apply to streamed queries.
   */
  override fun borrowConnection(): BorrowedConnection {
    val status = connectionManager.getConnection(ConnectionDefinition.DEFAULT)
    return BorrowedConnection(status.connection) { connectionManager.complete(status) }
  }
}
