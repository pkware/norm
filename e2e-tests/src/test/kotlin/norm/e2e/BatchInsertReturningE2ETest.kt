package norm.e2e

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNotZero
import assertk.assertions.startsWith
import example.crud.PostgresQueries
import norm.TransactionalConnectionProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.sql.Connection

class BatchInsertReturningE2ETest : PostgresTestBase() {

  private data class AuthorInput(val name: String, val bio: String?)

  private lateinit var queries: PostgresQueries

  override fun schemaFile(): File = projectRoot.resolve("test-scenarios/crud_generation/schema.sql")

  override fun cleanDatabase(connection: Connection) {
    connection.createStatement().use { stmt ->
      stmt.execute(
        """
        DROP VIEW IF EXISTS author_names CASCADE;
        DROP TABLE IF EXISTS product CASCADE;
        DROP TABLE IF EXISTS order_item CASCADE;
        DROP TABLE IF EXISTS audit_log CASCADE;
        DROP TABLE IF EXISTS author CASCADE;
        """.trimIndent(),
      )
    }
  }

  @BeforeEach
  fun setupQueries() {
    queries = PostgresQueries(connectionProvider)
  }

  @Nested
  inner class AuthorBatchInsert {

    @Test
    fun `returns generated IDs in insertion order`() {
      val inputs = listOf(
        AuthorInput("Alice", "Bio A"),
        AuthorInput("Bob", null),
        AuthorInput("Charlie", "Bio C"),
        AuthorInput("Diana", null),
        AuthorInput("Eve", "Bio E"),
      )

      val results = queries.insertAuthor(inputs, AuthorInput::name, AuthorInput::bio)

      assertThat(results).hasSize(5)
      for (result in results) {
        assertThat(result.id).isGreaterThan(0)
        assertThat(result.created_at).isNotNull()
      }

      val ids = results.map { it.id }
      assertThat(ids).isEqualTo(ids.sorted())

      val allAuthors = queries.findAllAuthor().list()
      assertThat(allAuthors).hasSize(5)
    }

    @Test
    fun `handles nullable insertable column`() {
      val inputs = listOf(
        AuthorInput("WithBio", "has a bio"),
        AuthorInput("NoBio", null),
      )

      val results = queries.insertAuthor(inputs, AuthorInput::name, AuthorInput::bio)

      assertThat(results).hasSize(2)

      val withBio = queries.findAuthorById(results[0].id).list().single()
      assertThat(withBio.bio).isEqualTo("has a bio")

      val noBio = queries.findAuthorById(results[1].id).list().single()
      assertThat(noBio.bio).isEqualTo(null)
    }

    @Test
    fun `flushes across multiple batchSize boundaries`() {
      val inputs = (1..250).map { AuthorInput("Author$it", null) }

      val results = queries.insertAuthor(inputs, AuthorInput::name, AuthorInput::bio, { id, createdAt ->
        example.crud.InsertAuthor(id, createdAt)
      }, 100)

      assertThat(results).hasSize(250)

      val ids = results.map { it.id }
      assertThat(ids.toSet()).hasSize(250)
      assertThat(ids).isEqualTo(ids.sorted())

      // Verify all rows were actually written to the database
      val allAuthors = queries.findAllAuthor().list()
      assertThat(allAuthors).hasSize(250)
    }

    @Test
    fun `empty input returns empty list`() {
      val results = queries.insertAuthor(emptyList(), AuthorInput::name, AuthorInput::bio)

      assertThat(results).isEmpty()
    }

    @Test
    fun `single row returns list of one`() {
      val results = queries.insertAuthor(listOf(AuthorInput("Solo", null)), AuthorInput::name, AuthorInput::bio)

      assertThat(results).hasSize(1)
      assertThat(results[0].id).isGreaterThan(0)
    }

    @Test
    fun `mapper overload transforms results`() {
      val inputs = listOf(
        AuthorInput("Alice", null),
        AuthorInput("Bob", null),
      )

      val ids: List<String> = queries.insertAuthor(
        inputs,
        AuthorInput::name,
        AuthorInput::bio,
        { id, _ -> "author-$id" },
        100,
      )

      assertThat(ids).hasSize(2)
      assertThat(ids[0]).startsWith("author-")
      assertThat(ids[1]).startsWith("author-")
    }
  }

  @Nested
  inner class ProductBatchInsert {

    @Test
    fun `returns generated ID and computed total`() {
      data class ProductInput(val name: String, val price: BigDecimal, val tax: BigDecimal)

      val inputs = listOf(
        ProductInput("Widget", BigDecimal("10.00"), BigDecimal("1.50")),
        ProductInput("Gadget", BigDecimal("20.00"), BigDecimal("3.00")),
      )

      val results = queries.insertProduct(inputs, ProductInput::name, ProductInput::price, ProductInput::tax)

      assertThat(results).hasSize(2)
      assertThat(results[0].id).isGreaterThan(0)
      assertThat(results[0].total).isEqualTo(BigDecimal("11.50"))
      assertThat(results[1].total).isEqualTo(BigDecimal("23.00"))
    }
  }

  @Nested
  inner class AuditLogBatchInsert {

    @Test
    fun `single returning column returns List of timestamps directly`() {
      val inputs = listOf("event1", "event2", "event3")

      val results = queries.insertAuditLog(inputs) { it }

      assertThat(results).hasSize(3)
      for (result in results) {
        assertThat(result.epochSecond).isNotZero()
      }
    }
  }

  @Nested
  inner class TransactionIntegration {

    private lateinit var txQueries: PostgresQueries

    @BeforeEach
    fun setupTxQueries() {
      val txProvider = TransactionalConnectionProvider(createRealDataSource())
      txQueries = PostgresQueries(txProvider)
    }

    @Test
    fun `batch insert within committed transaction`() {
      val results = txQueries.transactionWithResult(readOnly = false) {
        txQueries.insertAuthor(
          listOf(AuthorInput("TxAuthor", null)),
          AuthorInput::name,
          AuthorInput::bio,
        )
      }

      assertThat(results).hasSize(1)

      val found = txQueries.findAuthorById(results[0].id).list()
      assertThat(found).hasSize(1)
    }

    @Test
    fun `batch insert within rolled-back transaction`() {
      var returnedIds = emptyList<Int>()

      txQueries.transaction(readOnly = false) {
        val results = txQueries.insertAuthor(
          listOf(AuthorInput("RolledBack", null)),
          AuthorInput::name,
          AuthorInput::bio,
        )
        returnedIds = results.map { it.id }
        rollback()
      }

      assertThat(returnedIds).hasSize(1)

      val count = txQueries.countAuthor()
      assertThat(count).isEqualTo(0L)
    }
  }
}
