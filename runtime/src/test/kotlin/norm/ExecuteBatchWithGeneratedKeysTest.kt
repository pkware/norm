package norm

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

/** Tests that [NormDriver.executeBatchWithGeneratedKeys] delegates to [ConnectionProvider.withConnection]. */
@ExtendWith(MockitoExtension::class)
class ExecuteBatchWithGeneratedKeysTest {

  @Mock
  lateinit var connection: Connection

  @Mock
  lateinit var preparedStatement: PreparedStatement

  private var withConnectionReleased = false

  private lateinit var driver: NormDriver

  @BeforeEach
  fun setup() {
    withConnectionReleased = false
    whenever(connection.prepareStatement(any<String>(), any<Array<String>>())).thenReturn(preparedStatement)

    val provider = object : ConnectionProvider {
      override fun <R> withConnection(block: (Connection) -> R): R {
        try {
          return block(connection)
        } finally {
          withConnectionReleased = true
        }
      }

      override fun borrowConnection(): BorrowedConnection = BorrowedConnection(connection) {}
    }
    driver = NormDriver(provider)
  }

  @Test
  fun `returns result from action lambda`() {
    val result = driver.executeBatchWithGeneratedKeys("INSERT INTO t (name) VALUES (?)", arrayOf("id")) {
      "batch-result"
    }

    assertThat(result).isEqualTo("batch-result")
  }

  @Test
  fun `connection is released after completion`() {
    driver.executeBatchWithGeneratedKeys("INSERT INTO t (name) VALUES (?)", arrayOf("id")) {
      "result"
    }

    assertThat(withConnectionReleased).isTrue()
  }

  @Test
  fun `connection is released when action throws`() {
    assertFailure {
      driver.executeBatchWithGeneratedKeys("INSERT INTO t (name) VALUES (?)", arrayOf("id")) {
        throw SQLException("batch failed")
      }
    }

    assertThat(withConnectionReleased).isTrue()
  }
}
