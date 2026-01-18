package norm.e2e

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import example.PostgresQueries
import example.Queries
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * E2E tests for the all_types test scenario.
 *
 * Tests validate that NORM's generated code correctly:
 * - Executes queries against real PostgreSQL
 * - Returns correct data types
 * - Handles multiple rows
 *
 * Strategy: Insert known data → query with generated code → assert correctness.
 */
class AllTypesE2ETest : PostgresTestBase() {

  private lateinit var queries: Queries

  @BeforeEach
  fun setupQueries() {
    queries = PostgresQueries(driver)
  }

  @Test
  fun `single query returns correct string value`() {
    // Given: A row with string_type = "test-value"
    insertMinimalRow(stringType = "test-value")

    // When: Execute single query (-- name: single :one)
    val result = queries.single { stringType -> stringType }

    // Then: Should return the exact value
    assertThat(result).isEqualTo("test-value")
  }

  @Test
  fun `all query returns multiple rows in order`() {
    // Given: Three rows with different string values
    insertMinimalRow(stringType = "first")
    insertMinimalRow(stringType = "second")
    insertMinimalRow(stringType = "third")

    // When: Execute all query and extract string_type (parameter 38)
    val results = queries.all {
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?, // 10
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?, // 20
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?, // 30
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        stringType: String, // 38
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?, // 48
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?, // 58
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
        _: Any?,
      ->
      // 63
      stringType
    }.list()

    // Then: Should return all three values in insertion order
    assertThat(results).containsExactly("first", "second", "third")
  }

  // Helper: Insert a minimal row (only required fields)
  private fun insertMinimalRow(stringType: String = "test") {
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
        '$stringType', '2025-01-01', '12:00:00',
        '12:00:00+00', '2025-01-01 12:00:00', '2025-01-01 12:00:00+00',
        '00000000-0000-0000-0000-000000000000', E'\\x00',
        ARRAY[1], ARRAY['test']
      )
      """.trimIndent(),
    )
  }
}
