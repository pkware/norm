package norm

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
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
 * - [borrowReleaseCount] — number of times the [BorrowedConnection] release callback fired. Used by
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

  /** Number of times [ConnectionProvider.borrowConnection]'s release callback was invoked. */
  private var borrowReleaseCount = 0

  /** Produces the result of [Connection.prepareStatement]; tests replace it to make preparation fail. */
  private var prepareStatement: () -> PreparedStatement = { preparedStatement }

  private lateinit var driver: NormDriver

  @BeforeEach
  fun setup() {
    withConnectionReleased = false
    borrowReleaseCount = 0
    prepareStatement = { preparedStatement }
    whenever(connection.prepareStatement(any<String>())).thenAnswer { prepareStatement() }

    val provider = object : ConnectionProvider {
      override fun <R> withConnection(block: (Connection) -> R): R {
        try {
          return block(connection)
        } finally {
          withConnectionReleased = true
        }
      }

      override fun borrowConnection(): BorrowedConnection = BorrowedConnection(connection) {
        borrowReleaseCount++
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

    @Test
    fun `zero rows throws`() {
      whenever(preparedStatement.executeQuery()).thenReturn(resultSet)
      whenever(resultSet.next()).thenReturn(false)

      assertFailure {
        driver.queryOne("SELECT name FROM users", { it.getString(1) })
      }.isInstanceOf<IllegalStateException>().hasMessage("No results returned for SELECT name FROM users")
    }

    @Test
    fun `two rows throws`() {
      whenever(preparedStatement.executeQuery()).thenReturn(resultSet)
      whenever(resultSet.next()).thenReturn(true, true)
      whenever(resultSet.getString(1)).thenReturn("Alice")

      assertFailure {
        driver.queryOne("SELECT name FROM users", { it.getString(1) })
      }.isInstanceOf<IllegalStateException>().hasMessage(
        "ResultSet returned more than 1 row for SELECT name FROM users",
      )
    }

    @Test
    fun `single row mapped to null returns null`() {
      whenever(preparedStatement.executeQuery()).thenReturn(resultSet)
      whenever(resultSet.next()).thenReturn(true, false)
      whenever(resultSet.getString(1)).thenReturn(null)

      val result = driver.queryOne("SELECT name FROM users", { it.getString(1) })

      assertThat(result).isNull()
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

      assertThat(borrowReleaseCount).isEqualTo(1)
    }

    @Test
    fun `consumed stream closes each resource once when closed`() {
      whenever(preparedStatement.executeQuery()).thenReturn(resultSet)
      whenever(resultSet.next()).thenReturn(true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice")

      val stream = driver.queryMany("SELECT name FROM users", { getString(1) }).stream()
      stream.toList()
      stream.close()

      assertThat(borrowReleaseCount).isEqualTo(1)
      verify(resultSet).close()
      verify(preparedStatement).close()
    }

    @Test
    fun `prepareStatement failure releases the borrowed connection`() {
      val failure = SQLException("prepare failed")
      prepareStatement = { throw failure }

      assertFailure {
        driver.queryMany("SELECT name FROM users", { getString(1) }).stream()
      }.isEqualTo(failure)

      assertThat(borrowReleaseCount).isEqualTo(1)
    }

    @Test
    fun `binder failure closes the statement and releases the borrowed connection`() {
      val failure = SQLException("bind failed")

      assertFailure {
        driver.queryMany("SELECT name FROM users", { getString(1) }) { throw failure }.stream()
      }.isEqualTo(failure)

      verify(preparedStatement).close()
      assertThat(borrowReleaseCount).isEqualTo(1)
    }

    @Test
    fun `executeQuery failure closes the statement and releases the borrowed connection`() {
      val failure = SQLException("query failed")
      whenever(preparedStatement.executeQuery()).thenThrow(failure)

      assertFailure {
        driver.queryMany("SELECT name FROM users", { getString(1) }).stream()
      }.isEqualTo(failure)

      verify(preparedStatement).close()
      assertThat(borrowReleaseCount).isEqualTo(1)
    }

    @Test
    fun `cleanup failure is suppressed and the original exception propagates`() {
      val failure = SQLException("query failed")
      val closeFailure = SQLException("close failed")
      whenever(preparedStatement.executeQuery()).thenThrow(failure)
      whenever(preparedStatement.close()).thenThrow(closeFailure)

      val thrown = runCatching {
        driver.queryMany("SELECT name FROM users", { getString(1) }).stream()
      }.exceptionOrNull()

      assertThat(thrown).isNotNull().isEqualTo(failure)
      assertThat(thrown!!.suppressed.toList()).containsExactly(closeFailure)
      assertThat(borrowReleaseCount).isEqualTo(1)
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
