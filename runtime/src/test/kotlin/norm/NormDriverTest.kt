package norm

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Tests that [NormDriver] correctly delegates to [ConnectionProvider] and releases connections.
 *
 * The [ConnectionProvider] used in these tests tracks whether each connection-acquisition path was properly released:
 * - [withConnectionReleased] — set when [ConnectionProvider.withConnection] returns (via `finally`). Used by
 *   [queryOne][NormDriver.queryOne] and [executeRows][NormDriver.executeRows].
 * - [borrowReleased] — set when the [BorrowedConnection] release callback fires. Used by
 *   [stream][Many.stream], where the connection must outlive the method call.
 */
@ExtendWith(MockitoExtension::class)
class NormDriverTest {

  @Mock
  lateinit var connection: Connection

  @Mock
  lateinit var preparedStatement: PreparedStatement

  @Mock
  lateinit var resultSet: ResultSet

  /** Whether [ConnectionProvider.withConnection] released the connection (via its `finally` block). */
  private var withConnectionReleased = false

  /** Whether [ConnectionProvider.borrowConnection]'s release callback was invoked. */
  private var borrowReleased = false

  private lateinit var driver: NormDriver

  @BeforeEach
  fun setup() {
    withConnectionReleased = false
    borrowReleased = false
    whenever(connection.prepareStatement(any<String>())).thenReturn(preparedStatement)

    val provider = object : ConnectionProvider {
      override fun <R> withConnection(block: (Connection) -> R): R {
        try {
          return block(connection)
        } finally {
          withConnectionReleased = true
        }
      }

      override fun borrowConnection(): BorrowedConnection = BorrowedConnection(connection) {
        borrowReleased = true
      }
    }
    driver = NormDriver(provider)
  }

  /** Single-row queries use [ConnectionProvider.withConnection] — connection is scoped to the lambda. */
  @Nested
  inner class QueryOne {
    @Test
    fun `returns mapped result`() {
      whenever(preparedStatement.executeQuery()).thenReturn(resultSet)
      whenever(resultSet.next()).thenReturn(true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice")

      val result = driver.queryOne("SELECT name FROM users WHERE id = ?", { it.getString(1) }) {
        setInt(1, 42)
      }

      assertThat(result).isEqualTo("Alice")
    }

    @Test
    fun `connection is released after completion`() {
      whenever(preparedStatement.executeQuery()).thenReturn(resultSet)
      whenever(resultSet.next()).thenReturn(true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice")

      driver.queryOne("SELECT name FROM users", { it.getString(1) })

      assertThat(withConnectionReleased).isTrue()
    }

    @Test
    fun `connection is released when query throws`() {
      whenever(preparedStatement.executeQuery()).thenThrow(SQLException("connection lost"))

      assertFailure {
        driver.queryOne("SELECT name FROM users", { it.getString(1) })
      }

      assertThat(withConnectionReleased).isTrue()
    }
  }

  /** Write operations use [ConnectionProvider.withConnection] — connection is scoped to the lambda. */
  @Nested
  inner class ExecuteRows {
    @Test
    fun `returns affected row count`() {
      whenever(preparedStatement.executeUpdate()).thenReturn(3)

      val result = driver.executeRows("UPDATE users SET active = true")

      assertThat(result).isEqualTo(3)
    }

    @Test
    fun `connection is released after completion`() {
      whenever(preparedStatement.executeUpdate()).thenReturn(1)

      driver.executeRows("DELETE FROM users WHERE id = ?") { setInt(1, 1) }

      assertThat(withConnectionReleased).isTrue()
    }
  }

  /**
   * Streaming queries use [ConnectionProvider.borrowConnection] — the connection outlives the method call
   * because the caller consumes the [java.util.stream.Stream] lazily. The caller must close the stream to
   * release the connection.
   */
  @Nested
  inner class StreamTests {
    @Test
    fun `lazily reads ResultSet rows`() {
      whenever(preparedStatement.executeQuery()).thenReturn(resultSet)
      whenever(resultSet.next()).thenReturn(true, true, true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice", "Bob", "Charlie")

      val result = driver.queryMany("SELECT name FROM users", { getString(1) }).stream().toList()

      assertThat(result).containsExactly("Alice", "Bob", "Charlie")
    }

    @Test
    fun `borrowed connection is released when stream is closed`() {
      whenever(preparedStatement.executeQuery()).thenReturn(resultSet)

      driver.queryMany("SELECT name FROM users", { getString(1) }).stream().close()

      assertThat(borrowReleased).isTrue()
    }
  }

  /** Verifies the convenience [NormDriver] constructor that accepts a raw [DataSource]. */
  @Nested
  inner class DataSourceConstructor {
    @Mock
    lateinit var dataSource: DataSource

    @Test
    fun `produces working driver`() {
      whenever(dataSource.connection).thenReturn(connection)
      whenever(preparedStatement.executeQuery()).thenReturn(resultSet)
      whenever(resultSet.next()).thenReturn(true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice")

      val dataSourceDriver = NormDriver(dataSource)
      val result = dataSourceDriver.queryOne("SELECT name FROM users", { it.getString(1) })

      assertThat(result).isEqualTo("Alice")
    }
  }
}
