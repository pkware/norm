package norm.e2e

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import example.PostgresQueries
import example.Queries
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * E2E tests for plain (adapterless) array parameter binding and element reads (#190, #192).
 *
 * Every test writes through generated code and reads back through generated code. Golden files
 * cannot catch these defects: the generated code compiles cleanly and fails only at execution.
 */
class PlainArrayE2ETest : PostgresTestBase() {

  private lateinit var queries: Queries

  @BeforeEach
  fun setupQueries() {
    queries = PostgresQueries(connectionProvider)
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
        'array-target', '2025-01-01', '12:00:00',
        '12:00:00+00', '2025-01-01 12:00:00', '2025-01-01 12:00:00+00',
        '00000000-0000-0000-0000-000000000000', E'\\x00',
        ARRAY[1], ARRAY['test']
      )
      """.trimIndent(),
    )
  }

  // Return type is the generated result record for `SELECT * FROM type`; let inference name it.
  private fun row() = queries.all().list().single()

  @Nested
  inner class IntegerAndTextArrays {

    @Test
    fun `int and text arrays round-trip through generated binding`() {
      val updated = queries.updateIntTextArrays(
        arrayOf(1, null, 3),
        arrayOf(4, 5),
        arrayOf("a", null, "c"),
        arrayOf("d"),
        "array-target",
      )

      assertThat(updated).isEqualTo(1)
      val row = row()
      assertThat(row.int_array_type!!.toList()).containsExactly(1, null, 3)
      assertThat(row.int_array_notnull_type.toList()).containsExactly(4, 5)
      assertThat(row.text_array_type!!.toList()).containsExactly("a", null, "c")
      assertThat(row.text_array_notnull_type.toList()).containsExactly("d")
    }

    @Test
    fun `null array binds as SQL NULL`() {
      val updated = queries.updateIntTextArrays(null, arrayOf(1), null, arrayOf("x"), "array-target")

      assertThat(updated).isEqualTo(1)
      val row = row()
      assertThat(row.int_array_type).isNull()
      assertThat(row.text_array_type).isNull()
    }

    @Test
    fun `empty array is distinct from null`() {
      queries.updateIntTextArrays(emptyArray(), emptyArray(), emptyArray(), emptyArray(), "array-target")

      val row = row()
      assertThat(row.int_array_type).isNotNull()
      assertThat(row.int_array_type!!.toList()).isEmpty()
      assertThat(row.int_array_notnull_type.toList()).isEmpty()
      assertThat(row.text_array_type!!.toList()).isEmpty()
      assertThat(row.text_array_notnull_type.toList()).isEmpty()
    }
  }

  @Nested
  inner class NumericArrays {

    @Test
    fun `numeric element types round-trip`() {
      val updated = queries.updateNumericArrays(
        arrayOf(1.toShort(), null),
        arrayOf(2.toShort()),
        arrayOf(3L, null),
        arrayOf(4L),
        arrayOf(1.5f, null),
        arrayOf(2.5f),
        arrayOf(3.5, null),
        arrayOf(4.5),
        arrayOf(BigDecimal("1.50"), null),
        arrayOf(BigDecimal("2.25")),
        "array-target",
      )

      assertThat(updated).isEqualTo(1)
      val row = row()
      assertThat(row.int2_array_type!!.toList()).containsExactly(1.toShort(), null)
      assertThat(row.int2_array_notnull_type.toList()).containsExactly(2.toShort())
      assertThat(row.int8_array_type!!.toList()).containsExactly(3L, null)
      assertThat(row.int8_array_notnull_type.toList()).containsExactly(4L)
      assertThat(row.float4_array_type!!.toList()).containsExactly(1.5f, null)
      assertThat(row.float4_array_notnull_type.toList()).containsExactly(2.5f)
      assertThat(row.float8_array_type!!.toList()).containsExactly(3.5, null)
      assertThat(row.float8_array_notnull_type.toList()).containsExactly(4.5)
      assertThat(row.numeric_array_type!!.first()!!).isEqualTo(BigDecimal("1.50"))
      assertThat(row.numeric_array_type[1]).isNull()
      assertThat(row.numeric_array_notnull_type.single()!!).isEqualTo(BigDecimal("2.25"))
    }

    @Test
    fun `NaN and Infinity survive float element binding`() {
      queries.updateNumericArrays(
        arrayOf(1.toShort()),
        arrayOf(1.toShort()),
        arrayOf(1L),
        arrayOf(1L),
        arrayOf(Float.NaN, Float.POSITIVE_INFINITY),
        arrayOf(1.0f),
        arrayOf(Double.NaN, Double.NEGATIVE_INFINITY),
        arrayOf(1.0),
        arrayOf(BigDecimal.ONE),
        arrayOf(BigDecimal.ONE),
        "array-target",
      )

      val row = row()
      assertThat(row.float4_array_type!!.toList()).containsExactly(Float.NaN, Float.POSITIVE_INFINITY)
      assertThat(row.float8_array_type!!.toList()).containsExactly(Double.NaN, Double.NEGATIVE_INFINITY)
    }
  }

  @Nested
  inner class DateTimeArrays {

    @Test
    fun `timetz array preserves its offset`() {
      // This is the assertion the bulk getArray().array path cannot satisfy: pgjdbc decodes timetz
      // elements as java.sql.Time, which carries no offset at all.
      val noon = OffsetTime.of(12, 0, 0, 0, ZoneOffset.ofHours(2))

      queries.updateDateTimeArrays(
        arrayOf(LocalDate.of(2025, 1, 1)),
        arrayOf(LocalDate.of(2025, 1, 2)),
        arrayOf(LocalTime.of(1, 0)),
        arrayOf(LocalTime.of(2, 0)),
        arrayOf(noon, null),
        arrayOf(noon),
        arrayOf(LocalDateTime.of(2025, 1, 1, 3, 0)),
        arrayOf(LocalDateTime.of(2025, 1, 1, 4, 0)),
        arrayOf(Instant.parse("2025-01-01T05:00:00Z")),
        arrayOf(Instant.parse("2025-01-01T06:00:00Z")),
        "array-target",
      )

      val row = row()
      assertThat(row.timetz_array_type!!.toList()).containsExactly(noon, null)
      assertThat(row.timetz_array_notnull_type.toList()).containsExactly(noon)
    }

    @Test
    fun `time array keeps microsecond precision`() {
      // The bulk path decodes time elements as java.sql.Time, which truncates to milliseconds.
      val precise = LocalTime.of(12, 0, 0, 123_456_000)

      queries.updateDateTimeArrays(
        arrayOf(LocalDate.of(2025, 1, 1)),
        arrayOf(LocalDate.of(2025, 1, 2)),
        arrayOf(precise, null),
        arrayOf(precise),
        arrayOf(OffsetTime.of(1, 0, 0, 0, ZoneOffset.UTC)),
        arrayOf(OffsetTime.of(2, 0, 0, 0, ZoneOffset.UTC)),
        arrayOf(LocalDateTime.of(2025, 1, 1, 3, 0)),
        arrayOf(LocalDateTime.of(2025, 1, 1, 4, 0)),
        arrayOf(Instant.parse("2025-01-01T05:00:00Z")),
        arrayOf(Instant.parse("2025-01-01T06:00:00Z")),
        "array-target",
      )

      val row = row()
      assertThat(row.time_array_type!!.toList()).containsExactly(precise, null)
      assertThat(row.time_array_notnull_type.toList()).containsExactly(precise)
    }

    @Test
    fun `date timestamp and timestamptz arrays round-trip`() {
      val date = LocalDate.of(2025, 6, 15)
      val timestamp = LocalDateTime.of(2025, 6, 15, 13, 45, 30, 123_456_000)
      val moment = Instant.parse("2025-06-15T13:45:30.123456Z")

      queries.updateDateTimeArrays(
        arrayOf(date, null),
        arrayOf(date),
        arrayOf(LocalTime.of(1, 0)),
        arrayOf(LocalTime.of(2, 0)),
        arrayOf(OffsetTime.of(1, 0, 0, 0, ZoneOffset.UTC)),
        arrayOf(OffsetTime.of(2, 0, 0, 0, ZoneOffset.UTC)),
        arrayOf(timestamp, null),
        arrayOf(timestamp),
        arrayOf(moment, null),
        arrayOf(moment),
        "array-target",
      )

      val row = row()
      assertThat(row.date_array_type!!.toList()).containsExactly(date, null)
      assertThat(row.date_array_notnull_type.toList()).containsExactly(date)
      assertThat(row.timestamp_array_type!!.toList()).containsExactly(timestamp, null)
      assertThat(row.timestamp_array_notnull_type.toList()).containsExactly(timestamp)
      assertThat(row.timestamptz_array_type!!.toList()).containsExactly(moment, null)
      assertThat(row.timestamptz_array_notnull_type.toList()).containsExactly(moment)
    }

    @Test
    fun `null date time arrays bind as SQL NULL`() {
      val updated = queries.updateDateTimeArrays(
        null,
        arrayOf(LocalDate.of(2025, 1, 1)),
        null,
        arrayOf(LocalTime.of(1, 0)),
        null,
        arrayOf(OffsetTime.of(1, 0, 0, 0, ZoneOffset.UTC)),
        null,
        arrayOf(LocalDateTime.of(2025, 1, 1, 1, 0)),
        null,
        arrayOf(Instant.parse("2025-01-01T01:00:00Z")),
        "array-target",
      )

      assertThat(updated).isEqualTo(1)
      val row = row()
      assertThat(row.date_array_type).isNull()
      assertThat(row.time_array_type).isNull()
      assertThat(row.timetz_array_type).isNull()
      assertThat(row.timestamp_array_type).isNull()
      assertThat(row.timestamptz_array_type).isNull()
    }
  }

  @Nested
  inner class OtherArrays {

    private val first = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val second = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `jsonb array elements are parsed as jsonb, not stored verbatim`() {
      // Postgres normalizes jsonb whitespace: {"n":1} -> {"n": 1}. That normalization is itself the
      // evidence the element was parsed as jsonb rather than bound as character varying.
      val updated = queries.updateOtherArrays(
        arrayOf(true, null, false),
        arrayOf(true),
        arrayOf("""{"n":1}""", null),
        arrayOf("""{"n":2}"""),
        arrayOf("""{"n":1}""", null),
        arrayOf("""{"n":2}"""),
        arrayOf(byteArrayOf(1, 2, 3), null),
        arrayOf(byteArrayOf(4)),
        arrayOf(first, null),
        arrayOf(second),
        "array-target",
      )

      assertThat(updated).isEqualTo(1)
      val row = row()
      assertThat(row.jsonb_array_type!!.toList()).containsExactly("""{"n": 1}""", null)
      assertThat(row.jsonb_array_notnull_type.toList()).containsExactly("""{"n": 2}""")
    }

    @Test
    fun `json array elements bind as json, not character varying`() {
      // Postgres rejects a character varying[] parameter for a json[] column, so a successful update
      // is itself the evidence the element OID was named. json preserves input text verbatim, so the
      // values come back unnormalized — the one observable difference from jsonb[].
      val updated = queries.updateOtherArrays(
        arrayOf(true),
        arrayOf(true),
        arrayOf("""{"n":1}""", null),
        arrayOf("""{"n":2}"""),
        arrayOf("""{"n":1}"""),
        arrayOf("""{"n":2}"""),
        arrayOf(byteArrayOf(1)),
        arrayOf(byteArrayOf(4)),
        arrayOf(first),
        arrayOf(second),
        "array-target",
      )

      assertThat(updated).isEqualTo(1)
      val row = row()
      assertThat(row.json_array_type!!.toList()).containsExactly("""{"n":1}""", null)
      assertThat(row.json_array_notnull_type.toList()).containsExactly("""{"n":2}""")
    }

    @Test
    fun `bool bytea and uuid arrays round-trip`() {
      queries.updateOtherArrays(
        arrayOf(true, null, false),
        arrayOf(false),
        arrayOf("""{"n":1}"""),
        arrayOf("""{"n":2}"""),
        arrayOf("""{"n":1}"""),
        arrayOf("""{"n":2}"""),
        arrayOf(byteArrayOf(1, 2, 3), null),
        arrayOf(byteArrayOf(4)),
        arrayOf(first, null),
        arrayOf(second),
        "array-target",
      )

      val row = row()
      assertThat(row.bool_array_type!!.toList()).containsExactly(true, null, false)
      assertThat(row.bool_array_notnull_type.toList()).containsExactly(false)
      assertThat(row.bytea_array_type!!.map { it?.toList() })
        .containsExactly(listOf<Byte>(1, 2, 3), null)
      assertThat(row.bytea_array_notnull_type.map { it?.toList() }).containsExactly(listOf<Byte>(4))
      assertThat(row.uuid_array_type!!.toList()).containsExactly(first, null)
      assertThat(row.uuid_array_notnull_type.toList()).containsExactly(second)
    }

    @Test
    fun `null arrays bind as SQL NULL`() {
      val updated = queries.updateOtherArrays(
        null,
        arrayOf(true),
        null,
        arrayOf("""{"n":1}"""),
        null,
        arrayOf("""{"n":1}"""),
        null,
        arrayOf(byteArrayOf(1)),
        null,
        arrayOf(first),
        "array-target",
      )

      assertThat(updated).isEqualTo(1)
      val row = row()
      assertThat(row.bool_array_type).isNull()
      assertThat(row.json_array_type).isNull()
      assertThat(row.jsonb_array_type).isNull()
      assertThat(row.bytea_array_type).isNull()
      assertThat(row.uuid_array_type).isNull()
    }
  }
}
