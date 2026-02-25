package norm

import java.sql.Connection
import javax.sql.DataSource

/**
 * Provides [Connection] instances for executing SQL operations.
 *
 * This is the primary integration point for frameworks like Micronaut and Spring. Framework-specific
 * implementations obtain connections from the framework's connection management, ensuring that Norm queries
 * automatically participate in framework-managed transactions (e.g., `@Transactional` scopes).
 *
 * Two methods are provided for different connection lifecycle needs:
 * - [withConnection] for operations that complete within a single callback (the common case)
 * - [borrowConnection] for operations that need the connection to outlive the method call (lazy streaming)
 *
 * For standalone use without a framework, construct via the [ConnectionProvider] factory function:
 * ```kotlin
 * val provider = ConnectionProvider(dataSource)
 * ```
 */
public interface ConnectionProvider {

  /**
   * Executes [block] with a [Connection], then releases the connection.
   *
   * The connection must not be retained or used after [block] returns.
   *
   * @param block The operation to run against the connection.
   * @return The result produced by [block].
   */
  public fun <R> withConnection(block: (Connection) -> R): R

  /**
   * Borrows a [Connection] for extended use beyond a single callback scope.
   *
   * The caller **must** call [BorrowedConnection.close] when finished to release the connection back to
   * the pool or framework. Failure to do so will leak connections.
   *
   * This method exists primarily for lazy streaming ([Many.stream]), where JDBC resources must remain
   * open while the caller iterates results.
   *
   * @return A [BorrowedConnection] wrapping the connection and its release action.
   */
  public fun borrowConnection(): BorrowedConnection
}

/**
 * Creates a [ConnectionProvider] backed by a [DataSource].
 *
 * Each call to [ConnectionProvider.withConnection] obtains a new connection from the [dataSource] and closes
 * it when the block completes. Each call to [ConnectionProvider.borrowConnection] obtains a new connection
 * that the caller must close.
 *
 * This is the appropriate [ConnectionProvider] for standalone use without a framework.
 */
public fun ConnectionProvider(dataSource: DataSource): ConnectionProvider = object : ConnectionProvider {
  override fun <R> withConnection(block: (Connection) -> R): R = dataSource.connection.use(block)

  override fun borrowConnection(): BorrowedConnection {
    val connection = dataSource.connection
    return BorrowedConnection(connection) { connection.close() }
  }
}
