package norm.e2e

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import example.PostgresQueries
import example.Queries
import example.Type
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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

  @Nested
  inner class Arrays {

    @Test
    fun `array values are correctly retrieved`() {
      // Given: A row with populated arrays
      insertRowWithArrays(
        intArrayNotNull = "ARRAY[1, 2, 3]",
        textArrayNotNull = "ARRAY['a', 'b', 'c']",
      )

      // When: Query and retrieve the Type object
      val result = queries.all(::Type).list().first()

      // Then: Arrays should contain the expected values
      assertThat(result.int_array_notnull_type).containsExactly(1, 2, 3)
      assertThat(result.text_array_notnull_type).containsExactly("a", "b", "c")
    }

    @Test
    fun `null arrays return null for nullable columns`() {
      // Given: A row with NULL for nullable array columns
      insertRowWithArrays(
        intArray = "NULL",
        textArray = "NULL",
      )

      // When: Query and retrieve the Type object
      val result = queries.all(::Type).list().first()

      // Then: Nullable array columns should be null
      assertThat(result.int_array_type).isNull()
      assertThat(result.text_array_type).isNull()
    }

    @Test
    fun `empty arrays are distinct from null`() {
      // Given: A row with empty arrays (not NULL)
      insertRowWithArrays(
        intArray = "'{}'",
        textArray = "'{}'",
      )

      // When: Query and retrieve the Type object
      val result = queries.all(::Type).list().first()

      // Then: Arrays should be empty, not null
      assertThat(result.int_array_type).isNotNull().isEmpty()
      assertThat(result.text_array_type).isNotNull().isEmpty()
    }

    @Test
    fun `arrays can contain null elements`() {
      // Given: Arrays containing null elements
      insertRowWithArrays(
        intArrayNotNull = "ARRAY[1, NULL, 3]",
        textArrayNotNull = "ARRAY['a', NULL, 'c']",
      )

      // When: Query and retrieve the Type object
      val result = queries.all(::Type).list().first()

      // Then: Arrays should have 3 elements with null at index 1
      assertThat(result.int_array_notnull_type).containsExactly(1, null, 3)
      assertThat(result.text_array_notnull_type).containsExactly("a", null, "c")
    }
  }

  // Helper: Insert a minimal row (only required fields)
  private fun insertMinimalRow(stringType: String = "test") {
    insertRowWithArrays(stringType = stringType)
  }

  /**
   * Inserts a row with configurable array values for testing array type handling.
   *
   * @param intArray SQL expression for int_array_type (nullable). Use "NULL" for null, "ARRAY[1,2]" for values.
   * @param intArrayNotNull SQL expression for int_array_notnull_type. Required, cannot be null.
   * @param textArray SQL expression for text_array_type (nullable). Use "NULL" for null, "ARRAY['a','b']" for values.
   * @param textArrayNotNull SQL expression for text_array_notnull_type. Required, cannot be null.
   */
  private fun insertRowWithArrays(
    stringType: String = "test",
    intArray: String = "NULL",
    intArrayNotNull: String = "ARRAY[1]",
    textArray: String = "NULL",
    textArrayNotNull: String = "ARRAY['test']",
  ) {
    executeRawSql(
      """
      INSERT INTO type (
        serial2_type, serial4_type, serial8_type,
        int2_type, int4_type, int8_type,
        float4_type, float8_type,
        string_type, date_notnull_type, time_notnull_type,
        timetz_notnull_type, timestamp_notnull_type, timestamptz_notnull_type,
        uuid_notnull_type, bytea_notnull_type,
        int_array_type, int_array_notnull_type, text_array_type, text_array_notnull_type
      ) VALUES (
        1, 1, 1,
        1, 1, 1,
        1.0, 1.0,
        '$stringType', '2025-01-01', '12:00:00',
        '12:00:00+00', '2025-01-01 12:00:00', '2025-01-01 12:00:00+00',
        '00000000-0000-0000-0000-000000000000', E'\\x00',
        $intArray, $intArrayNotNull, $textArray, $textArrayNotNull
      )
      """.trimIndent(),
    )
  }
}
