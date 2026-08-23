package norm.e2e

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import example.crud.PostgresQueries
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.sql.Connection

/**
 * Covers the `:exec` and `:execrows` batch overloads against a real database, with particular attention to the
 * element counts that used to throw or silently truncate the returned array (issue #189): an empty stream, and a
 * stream whose size is a nonzero exact multiple of `batchSize`.
 *
 * `insertOrderItem` is the `:exec` batch (composite primary key, so the synthesized insert has no `RETURNING`
 * clause); `deleteAuthorById` is the `:execrows` batch.
 */
class BatchExecE2ETest : PostgresTestBase() {

  private data class OrderItemInput(val orderId: Int, val itemId: Int, val quantity: Int, val price: BigDecimal)

  private lateinit var queries: PostgresQueries

  override fun schemaFile(): File = projectRoot.resolve("test-scenarios/crud_generation/schema.sql")

  override fun cleanDatabase(connection: Connection) {
    connection.createStatement().use { stmt ->
      stmt.execute(
        """
        DROP VIEW IF EXISTS author_names CASCADE;
        DROP TABLE IF EXISTS document CASCADE;
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

  private fun orderItems(count: Int): List<OrderItemInput> =
    (1..count).map { OrderItemInput(orderId = 1, itemId = it, quantity = it, price = BigDecimal("1.00")) }

  private fun insertOrderItems(inputs: List<OrderItemInput>): IntArray = queries.insertOrderItem(
    inputs,
    OrderItemInput::orderId,
    OrderItemInput::itemId,
    OrderItemInput::quantity,
    OrderItemInput::price,
    BATCH_SIZE,
  )

  /** Inserts [count] authors and returns their generated ids in insertion order. */
  private fun seedAuthors(count: Int): List<Int> =
    queries.insertAuthor((1..count).map { "Author$it" }, { it }, { null }).map { it.id }

  @Nested
  inner class ExecBatch {

    @Test
    fun `empty stream returns an empty array`() {
      val results = insertOrderItems(emptyList())

      assertThat(results.toList()).isEmpty()
      assertThat(queries.countOrderItem()).isEqualTo(0L)
    }

    @Test
    fun `stream smaller than batchSize returns one successful insert count per element`() {
      val results = insertOrderItems(orderItems(3))

      assertThat(results.toList()).isEqualTo(List(3) { 1 })
      assertThat(queries.countOrderItem()).isEqualTo(3L)
    }

    @Test
    fun `stream sized to exactly batchSize returns one successful insert count per element`() {
      val results = insertOrderItems(orderItems(BATCH_SIZE))

      assertThat(results.toList()).isEqualTo(List(BATCH_SIZE) { 1 })
      assertThat(queries.countOrderItem()).isEqualTo(BATCH_SIZE.toLong())
    }

    @Test
    fun `stream sized to twice batchSize returns one successful insert count per element`() {
      val count = 2 * BATCH_SIZE

      val results = insertOrderItems(orderItems(count))

      assertThat(results.toList()).isEqualTo(List(count) { 1 })
      assertThat(queries.countOrderItem()).isEqualTo(count.toLong())
    }

    @Test
    fun `stream spanning several batches with a partial final batch returns one successful insert count per element`() {
      val count = 2 * BATCH_SIZE + 3

      val results = insertOrderItems(orderItems(count))

      assertThat(results.toList()).isEqualTo(List(count) { 1 })
      assertThat(queries.countOrderItem()).isEqualTo(count.toLong())
    }
  }

  @Nested
  inner class ExecRowsBatch {

    @Test
    fun `empty stream returns an empty array`() {
      val affected = queries.deleteAuthorById(emptyList<Int>(), { it }, BATCH_SIZE)

      assertThat(affected.toList()).isEmpty()
    }

    @Test
    fun `stream smaller than batchSize reports one affected row per element`() {
      val ids = seedAuthors(3)

      val affected = queries.deleteAuthorById(ids, { it }, BATCH_SIZE)

      assertThat(affected.toList()).isEqualTo(List(3) { 1 })
      assertThat(queries.countAuthor()).isEqualTo(0L)
    }

    @Test
    fun `stream sized to exactly batchSize reports one affected row per element`() {
      val ids = seedAuthors(BATCH_SIZE)

      val affected = queries.deleteAuthorById(ids, { it }, BATCH_SIZE)

      assertThat(affected.toList()).isEqualTo(List(BATCH_SIZE) { 1 })
      assertThat(queries.countAuthor()).isEqualTo(0L)
    }

    @Test
    fun `stream sized to twice batchSize reports one affected row per element`() {
      val ids = seedAuthors(2 * BATCH_SIZE)

      val affected = queries.deleteAuthorById(ids, { it }, BATCH_SIZE)

      assertThat(affected.toList()).isEqualTo(List(2 * BATCH_SIZE) { 1 })
      assertThat(queries.countAuthor()).isEqualTo(0L)
    }

    @Test
    fun `stream spanning several batches with a partial final batch reports one affected row per element`() {
      val ids = seedAuthors(2 * BATCH_SIZE + 3)

      val affected = queries.deleteAuthorById(ids, { it }, BATCH_SIZE)

      assertThat(affected.toList()).isEqualTo(List(ids.size) { 1 })
      assertThat(queries.countAuthor()).isEqualTo(0L)
    }

    @Test
    fun `reports zero for elements that match no row`() {
      val ids = seedAuthors(BATCH_SIZE)

      // The final batch is partial and contains only ids that match nothing, so the zeroes can only appear in the
      // combined array if the earlier full batch's results were captured too.
      val affected = queries.deleteAuthorById(ids + listOf(-1, -2), { it }, BATCH_SIZE)

      assertThat(affected.toList()).isEqualTo(List(BATCH_SIZE) { 1 } + listOf(0, 0))
      assertThat(queries.countAuthor()).isEqualTo(0L)
    }
  }

  private companion object {
    /**
     * Small enough to keep the seeded row counts cheap, large enough that the multi-batch cases flush more than
     * once.
     */
    const val BATCH_SIZE = 4
  }
}
