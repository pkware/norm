package norm.e2e

import norm.NormDriver
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * Base class for E2E tests that need a real PostgreSQL database.
 *
 * Uses testcontainers to run PostgreSQL in Docker. The container is shared
 * across all test methods in the class for performance (startup time ~5-10s).
 * The database is cleaned between each test method for isolation.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
abstract class PostgresTestBase {

  protected lateinit var driver: NormDriver
  private lateinit var connection: Connection

  @BeforeEach
  fun setupDatabase() {
    // Create a new connection for each test (not pooled - simpler for tests)
    connection = DriverManager.getConnection(
      postgres.jdbcUrl,
      postgres.username,
      postgres.password,
    )
    connection.autoCommit = true

    // Wrap connection in a simple DataSource
    val dataSource = SingleConnectionDataSource(connection)
    driver = NormDriver(dataSource)

    // Ensure clean state before loading schema
    connection.createStatement().use { stmt ->
      stmt.execute("DROP TABLE IF EXISTS type CASCADE")
    }

    // Load schema from test-scenarios
    loadSchema()
  }

  private fun loadSchema() {
    // Load schema from test-scenarios (not duplicated)
    // Working directory is e2e-tests/, so navigate up to project root
    val projectRoot = File(System.getProperty("user.dir")).parentFile
    val schemaFile = projectRoot.resolve("test-scenarios-basic/all_types/schema.sql")

    if (!schemaFile.exists()) {
      error("Schema file not found: ${schemaFile.absolutePath}")
    }

    val schemaScript = schemaFile.readText()

    connection.createStatement().use { stmt ->
      stmt.execute(schemaScript)
    }
  }

  /**
   * Execute raw SQL for test data setup.
   * Useful for inserting rows with specific values to test query behavior.
   */
  protected fun executeRawSql(@Language("PostgreSQL") sql: String) {
    connection.createStatement().use { stmt ->
      stmt.execute(sql)
    }
  }

  companion object {
    /**
     * Shared PostgreSQL container for all tests in this class.
     * Uses alpine variant for smaller image size (~80MB vs ~300MB).
     */
    @Container
    @JvmStatic
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:18-alpine")
      .withDatabaseName("test")
      .withUsername("test")
      .withPassword("test")
      .waitingFor(Wait.forListeningPort())
  }

  /**
   * Simple DataSource implementation that returns a single connection.
   * Good enough for single-threaded tests. Not suitable for production.
   */
  private class SingleConnectionDataSource(private val conn: Connection) : DataSource {
    override fun getConnection(): Connection = conn
    override fun getConnection(username: String?, password: String?): Connection = conn

    // Unsupported operations (not needed for tests)
    override fun <T> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
    override fun getLogWriter() = throw UnsupportedOperationException()
    override fun setLogWriter(out: java.io.PrintWriter?) = throw UnsupportedOperationException()
    override fun setLoginTimeout(seconds: Int) = throw UnsupportedOperationException()
    override fun getLoginTimeout(): Int = throw UnsupportedOperationException()
    override fun getParentLogger() = throw UnsupportedOperationException()
  }
}
