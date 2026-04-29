package norm.e2e

import assertk.assertThat
import assertk.assertions.isEqualTo
import example.PostgresQueries
import norm.TransactionalConnectionProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TransactionE2ETest : PostgresTestBase() {

  private lateinit var queries: PostgresQueries

  @BeforeEach
  fun setupQueries() {
    val provider = TransactionalConnectionProvider(createRealDataSource())
    queries = PostgresQueries(provider)
  }

  @Test
  fun `transaction commits deleteAll`() {
    insertRows(2)
    assertThat(queries.all().list().size).isEqualTo(2)

    queries.transaction(readOnly = false) {
      queries.deleteAll()
    }

    assertThat(queries.all().list().size).isEqualTo(0)
  }

  @Test
  fun `transaction rollback does not persist`() {
    insertRows(2)
    assertThat(queries.all().list().size).isEqualTo(2)

    queries.transaction(readOnly = false) {
      queries.deleteAll()
      rollback()
    }

    assertThat(queries.all().list().size).isEqualTo(2)
  }

  @Test
  fun `nested transaction rollback does not affect outer`() {
    insertRows(2)
    assertThat(queries.all().list().size).isEqualTo(2)

    // Outer commits, nested rolls back — net result: nothing deleted
    queries.transaction(readOnly = false) {
      queries.transaction(readOnly = false) {
        queries.deleteAll()
        rollback()
      }
    }

    assertThat(queries.all().list().size).isEqualTo(2)
  }

  /**
   * Inserts [count] rows via raw SQL using the base-class connection.
   * Each row satisfies all NOT NULL constraints of the `type` table.
   */
  private fun insertRows(count: Int) {
    repeat(count) { index ->
      executeRawSql(
        """
        INSERT INTO type (
          serial2_type, serial4_type, serial8_type,
          int2_type, int4_type, int8_type,
          float4_type, float8_type,
          string_type, date_notnull_type, time_notnull_type,
          timetz_notnull_type, timestamp_notnull_type, timestamptz_notnull_type,
          uuid_notnull_type, bytea_notnull_type,
          int_array_notnull_type, text_array_notnull_type
        ) VALUES (
          1, 1, 1,
          1, 1, 1,
          1.0, 1.0,
          'row-$index', '2025-01-01', '12:00:00',
          '12:00:00+00', '2025-01-01 12:00:00', '2025-01-01 12:00:00+00',
          '00000000-0000-0000-0000-000000000000', E'\\x00',
          ARRAY[1], ARRAY['test']
        )
        """.trimIndent(),
      )
    }
  }
}
