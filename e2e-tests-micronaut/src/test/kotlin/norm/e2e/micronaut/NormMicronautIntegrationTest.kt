package norm.e2e.micronaut

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import example.PostgresQueries
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/**
 * Integration tests proving Norm queries work through Micronaut's connection management.
 *
 * These tests verify:
 * - Norm queries execute correctly using Micronaut-managed connections
 * - Norm queries participate in `@Transactional` scopes (rollback on failure)
 * - Norm queries work outside a transaction
 */
@MicronautTest
class NormMicronautIntegrationTest {

  @Inject
  lateinit var queries: PostgresQueries

  @Inject
  lateinit var authorService: AuthorService

  @Inject
  lateinit var dataSource: DataSource

  @BeforeEach
  fun cleanDatabase() {
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("DELETE FROM person")
        statement.execute("DELETE FROM book")
        statement.execute("DELETE FROM author")
      }
    }
  }

  @Test
  fun `can execute Norm query through Micronaut connection management`() {
    // Insert test data via raw SQL so we have a known ID
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

    // Execute Norm query through Micronaut's connection management
    val author = queries.getAuthor(authorId)

    assertThat(author.id).isEqualTo(authorId)
    assertThat(author.name).isEqualTo("Jane Austen")
    assertThat(author.email).isEqualTo("jane@example.com")
  }

  @Test
  fun `Norm exec query works through Micronaut connection management`() {
    // Use Norm's addAuthor (exec query) to insert
    queries.addAuthor("Charles Dickens", "charles@example.com")

    // Verify via raw SQL that the insert worked
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
    // Attempt to add an author inside a REQUIRES_NEW transaction that will fail.
    // REQUIRES_NEW ensures the service gets its own transaction, independent of the
    // test's wrapping transaction. When error() throws, the service's transaction
    // rolls back completely.
    assertFailure {
      authorService.addAuthorThenFail("Ghost Author", "ghost@example.com")
    }

    // The insert should have been rolled back. The verification query runs on the
    // test's wrapping transaction, which never saw the insert (it was in a separate tx).
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
