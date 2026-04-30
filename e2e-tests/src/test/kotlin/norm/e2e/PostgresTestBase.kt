package norm.e2e

import norm.ConnectionProvider
import norm.TransactionalConnectionProvider
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AutoClose
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
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

  protected lateinit var connectionProvider: ConnectionProvider

  @AutoClose
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
    connectionProvider = TransactionalConnectionProvider(dataSource)

    // Ensure clean state before loading schema
    cleanDatabase(connection)

    // Load schema from test-scenarios
    val schemaScript = schemaFile().also {
      if (!it.exists()) error("Schema file not found: ${it.absolutePath}")
    }.readText()
    connection.createStatement().use { stmt -> stmt.execute(schemaScript) }
  }

  /**
   * Returns the schema file to apply before each test.
   * Subclasses override to point at a different test scenario's schema.
   */
  protected open fun schemaFile(): File = projectRoot.resolve("test-scenarios/all_types/schema.sql")

  /**
   * Drops any objects left over from the previous test so the schema can be reapplied cleanly.
   * Subclasses override to clean up scenario-specific types and tables.
   */
  protected open fun cleanDatabase(connection: Connection) {
    connection.createStatement().use { stmt ->
      stmt.execute("DROP TABLE IF EXISTS type CASCADE")
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
    /** Root of the Norm repository, resolved relative to e2e-tests' working directory. */
    val projectRoot: File = File(System.getProperty("user.dir")).parentFile

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
      .waitingFor(
        WaitAllStrategy()
          .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
          .withStrategy(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(60)),
      )

    /**
     * Creates a [DataSource] that returns real connections (not wrapped in [NonClosingConnectionWrapper]).
     * Needed for transaction tests where connection lifecycle matters — autoCommit changes, close, etc.
     */
    fun createRealDataSource(): DataSource = PGSimpleDataSource().apply {
      setURL(postgres.jdbcUrl)
      user = postgres.username
      password = postgres.password
    }
  }

  /**
   * Simple DataSource implementation that returns a single connection.
   * Good enough for single-threaded tests. Not suitable for production.
   *
   * Returns a [NonClosingConnectionWrapper] so that [norm.NormDriver]'s `connection.use { }` calls
   * do not actually close the underlying JDBC connection. Connection lifecycle is managed by the
   * [setupDatabase] / [cleanDatabase] cycle instead.
   */
  /**
   * Wraps a [Connection] and makes [close] a no-op.
   *
   * [norm.NormDriver] calls `connection.use { }` after each query, which would close the
   * underlying JDBC connection and break subsequent queries in the same test. This wrapper
   * intercepts [close] so the connection stays open for the full `@BeforeEach`–`@AfterEach`
   * lifecycle managed by [setupDatabase].
   */
  private class NonClosingConnectionWrapper(conn: Connection) : Connection by conn {
    override fun close() {
      // no-op: lifecycle managed by setupDatabase
    }
  }

  private class SingleConnectionDataSource(private val conn: Connection) : DataSource {
    override fun getConnection(): Connection = NonClosingConnectionWrapper(conn)
    override fun getConnection(username: String?, password: String?): Connection = NonClosingConnectionWrapper(conn)

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
