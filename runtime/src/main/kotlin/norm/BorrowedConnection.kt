package norm

import java.sql.Connection

/**
 * A [Connection] borrowed from a [ConnectionProvider] that the caller is responsible for releasing.
 *
 * Call [close] when finished with the connection to return it to the pool or framework.
 * Implements [AutoCloseable] for use with Kotlin's [use] or Java's try-with-resources.
 *
 * @property connection The borrowed JDBC connection.
 */
public class BorrowedConnection(public val connection: Connection, private val release: () -> Unit) : AutoCloseable {
  override fun close(): Unit = release()
}
