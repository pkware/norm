package norm.e2e.spring

import assertk.assertThat
import assertk.assertions.isEqualTo
import example.SpringConnectionProvider
import norm.ConnectionProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * Tests that [SpringConnectionProvider] correctly integrates with Spring's
 * connection management via [DataSourceUtils].
 *
 * Connection decoration happens at the
 * [DataSource] level, and [DataSourceUtils.getConnection] handles transaction-bound connection reuse. These tests
 * verify that Norm's `SpringConnectionProvider` properly delegates to [DataSourceUtils] rather than bypassing it.
 */
@SpringBootTest
@ActiveProfiles("test")
class NormSpringConnectionCustomizerTest {

  @Autowired
  lateinit var connectionProvider: ConnectionProvider

  @Autowired
  lateinit var dataSource: DataSource

  lateinit var transactionTemplate: TransactionTemplate

  @Autowired
  fun initTransactionTemplate(transactionManager: PlatformTransactionManager) {
    transactionTemplate = TransactionTemplate(transactionManager)
  }

  @BeforeEach
  fun cleanDatabase() {
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("DELETE FROM book")
        statement.execute("DELETE FROM author")
      }
    }
  }

  @Nested
  inner class WithConnection {

    @Test
    fun `withConnection returns the transaction-bound connection`() {
      transactionTemplate.execute { _ ->
        val transactionPid = DataSourceUtils.getConnection(dataSource).let { connection ->
          try {
            connection.prepareStatement("SELECT pg_backend_pid()").use { statement ->
              statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
              }
            }
          } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
          }
        }

        val normPid = connectionProvider.withConnection { connection ->
          connection.prepareStatement("SELECT pg_backend_pid()").use { statement ->
            statement.executeQuery().use { resultSet ->
              resultSet.next()
              resultSet.getInt(1)
            }
          }
        }

        assertThat(normPid).isEqualTo(transactionPid)
      }
    }
  }

  @Nested
  inner class BorrowConnection {

    @Test
    fun `borrowed connection within transaction is the transaction-bound connection`() {
      transactionTemplate.execute { _ ->
        val transactionPid = DataSourceUtils.getConnection(dataSource).let { connection ->
          try {
            connection.prepareStatement("SELECT pg_backend_pid()").use { statement ->
              statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
              }
            }
          } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
          }
        }

        val borrowed = connectionProvider.borrowConnection()
        val borrowedPid = borrowed.connection.prepareStatement("SELECT pg_backend_pid()").use { statement ->
          statement.executeQuery().use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
          }
        }

        assertThat(borrowedPid).isEqualTo(transactionPid)
      }
    }
  }
}
