package norm.e2e

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import example.testqueries.AuditAction
import example.testqueries.Author
import example.testqueries.PostgresTestQueries
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs the queries of the `test_queries` scenario's `test` source set against a real database. The scenario's
 * main queries are generated into the same module, and `listAuthorsForAssertions` returns the [Author] type
 * that only the main output declares.
 */
class TestQueriesE2ETest : PostgresTestBase() {

  private lateinit var testQueries: PostgresTestQueries

  override fun schemaFile(): File = projectRoot.resolve("test-scenarios/test_queries/schema.sql")

  @BeforeEach
  fun setupQueries() {
    testQueries = PostgresTestQueries(connectionProvider)
    executeRawSql(
      """
      INSERT INTO author (name) VALUES ('Ada'), ('Grace');
      INSERT INTO audit_log (author_id, action) VALUES (1, 'created'), (1, 'updated'), (2, 'created');
      """.trimIndent(),
    )
  }

  @Test
  fun `a test query reads a table that only the test queries know`() {
    val actions = testQueries.listAuditLog().list().map { it.action }

    assertThat(actions).containsExactly(AuditAction.CREATED, AuditAction.UPDATED, AuditAction.CREATED)
  }

  @Test
  fun `a test query reuses the table type generated for the main queries`() {
    val authors = testQueries.listAuthorsForAssertions().list()

    assertThat(authors).containsExactly(Author(1, "Ada"), Author(2, "Grace"))
  }

  @Test
  fun `a test query returns a multi-column row`() {
    val rows = testQueries.countAuditLogByAuthor { authorId, entries -> authorId to entries }.list()

    assertThat(rows).containsExactly(1 to 2L, 2 to 1L)
  }

  @Test
  fun `a test query binds an enum parameter`() {
    val authorIds = testQueries.listAuditLogByAction(AuditAction.CREATED).list().map { it.author_id }

    assertThat(authorIds).isEqualTo(listOf(1, 2))
  }
}
