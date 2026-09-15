package norm.e2e

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import example.crud.PostgresQueries
import norm.ColumnValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Live-database coverage for #299: a synthesized `insert*` function's overridable-default column
 * (`preference.note`, nullable with `DEFAULT 'n/a'`) supports all three [ColumnValue] states --
 * omitted (the database's own `DEFAULT`), an explicit value, and an explicit SQL `NULL` -- and each
 * is distinguishable from the others, for both the single-row and the batch insert path.
 */
class ColumnValueOverrideE2ETest : PostgresTestBase() {

  private lateinit var queries: PostgresQueries

  override fun schemaFile(): File = projectRoot.resolve("test-scenarios/crud_generation/schema.sql")

  @BeforeEach
  fun setupQueries() {
    queries = PostgresQueries(connectionProvider)
  }

  @Nested
  inner class SingleRow {

    @Test
    fun `omitting note applies the database default`() {
      val preference = queries.insertPreference()

      assertThat(preference.theme).isEqualTo("light")
      assertThat(preference.note).isEqualTo("n/a")
    }

    @Test
    fun `an explicit value overrides the database default`() {
      val preference = queries.insertPreference(note = ColumnValue.Set("custom"))

      assertThat(preference.note).isEqualTo("custom")
    }

    @Test
    fun `an explicit null is distinct from the database default`() {
      val preference = queries.insertPreference(note = ColumnValue.Set(null))

      assertThat(preference.note).isNull()
    }
  }

  @Nested
  inner class Batch {

    @Test
    fun `a null extractor applies the database default to every row in the call`() {
      val results = queries.insertPreference(listOf(1, 2))

      assertThat(results).hasSize(2)
      assertThat(results.map { it.note }).isEqualTo(listOf("n/a", "n/a"))
    }

    @Test
    fun `a non-null extractor overrides the database default per row`() {
      val results = queries.insertPreference(listOf("first", "second"), note = { "custom-$it" })

      assertThat(results.map { it.note }).isEqualTo(listOf("custom-first", "custom-second"))
    }

    @Test
    fun `an extractor returning null binds an explicit SQL null, distinct from the database default`() {
      val results = queries.insertPreference(listOf(1, 2), note = { null })

      assertThat(results.map { it.note }).isEqualTo(listOf(null, null))
    }
  }
}
