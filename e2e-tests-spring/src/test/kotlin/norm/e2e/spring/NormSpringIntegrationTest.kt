package norm.e2e.spring

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import example.PostgresQueries
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

/**
 * Integration tests proving Norm queries work through Spring's connection management.
 *
 * These tests verify:
 * - Norm queries execute correctly using Spring-managed connections (via [DataSourceUtils])
 * - Norm queries participate in `@Transactional` scopes (rollback on failure)
 * - Norm queries work outside a transaction
 */
@SpringBootTest
@ActiveProfiles("test")
class NormSpringIntegrationTest {

  @Autowired
  lateinit var queries: PostgresQueries

  @Autowired
  lateinit var authorService: AuthorService

  @Autowired
  lateinit var dataSource: DataSource

  @BeforeEach
  fun cleanDatabase() {
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("DELETE FROM book")
        statement.execute("DELETE FROM author")
      }
    }
  }

  @Test
  fun `can execute Norm query through Spring connection management`() {
    val authorId = dataSource.connection.use { connection ->
      connection.prepareStatement("INSERT INTO author (name, email) VALUES (?, ?) RETURNING id").use { statement ->
        statement.setString(1, "Jane Austen")
        statement.setString(2, "jane@example.com")
        statement.executeQuery().use { resultSet ->
          resultSet.next()
          resultSet.getInt(1)
        }
      }
    }

    val author = queries.getAuthor(authorId)

    assertThat(author.id).isEqualTo(authorId)
    assertThat(author.name).isEqualTo("Jane Austen")
    assertThat(author.email).isEqualTo("jane@example.com")
  }

  @Test
  fun `Norm exec query works through Spring connection management`() {
    queries.addAuthor("Charles Dickens", "charles@example.com")

    val name = dataSource.connection.use { connection ->
      connection.prepareStatement("SELECT name FROM author WHERE name = ?").use { statement ->
        statement.setString(1, "Charles Dickens")
        statement.executeQuery().use { resultSet ->
          resultSet.next()
          resultSet.getString(1)
        }
      }
    }

    assertThat(name).isEqualTo("Charles Dickens")
  }

  @Test
  fun `queries participate in @Transactional scope with rollback`() {
    assertFailure {
      authorService.addAuthorThenFail("Ghost Author", "ghost@example.com")
    }

    val count = dataSource.connection.use { connection ->
      connection.prepareStatement("SELECT COUNT(*) FROM author WHERE name = ?").use { statement ->
        statement.setString(1, "Ghost Author")
        statement.executeQuery().use { resultSet ->
          resultSet.next()
          resultSet.getLong(1)
        }
      }
    }

    assertThat(count).isEqualTo(0L)
  }

  @Test
  fun `nullable columns are handled correctly`() {
    queries.addAuthor("No Email Author", null)

    val authorId = dataSource.connection.use { connection ->
      connection.prepareStatement("SELECT id FROM author WHERE name = ?").use { statement ->
        statement.setString(1, "No Email Author")
        statement.executeQuery().use { resultSet ->
          resultSet.next()
          resultSet.getInt(1)
        }
      }
    }

    val result = queries.getAuthor(authorId)
    assertThat(result.name).isEqualTo("No Email Author")
    assertThat(result.email).isNull()
  }
}
