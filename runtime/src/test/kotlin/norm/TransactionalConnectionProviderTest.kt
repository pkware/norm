package norm

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class TransactionalConnectionProviderTest {

  private lateinit var provider: TransactionalConnectionProvider
  private lateinit var dataSource: DataSource

  @BeforeEach
  fun setup() {
    val connection = DriverManager.getConnection(
      postgres.jdbcUrl,
      postgres.username,
      postgres.password,
    )
    connection.createStatement().use { stmt ->
      stmt.execute("DROP TABLE IF EXISTS test_data CASCADE")
      stmt.execute("CREATE TABLE test_data (id SERIAL PRIMARY KEY, value TEXT NOT NULL)")
    }
    connection.close()

    dataSource = SimpleDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    provider = TransactionalConnectionProvider(dataSource)
  }

  @Nested
  inner class BasicTransaction {

    @Test
    fun `successful transaction commits data`() {
      provider.transaction(readOnly = false) {
        provider.withConnection { conn ->
          conn.prepareStatement("INSERT INTO test_data (value) VALUES (?)").use { stmt ->
            stmt.setString(1, "committed")
            stmt.executeUpdate()
          }
        }
      }

      val count = provider.withConnection { conn ->
        conn.createStatement().use { stmt ->
          stmt.executeQuery("SELECT COUNT(*) FROM test_data").use { rs ->
            rs.next()
            rs.getInt(1)
          }
        }
      }
      assertThat(count).isEqualTo(1)
    }

    @Test
    fun `exception triggers rollback — data not persisted`() {
      class TestException : RuntimeException()

      assertThrows<TestException> {
        provider.transaction(readOnly = false) {
          provider.withConnection { conn ->
            conn.prepareStatement("INSERT INTO test_data (value) VALUES (?)").use { stmt ->
              stmt.setString(1, "should-not-persist")
              stmt.executeUpdate()
            }
          }
          throw TestException()
        }
      }

      val count = countRows()
      assertThat(count).isEqualTo(0)
    }

    @Test
    fun `explicit rollback — data not persisted`() {
      provider.transaction(readOnly = false) {
        provider.withConnection { conn ->
          conn.prepareStatement("INSERT INTO test_data (value) VALUES (?)").use { stmt ->
            stmt.setString(1, "should-not-persist")
            stmt.executeUpdate()
          }
        }
        rollback()
      }

      val count = countRows()
      assertThat(count).isEqualTo(0)
    }
  }

  @Nested
  inner class NestedTransactions {

    @Test
    fun `inner success + outer success = both committed`() {
      provider.transaction(readOnly = false) {
        insertRow("outer")
        provider.transaction(readOnly = false) {
          insertRow("inner")
        }
      }

      assertThat(countRows()).isEqualTo(2)
    }

    @Test
    fun `inner explicit rollback + outer success = only inner rolled back`() {
      provider.transaction(readOnly = false) {
        insertRow("outer")
        provider.transaction(readOnly = false) {
          insertRow("inner-rolled-back")
          rollback()
        }
      }

      assertThat(countRows()).isEqualTo(1)
      val value = provider.withConnection { conn ->
        conn.createStatement().use { stmt ->
          stmt.executeQuery("SELECT value FROM test_data").use { rs ->
            rs.next()
            rs.getString(1)
          }
        }
      }
      assertThat(value).isEqualTo("outer")
    }

    @Test
    fun `inner exception poisons outer — all rolled back`() {
      class InnerFailure : RuntimeException()

      provider.transaction(readOnly = false) {
        insertRow("outer")
        try {
          provider.transaction(readOnly = false) {
            insertRow("inner")
            throw InnerFailure()
          }
        } catch (_: InnerFailure) {
          // outer catches and continues — but transaction is poisoned
        }
      }

      assertThat(countRows()).isEqualTo(0)
    }
  }

  @Nested
  inner class ReadOnly {

    @Test
    fun `readOnly=true prevents writes`() {
      assertThrows<java.sql.SQLException> {
        provider.transaction(readOnly = true) {
          insertRow("should-fail")
        }
      }
    }

    @Test
    fun `readOnly=false allows writes`() {
      provider.transaction(readOnly = false) {
        insertRow("allowed")
      }
      assertThat(countRows()).isEqualTo(1)
    }

    @Test
    fun `read-only outer with read-write inner throws IllegalStateException`() {
      val exception = assertThrows<IllegalStateException> {
        provider.transaction(readOnly = true) {
          provider.transaction(readOnly = false) {
            // should not reach here
          }
        }
      }
      assertThat(exception.message).isNotNull().contains("read-only")
    }

    @Test
    fun `read-write outer with read-only inner is allowed`() {
      provider.transaction(readOnly = false) {
        insertRow("outer-write")
        provider.transaction(readOnly = true) {
          // read-only intent marker — Postgres doesn't enforce per-savepoint
          val count = countRows()
          assertThat(count).isEqualTo(1)
        }
      }
    }
  }

  @Nested
  inner class TransactionWithResult {

    @Test
    fun `returns value on success`() {
      val result = provider.transactionWithResult {
        provider.withConnection { conn ->
          conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT 42").use { rs ->
              rs.next()
              rs.getInt(1)
            }
          }
        }
      }
      assertThat(result).isEqualTo(42)
    }

    @Test
    fun `rolls back on exception`() {
      class TestException : RuntimeException()

      assertThrows<TestException> {
        provider.transactionWithResult(readOnly = false) {
          insertRow("should-not-persist")
          throw TestException()
        }
      }
      assertThat(countRows()).isEqualTo(0)
    }

    @Test
    fun `explicit rollback — data not persisted, RollbackException thrown`() {
      assertThrows<RollbackException> {
        provider.transactionWithResult(readOnly = false) {
          insertRow("should-not-persist")
          rollback()
        }
      }
      assertThat(countRows()).isEqualTo(0)
    }

    @Test
    fun `nested explicit rollback does not poison outer`() {
      provider.transaction(readOnly = false) {
        insertRow("outer")
        runCatching {
          provider.transactionWithResult(readOnly = false) {
            insertRow("inner-rolled-back")
            rollback()
          }
        }
      }
      // outer committed, inner rolled back via savepoint
      assertThat(countRows()).isEqualTo(1)
    }
  }

  @Nested
  inner class VirtualThreads {

    @Test
    fun `transactions work correctly on virtual threads`() {
      val results = ConcurrentHashMap<String, Int>()

      val threads = (1..10).map { i ->
        Thread.ofVirtual().start {
          provider.transaction(readOnly = false) {
            insertRow("vt-$i")
          }
          results["vt-$i"] = 1
        }
      }
      threads.forEach { it.join() }

      assertThat(results.size).isEqualTo(10)
      assertThat(countRows()).isEqualTo(10)
    }
  }

  private fun countRows(): Int = provider.withConnection { conn ->
    conn.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM test_data").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
  }

  private fun insertRow(value: String) {
    provider.withConnection { conn ->
      conn.prepareStatement("INSERT INTO test_data (value) VALUES (?)").use { stmt ->
        stmt.setString(1, value)
        stmt.executeUpdate()
      }
    }
  }

  companion object {
    @Container
    @JvmStatic
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:18-alpine")
      .withDatabaseName("test")
      .withUsername("test")
      .withPassword("test")
      .waitingFor(Wait.forListeningPort())
  }

  /**
   * Minimal DataSource that creates new connections on each call.
   * Suitable for tests where real connection lifecycle matters (unlike
   * SingleConnectionDataSource in e2e-tests which wraps a single connection).
   */
  private class SimpleDataSource(private val url: String, private val username: String, private val password: String) :
    DataSource {
    override fun getConnection() = DriverManager.getConnection(url, username, password)
    override fun getConnection(username: String?, password: String?) =
      DriverManager.getConnection(url, username ?: this.username, password ?: this.password)
    override fun <T> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
    override fun getLogWriter() = throw UnsupportedOperationException()
    override fun setLogWriter(out: java.io.PrintWriter?) = throw UnsupportedOperationException()
    override fun setLoginTimeout(seconds: Int) = throw UnsupportedOperationException()
    override fun getLoginTimeout(): Int = throw UnsupportedOperationException()
    override fun getParentLogger() = throw UnsupportedOperationException()
  }
}
