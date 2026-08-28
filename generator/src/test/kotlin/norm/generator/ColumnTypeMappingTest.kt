package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isIn
import assertk.assertions.isTrue
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asTypeName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Blob
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetTime
import java.util.UUID

/**
 * Tests for PostgreSQL to Kotlin type mapping logic in TypeRepository.resolveMappableType().
 */
class ColumnTypeMappingTest {

  private val typeRepository = TypeRepository("test", Catalog())

  @Nested
  inner class IntegerTypes {
    @Test
    fun `nullable int column`() {
      val statement = createStatement(
        "SELECT age FROM person;",
        columns = listOf(column("age", type = "int4", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(Int::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `non-null int column`() {
      val statement = createStatement(
        "SELECT id FROM person;",
        columns = listOf(column("id", type = "int4", notNull = true)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(INT)
    }

    @Test
    fun `various postgres integer types map correctly`() {
      val intTypes = listOf("smallint", "int2", "integer", "int4", "bigint", "int8")

      for (type in intTypes) {
        val statement = createStatement(
          "SELECT val FROM t;",
          columns = listOf(column("val", type = type)),
        )
        assertThat(statement.resultRowShape.kotlinType?.toString())
          .isIn("kotlin.Short", "kotlin.Int", "kotlin.Long")
      }
    }

    @Test
    fun `pg_catalog integer maps to Int`() {
      // Closes a gap resolveBaseType previously had: pg_catalog.bool/varchar/bpchar were accepted,
      // but pg_catalog.integer was not, even though the bare "integer" spelling was.
      val statement = createStatement(
        "SELECT val FROM t;",
        columns = listOf(column("val", type = "pg_catalog.integer")),
      )
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(Int::class.asTypeName())
    }
  }

  @Nested
  inner class FloatTypes {
    @Test
    fun `various postgres float types map correctly`() {
      val floatTypes = listOf("real", "float4", "double precision", "float8")

      for (type in floatTypes) {
        val statement = createStatement(
          "SELECT val FROM t;",
          columns = listOf(column("val", type = type)),
        )
        assertThat(statement.resultRowShape.kotlinType?.toString())
          .isIn("kotlin.Float", "kotlin.Double")
      }
    }
  }

  @Nested
  inner class TextTypes {
    @Test
    fun `various postgres text types map correctly`() {
      val textTypes = listOf("text", "varchar", "bpchar")

      for (type in textTypes) {
        val statement = createStatement(
          "SELECT val FROM t;",
          columns = listOf(column("val", type = type)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(String::class.asTypeName())
      }
    }

    @Test
    fun `pg_catalog text maps to String`() {
      // Closes a gap resolveBaseType previously had: pg_catalog.varchar/bpchar were accepted, but
      // pg_catalog.text was not.
      val statement = createStatement(
        "SELECT val FROM t;",
        columns = listOf(column("val", type = "pg_catalog.text")),
      )
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(String::class.asTypeName())
    }
  }

  @Nested
  inner class BooleanTypes {
    @Test
    fun `boolean type maps correctly`() {
      val statement = createStatement(
        "SELECT active FROM user;",
        columns = listOf(column("active", type = "bool")),
      )
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(Boolean::class.asTypeName())
    }

    @Test
    fun `pg_catalog boolean maps to Boolean`() {
      // Closes a gap resolveBaseType previously had: pg_catalog.bool was accepted, but
      // pg_catalog.boolean was not.
      val statement = createStatement(
        "SELECT active FROM user;",
        columns = listOf(column("active", type = "pg_catalog.boolean")),
      )
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(Boolean::class.asTypeName())
    }
  }

  @Nested
  inner class DateTypes {
    @Test
    fun `date maps to LocalDate`() {
      val statement = createStatement(
        "SELECT birth_date FROM person;",
        columns = listOf(column("birth_date", type = "date")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(LocalDate::class.asTypeName())
    }

    @Test
    fun `pg_catalog date maps to LocalDate`() {
      val statement = createStatement(
        "SELECT birth_date FROM person;",
        columns = listOf(column("birth_date", type = "pg_catalog.date")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(LocalDate::class.asTypeName())
    }

    @Test
    fun `nullable date column`() {
      val statement = createStatement(
        "SELECT birth_date FROM person;",
        columns = listOf(column("birth_date", type = "date", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(LocalDate::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `non-null date column`() {
      val statement = createStatement(
        "SELECT birth_date FROM person;",
        columns = listOf(column("birth_date", type = "date", notNull = true)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(LocalDate::class.asTypeName())
    }
  }

  @Nested
  inner class TimeWithoutTimezoneTypes {
    @Test
    fun `time maps to LocalTime`() {
      val statement = createStatement(
        "SELECT start_time FROM schedule;",
        columns = listOf(column("start_time", type = "time")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(LocalTime::class.asTypeName())
    }

    @Test
    fun `pg_catalog time maps to LocalTime`() {
      val statement = createStatement(
        "SELECT start_time FROM schedule;",
        columns = listOf(column("start_time", type = "pg_catalog.time")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(LocalTime::class.asTypeName())
    }

    @Test
    fun `nullable time column`() {
      val statement = createStatement(
        "SELECT start_time FROM schedule;",
        columns = listOf(column("start_time", type = "time", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(LocalTime::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `non-null time column`() {
      val statement = createStatement(
        "SELECT start_time FROM schedule;",
        columns = listOf(column("start_time", type = "time", notNull = true)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(LocalTime::class.asTypeName())
    }
  }

  @Nested
  inner class TimeWithTimezoneTypes {
    @Test
    fun `timetz maps to OffsetTime`() {
      val statement = createStatement(
        "SELECT meeting_time FROM events;",
        columns = listOf(column("meeting_time", type = "timetz")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(OffsetTime::class.asTypeName())
    }

    @Test
    fun `pg_catalog timetz maps to OffsetTime`() {
      val statement = createStatement(
        "SELECT meeting_time FROM events;",
        columns = listOf(column("meeting_time", type = "pg_catalog.timetz")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(OffsetTime::class.asTypeName())
    }

    @Test
    fun `nullable timetz column`() {
      val statement = createStatement(
        "SELECT meeting_time FROM events;",
        columns = listOf(column("meeting_time", type = "timetz", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(OffsetTime::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `non-null timetz column`() {
      val statement = createStatement(
        "SELECT meeting_time FROM events;",
        columns = listOf(column("meeting_time", type = "timetz", notNull = true)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(OffsetTime::class.asTypeName())
    }
  }

  @Nested
  inner class TimestampWithoutTimezoneTypes {
    @Test
    fun `timestamp maps to LocalDateTime`() {
      val statement = createStatement(
        "SELECT created_at FROM records;",
        columns = listOf(column("created_at", type = "timestamp")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(LocalDateTime::class.asTypeName())
    }

    @Test
    fun `pg_catalog timestamp maps to LocalDateTime`() {
      val statement = createStatement(
        "SELECT created_at FROM records;",
        columns = listOf(column("created_at", type = "pg_catalog.timestamp")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(LocalDateTime::class.asTypeName())
    }

    @Test
    fun `nullable timestamp column`() {
      val statement = createStatement(
        "SELECT created_at FROM records;",
        columns = listOf(column("created_at", type = "timestamp", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(LocalDateTime::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `non-null timestamp column`() {
      val statement = createStatement(
        "SELECT created_at FROM records;",
        columns = listOf(column("created_at", type = "timestamp", notNull = true)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(LocalDateTime::class.asTypeName())
    }
  }

  @Nested
  inner class TimestampWithTimezoneTypes {
    @Test
    fun `timestamptz maps to Instant`() {
      val statement = createStatement(
        "SELECT updated_at FROM records;",
        columns = listOf(column("updated_at", type = "timestamptz")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(Instant::class.asTypeName())
    }

    @Test
    fun `pg_catalog timestamptz maps to Instant`() {
      val statement = createStatement(
        "SELECT updated_at FROM records;",
        columns = listOf(column("updated_at", type = "pg_catalog.timestamptz")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(Instant::class.asTypeName())
    }

    @Test
    fun `nullable timestamptz column`() {
      val statement = createStatement(
        "SELECT updated_at FROM records;",
        columns = listOf(column("updated_at", type = "timestamptz", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(Instant::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `non-null timestamptz column`() {
      val statement = createStatement(
        "SELECT updated_at FROM records;",
        columns = listOf(column("updated_at", type = "timestamptz", notNull = true)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(Instant::class.asTypeName())
    }
  }

  @Nested
  inner class UuidTypes {
    @Test
    fun `uuid maps to UUID`() {
      val statement = createStatement(
        "SELECT id FROM users;",
        columns = listOf(column("id", type = "uuid")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(UUID::class.asTypeName())
    }

    @Test
    fun `pg_catalog uuid maps to UUID`() {
      val statement = createStatement(
        "SELECT id FROM users;",
        columns = listOf(column("id", type = "pg_catalog.uuid")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(UUID::class.asTypeName())
    }

    @Test
    fun `nullable uuid column`() {
      val statement = createStatement(
        "SELECT id FROM users;",
        columns = listOf(column("id", type = "uuid", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(UUID::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `non-null uuid column`() {
      val statement = createStatement(
        "SELECT id FROM users;",
        columns = listOf(column("id", type = "uuid", notNull = true)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(UUID::class.asTypeName())
    }
  }

  @Nested
  inner class ByteaTypes {
    @Test
    fun `bytea maps to ByteArray`() {
      val statement = createStatement(
        "SELECT data FROM files;",
        columns = listOf(column("data", type = "bytea")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(ByteArray::class.asTypeName())
    }

    @Test
    fun `pg_catalog bytea maps to ByteArray`() {
      val statement = createStatement(
        "SELECT data FROM files;",
        columns = listOf(column("data", type = "pg_catalog.bytea")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(ByteArray::class.asTypeName())
    }

    @Test
    fun `nullable bytea column`() {
      val statement = createStatement(
        "SELECT data FROM files;",
        columns = listOf(column("data", type = "bytea", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(ByteArray::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `non-null bytea column`() {
      val statement = createStatement(
        "SELECT data FROM files;",
        columns = listOf(column("data", type = "bytea", notNull = true)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(ByteArray::class.asTypeName())
    }
  }

  @Nested
  inner class BlobTypes {
    @Test
    fun `oid maps to Blob`() {
      val statement = createStatement(
        "SELECT data FROM files;",
        columns = listOf(column("data", type = "oid")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(Blob::class.asTypeName())
    }

    @Test
    fun `pg_catalog oid maps to Blob`() {
      val statement = createStatement(
        "SELECT data FROM files;",
        columns = listOf(column("data", type = "pg_catalog.oid")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(Blob::class.asTypeName())
    }

    @Test
    fun `nullable oid column`() {
      val statement = createStatement(
        "SELECT data FROM files;",
        columns = listOf(column("data", type = "oid", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(Blob::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `non-null oid column`() {
      val statement = createStatement(
        "SELECT data FROM files;",
        columns = listOf(column("data", type = "oid", notNull = true)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(Blob::class.asTypeName())
    }
  }

  @Nested
  inner class ArrayTypes {

    @Nested
    inner class NumericArrays {
      @Test
      fun `boolean array maps to Array of Boolean`() {
        val statement = createStatement(
          "SELECT flags FROM settings;",
          columns = listOf(column("flags", type = "bool", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Boolean::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `smallint array maps to Array of Short`() {
        val statement = createStatement(
          "SELECT values FROM data;",
          columns = listOf(column("values", type = "int2", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Short::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `int array maps to Array of Int`() {
        val statement = createStatement(
          "SELECT tags FROM post;",
          columns = listOf(column("tags", type = "int4", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Int::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `bigint array maps to Array of Long`() {
        val statement = createStatement(
          "SELECT ids FROM batch;",
          columns = listOf(column("ids", type = "int8", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Long::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `float array maps to Array of Float`() {
        val statement = createStatement(
          "SELECT measurements FROM sensor;",
          columns = listOf(column("measurements", type = "float4", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Float::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `double array maps to Array of Double`() {
        val statement = createStatement(
          "SELECT coordinates FROM location;",
          columns = listOf(column("coordinates", type = "float8", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Double::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `oid array maps to Array of Long`() {
        val statement = createStatement(
          "SELECT owner_ids FROM object;",
          columns = listOf(column("owner_ids", type = "oid", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Long::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `pg_catalog oid array maps to Array of Long`() {
        val statement = createStatement(
          "SELECT owner_ids FROM object;",
          columns = listOf(column("owner_ids", type = "pg_catalog.oid", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Long::class.asTypeName().copy(nullable = true)))
      }
    }

    @Nested
    inner class GenericArrays {
      @Test
      fun `text array maps to Array of String`() {
        val statement = createStatement(
          "SELECT tags FROM post;",
          columns = listOf(column("tags", type = "text", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(String::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `varchar array maps to Array of String`() {
        val statement = createStatement(
          "SELECT labels FROM item;",
          columns = listOf(column("labels", type = "varchar", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(String::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `uuid array maps to Array of UUID`() {
        val statement = createStatement(
          "SELECT user_ids FROM group;",
          columns = listOf(column("user_ids", type = "uuid", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(UUID::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `date array maps to Array of LocalDate`() {
        val statement = createStatement(
          "SELECT holidays FROM calendar;",
          columns = listOf(column("holidays", type = "date", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(LocalDate::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `time array maps to Array of LocalTime`() {
        val statement = createStatement(
          "SELECT meeting_times FROM schedule;",
          columns = listOf(column("meeting_times", type = "time", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(LocalTime::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `timetz array maps to Array of OffsetTime`() {
        val statement = createStatement(
          "SELECT event_times FROM schedule;",
          columns = listOf(column("event_times", type = "timetz", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(OffsetTime::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `timestamp array maps to Array of LocalDateTime`() {
        val statement = createStatement(
          "SELECT created_dates FROM audit;",
          columns = listOf(column("created_dates", type = "timestamp", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(LocalDateTime::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `timestamptz array maps to Array of Instant`() {
        val statement = createStatement(
          "SELECT updated_dates FROM audit;",
          columns = listOf(column("updated_dates", type = "timestamptz", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Instant::class.asTypeName().copy(nullable = true)))
      }
    }

    @Nested
    inner class NullableArrays {
      @Test
      fun `nullable int array`() {
        val statement = createStatement(
          "SELECT tags FROM post;",
          columns = listOf(column("tags", type = "int4", notNull = false, isArray = true)),
        )
        val kotlinType = statement.resultRowShape.kotlinType!!
        assertThat(kotlinType.isNullable).isTrue()
        assertThat(kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Int::class.asTypeName().copy(nullable = true)).copy(nullable = true))
      }

      @Test
      fun `non-null int array`() {
        val statement = createStatement(
          "SELECT tags FROM post;",
          columns = listOf(column("tags", type = "int4", notNull = true, isArray = true)),
        )
        val kotlinType = statement.resultRowShape.kotlinType!!
        assertThat(kotlinType.isNullable).isFalse()
        assertThat(kotlinType).isEqualTo(ARRAY.parameterizedBy(Int::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `nullable text array`() {
        val statement = createStatement(
          "SELECT labels FROM item;",
          columns = listOf(column("labels", type = "text", notNull = false, isArray = true)),
        )
        val kotlinType = statement.resultRowShape.kotlinType!!
        assertThat(kotlinType.isNullable).isTrue()
        assertThat(kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(String::class.asTypeName().copy(nullable = true)).copy(nullable = true))
      }

      @Test
      fun `non-null text array`() {
        val statement = createStatement(
          "SELECT labels FROM item;",
          columns = listOf(column("labels", type = "text", notNull = true, isArray = true)),
        )
        val kotlinType = statement.resultRowShape.kotlinType!!
        assertThat(kotlinType.isNullable).isFalse()
        assertThat(kotlinType).isEqualTo(ARRAY.parameterizedBy(String::class.asTypeName().copy(nullable = true)))
      }

      @Test
      fun `nullable oid array`() {
        val statement = createStatement(
          "SELECT owner_ids FROM object;",
          columns = listOf(column("owner_ids", type = "oid", notNull = false, isArray = true)),
        )
        val kotlinType = statement.resultRowShape.kotlinType!!
        assertThat(kotlinType.isNullable).isTrue()
        assertThat(kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(Long::class.asTypeName().copy(nullable = true)).copy(nullable = true))
      }

      @Test
      fun `non-null oid array`() {
        val statement = createStatement(
          "SELECT owner_ids FROM object;",
          columns = listOf(column("owner_ids", type = "oid", notNull = true, isArray = true)),
        )
        val kotlinType = statement.resultRowShape.kotlinType!!
        assertThat(kotlinType.isNullable).isFalse()
        assertThat(kotlinType).isEqualTo(ARRAY.parameterizedBy(Long::class.asTypeName().copy(nullable = true)))
      }
    }
  }

  @Nested
  inner class ArrayResultSetAccessors {

    @Test
    fun `non-null int array reads element-wise with a wasNull check`() {
      val col = column("tags", type = "int4", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      val accessorString = accessor.toString()

      assertThat(accessorString).contains("getArray(1).")
      assertThat(accessorString).contains("norm.mapElements { getInt(2).takeUnless { wasNull() } }")
    }

    @Test
    fun `nullable int array uses a safe call before mapElements`() {
      val col = column("tags", type = "int4", isArray = true, notNull = false)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      val accessorString = accessor.toString()

      assertThat(accessorString).contains("getArray(1)?.")
      assertThat(accessorString).contains("norm.mapElements { getInt(2).takeUnless { wasNull() } }")
    }

    @Test
    fun `text array reads elements with getString`() {
      val col = column("labels", type = "text", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).contains("norm.mapElements { getString(2) }")
    }

    @Test
    fun `UUID array reads elements with getObject`() {
      val col = column("user_ids", type = "uuid", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .contains("norm.mapElements { getObject(2, java.util.UUID::class.java) }")
    }

    @Test
    fun `jsonb array reads elements with getString`() {
      val col = column("tags", type = "jsonb", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).contains("norm.mapElements { getString(2) }")
    }

    @Test
    fun `json array reads elements with getString`() {
      val col = column("payloads", type = "json", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).contains("norm.mapElements { getString(2) }")
    }

    @Test
    fun `date array reads elements as LocalDate`() {
      val col = column("holidays", type = "date", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .contains("norm.mapElements { getObject(2, java.time.LocalDate::class.java) }")
    }

    @Test
    fun `timetz array reads elements as OffsetTime, preserving the offset`() {
      val col = column("event_times", type = "timetz", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .contains("norm.mapElements { getObject(2, java.time.OffsetTime::class.java) }")
    }

    @Test
    fun `bytea array reads elements with getBytes`() {
      val col = column("blobs", type = "bytea", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).contains("norm.mapElements { getBytes(2) }")
    }

    @Test
    fun `non-null timestamptz array element read is null-safe`() {
      // A NOT NULL timestamptz[] column can still contain NULL elements, so the element read must
      // use the nullable InstantSqlMappable form. The non-null form would NPE on a NULL element.
      val col = column("moments", type = "timestamptz", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .contains("norm.mapElements { getObject(2, java.time.OffsetDateTime::class.java)?.toInstant() }")
    }

    @Test
    fun `non-null int array element read uses a wasNull check`() {
      // Same reason as the timestamptz case: getInt on a NULL element returns 0, not null.
      val col = column("tags", type = "int4", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).contains("getInt(2).takeUnless { wasNull() }")
    }

    @Test
    fun `array reads emit no unchecked cast`() {
      val col = column("tags", type = "int4", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).doesNotContain("UNCHECKED_CAST")
    }

    @Test
    fun `oid array reads elements with getLong and a wasNull check`() {
      // oid is an unsigned 32-bit integer; getInt throws on values above Int.MAX_VALUE, so the
      // element read must go through getLong, unlike the scalar oid column's Blob mapping.
      val col = column("owner_ids", type = "oid", isArray = true, notNull = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).contains("norm.mapElements { getLong(2).takeUnless { wasNull() } }")
    }
  }

  @Nested
  inner class PlainArrayStatementActions {

    @Test
    fun `non-null jsonb array writes via setArray and toSqlArray`() {
      val col = column("tags", type = "jsonb", isArray = true, notNull = true)
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("tags"))
      val setterString = setter.toString()

      assertThat(setterString).contains("setArray(1, ")
      assertThat(setterString).contains("""norm.toSqlArray(connection, "jsonb")""")
    }

    @Test
    fun `nullable jsonb array writes via setArray with setNull fallback`() {
      val col = column("tags", type = "jsonb", isArray = true, notNull = false)
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("tags"))
      val setterString = setter.toString()

      assertThat(setterString).contains("tags?.let { setArray(1, it.")
      assertThat(setterString).contains("""norm.toSqlArray(connection, "jsonb")""")
      assertThat(setterString).contains("?: setNull(1, java.sql.Types.ARRAY)")
    }

    @Test
    fun `json array writes with the json element type name`() {
      // A bare setObject would send character varying[], which Postgres rejects for json[].
      val col = column("payloads", type = "json", isArray = true, notNull = true)
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("payloads"))
      assertThat(setter.toString()).contains("""norm.toSqlArray(connection, "json")""")
    }

    @Test
    fun `date array writes with the date element type name`() {
      val col = column("holidays", type = "date", isArray = true, notNull = true)
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("holidays"))
      assertThat(setter.toString()).contains("""norm.toSqlArray(connection, "date")""")
    }

    @Test
    fun `timestamptz array writes with the timestamptz element type name`() {
      val col = column("moments", type = "timestamptz", isArray = true, notNull = true)
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("moments"))
      assertThat(setter.toString()).contains("""norm.toSqlArray(connection, "timestamptz")""")
    }

    @Test
    fun `int array writes with the canonical int4 element type name`() {
      val col = column("counts", type = "integer", isArray = true, notNull = true)
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("counts"))
      assertThat(setter.toString()).contains("""norm.toSqlArray(connection, "int4")""")
    }

    @Test
    fun `pg_catalog prefixed element type is canonicalized`() {
      val col = column("counts", type = "pg_catalog.int4", isArray = true, notNull = true)
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("counts"))
      assertThat(setter.toString()).contains("""norm.toSqlArray(connection, "int4")""")
    }

    @Test
    fun `bytea array writes with the bytea element type name`() {
      val col = column("blobs", type = "bytea", isArray = true, notNull = true)
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("blobs"))
      assertThat(setter.toString()).contains("""norm.toSqlArray(connection, "bytea")""")
    }

    @Test
    fun `oid array writes with the oid element type name`() {
      val col = column("owner_ids", type = "oid", isArray = true, notNull = true)
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("owner_ids"))
      assertThat(setter.toString()).contains("""norm.toSqlArray(connection, "oid")""")
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  inner class PostgresArrayElementTypeNames {

    @ParameterizedTest(name = "{0} folds to {1}")
    @MethodSource("foldCases")
    fun `folds a SQL-spelling alias, or a pg_catalog-prefixed name, to its canonical pg_type name`(
      sqlSpelling: String,
      canonicalName: String,
    ) {
      assertThat(postgresArrayElementTypeName(sqlSpelling)).isEqualTo(canonicalName)
    }

    fun foldCases(): List<Arguments> = listOf(
      // A pg_catalog-qualified name is stripped to its bare type name, whether or not that bare
      // name itself also needs folding (see "pg_catalog.int4" below).
      Arguments.of("pg_catalog.timestamptz", "timestamptz"),
      Arguments.of("integer", "int4"),
      Arguments.of("int", "int4"),
      Arguments.of("pg_catalog.int4", "int4"),
      Arguments.of("smallint", "int2"),
      Arguments.of("bigint", "int8"),
      Arguments.of("real", "float4"),
      Arguments.of("double precision", "float8"),
      Arguments.of("float", "float8"),
      Arguments.of("boolean", "bool"),
      Arguments.of("string", "text"),
      // Already-canonical names must pass through unchanged.
      Arguments.of("json", "json"),
      Arguments.of("jsonb", "jsonb"),
      Arguments.of("timetz", "timetz"),
      Arguments.of("uuid", "uuid"),
      Arguments.of("bytea", "bytea"),
      Arguments.of("numeric", "numeric"),
      Arguments.of("text", "text"),
      Arguments.of("varchar", "varchar"),
    )

    /**
     * Anti-drift sweep for [postgresArrayElementTypeName], the same intent as
     * [DomainBaseTypeAntiDriftSweep] but pinned against a HARDCODED, independently-verified
     * classification rather than [BASE_TYPE_RESOLVERS] membership. A membership check is a
     * tautology here: every [BASE_TYPE_RESOLVERS] key that is not folded still passes itself
     * through unchanged (`postgresArrayElementTypeName`'s `else` branch), and every SQL-spelling
     * alias is, by construction, also a [BASE_TYPE_RESOLVERS] key — so deleting every fold branch
     * would still leave every folded result a [BASE_TYPE_RESOLVERS] key and a membership check
     * green.
     *
     * [expectedCanonicalNameByAlias] and [alreadyCanonicalNames] below are hand-verified against a
     * live PostgreSQL 17 server via `SELECT typname FROM pg_type WHERE oid = to_regtype(?)` — see
     * [postgresArrayElementTypeName]'s KDoc — and never derived from [BASE_TYPE_RESOLVERS] or
     * [postgresArrayElementTypeName] themselves. The set-equality assertion catches a new
     * [BASE_TYPE_RESOLVERS] key added without being classified into either bucket; the per-alias
     * assertions catch a fold branch that is deleted, or wrong, by checking the ACTUAL fold result
     * against this table's fixed expectation rather than a self-referential set.
     *
     * Serial variants (`serial`, `bigserial`, ...) are excluded: [postgresArrayElementTypeName]'s
     * own KDoc documents that a serial column can never reach the array path, so they need no fold
     * and are outside this sweep's scope.
     */
    @Test
    fun `every SQL-spelling alias folds to its verified canonical pg_type name`() {
      val serialVariants = setOf("smallserial", "serial2", "serial", "serial4", "bigserial", "serial8")
      val expectedCanonicalNameByAlias = mapOf(
        "smallint" to "int2",
        "integer" to "int4",
        "int" to "int4",
        "bigint" to "int8",
        "real" to "float4",
        "double precision" to "float8",
        "float" to "float8",
        "boolean" to "bool",
        "string" to "text",
      )
      val alreadyCanonicalNames = setOf(
        "int2", "int4", "int8", "float4", "float8", "numeric", "bool",
        "json", "jsonb", "oid", "bytea", "date", "time", "timetz", "timestamp", "timestamptz",
        "text", "varchar", "bpchar", "uuid",
      )

      assertThat(BASE_TYPE_RESOLVERS.keys - serialVariants)
        .isEqualTo(expectedCanonicalNameByAlias.keys + alreadyCanonicalNames)

      expectedCanonicalNameByAlias.forEach { (alias, expectedCanonical) ->
        assertThat(postgresArrayElementTypeName(alias)).isEqualTo(expectedCanonical)
      }
      alreadyCanonicalNames.forEach { canonicalName ->
        assertThat(postgresArrayElementTypeName(canonicalName)).isEqualTo(canonicalName)
      }
    }
  }

  @Nested
  inner class TimestamptzAccessors {

    @Test
    fun `non-null timestamptz reads via OffsetDateTime and converts to Instant`() {
      val col = column("updated_at", type = "timestamptz")
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getObject(1, java.time.OffsetDateTime::class.java).toInstant()")
    }

    @Test
    fun `nullable timestamptz reads via OffsetDateTime with safe call`() {
      val col = column("updated_at", type = "timestamptz", notNull = false)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getObject(1, java.time.OffsetDateTime::class.java)?.toInstant()")
    }

    @Test
    fun `non-null timestamptz writes via OffsetDateTime ofInstant`() {
      val col = column("updated_at", type = "timestamptz")
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("updated_at"))
      assertThat(setter.toString())
        .isEqualTo("setObject(1, java.time.OffsetDateTime.ofInstant(updated_at, java.time.ZoneOffset.UTC))")
    }

    @Test
    fun `nullable timestamptz writes via OffsetDateTime with setNull fallback`() {
      val col = column("updated_at", type = "timestamptz", notNull = false)
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("updated_at"))
      val setterString = setter.toString()
      assertThat(setterString).contains("OffsetDateTime.ofInstant(it, java.time.ZoneOffset.UTC)")
      assertThat(setterString).contains("setNull(1,")
    }
  }

  @Nested
  inner class JsonAccessors {

    @Test
    fun `non-null jsonb writes via setObject with Types OTHER`() {
      val col = column("metadata", type = "jsonb")
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("metadata"))
      assertThat(setter.toString()).isEqualTo("setObject(1, metadata, java.sql.Types.OTHER)")
    }

    @Test
    fun `nullable jsonb writes via setObject with Types OTHER`() {
      // pgjdbc's setObject(index, null, targetSqlType) delegates to setNull(index, targetSqlType),
      // so the nullable case needs no separate branch.
      val col = column("metadata", type = "jsonb", notNull = false)
      val setter = typeRepository.resolveMappableType(col).statementAction(2, CodeBlock.of("metadata"))
      assertThat(setter.toString()).isEqualTo("setObject(2, metadata, java.sql.Types.OTHER)")
    }

    @Test
    fun `jsonb reads via getString`() {
      val col = column("metadata", type = "jsonb", notNull = false)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("getString(1)")
    }

    @Test
    fun `non-null jsonb column resolves to String`() {
      val col = column("metadata", type = "jsonb")
      assertThat(typeRepository.resolveColumnType(col)).isEqualTo(String::class.asTypeName())
    }

    @Test
    fun `pg_catalog jsonb column resolves to String`() {
      // Closes a gap resolveBaseType previously had: the json/jsonb branch had no pg_catalog.-
      // qualified literals at all, so pg_catalog.jsonb resolved to null rather than falling through.
      val col = column("metadata", type = "pg_catalog.jsonb")
      assertThat(typeRepository.resolveColumnType(col)).isEqualTo(String::class.asTypeName())
    }

    @Test
    fun `pg_catalog json column resolves to String`() {
      val col = column("payload", type = "pg_catalog.json")
      assertThat(typeRepository.resolveColumnType(col)).isEqualTo(String::class.asTypeName())
    }

    @Test
    fun `nullable jsonb column resolves to nullable String`() {
      val col = column("metadata", type = "jsonb", notNull = false)
      assertThat(typeRepository.resolveColumnType(col))
        .isEqualTo(String::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `jsonb array column still resolves to Array of nullable String`() {
      val col = column("tags", type = "jsonb", notNull = false, isArray = true)
      assertThat(typeRepository.resolveColumnType(col))
        .isEqualTo(ARRAY.parameterizedBy(String::class.asTypeName().copy(nullable = true)).copy(nullable = true))
    }

    @Test
    fun `non-null json writes via setObject with Types OTHER`() {
      // Postgres rejects setString() for json exactly as it does for jsonb: the parameter arrives as
      // character varying and there is no implicit cast to json.
      val col = column("payload", type = "json")
      val setter = typeRepository.resolveMappableType(col).statementAction(1, CodeBlock.of("payload"))
      assertThat(setter.toString()).isEqualTo("setObject(1, payload, java.sql.Types.OTHER)")
    }

    @Test
    fun `nullable json writes via setObject with Types OTHER`() {
      val col = column("payload", type = "json", notNull = false)
      val setter = typeRepository.resolveMappableType(col).statementAction(2, CodeBlock.of("payload"))
      assertThat(setter.toString()).isEqualTo("setObject(2, payload, java.sql.Types.OTHER)")
    }

    @Test
    fun `json reads via getString`() {
      val col = column("payload", type = "json", notNull = false)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("getString(1)")
    }

    @Test
    fun `non-null json column resolves to String`() {
      val col = column("payload", type = "json")
      assertThat(typeRepository.resolveColumnType(col)).isEqualTo(String::class.asTypeName())
    }

    @Test
    fun `nullable json column resolves to nullable String`() {
      val col = column("payload", type = "json", notNull = false)
      assertThat(typeRepository.resolveColumnType(col))
        .isEqualTo(String::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `json array column resolves to Array of nullable String`() {
      val col = column("payloads", type = "json", notNull = false, isArray = true)
      assertThat(typeRepository.resolveColumnType(col))
        .isEqualTo(ARRAY.parameterizedBy(String::class.asTypeName().copy(nullable = true)).copy(nullable = true))
    }
  }

  @Nested
  inner class EnumTypes {

    private val moodEnum = Enum(name = "mood", vals = listOf("happy", "sad", "angry"))
    private val enumCatalog = Catalog(schemas = listOf(Schema(name = "public", enums = listOf(moodEnum))))

    @Test
    fun `non-null enum column resolves to generated enum type`() {
      val statement = createStatement(
        "SELECT current_mood FROM person;",
        columns = listOf(column("current_mood", type = "mood")),
        catalog = enumCatalog,
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(ClassName("test", "Mood"))
    }

    @Test
    fun `nullable enum column resolves to nullable generated enum type`() {
      val statement = createStatement(
        "SELECT previous_mood FROM person;",
        columns = listOf(column("previous_mood", type = "mood", notNull = false)),
        catalog = enumCatalog,
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(ClassName("test", "Mood").copy(nullable = true))
    }

    @Test
    fun `nullable enum array column resolves to nullable Array of nullable enum type`() {
      val statement = createStatement(
        "SELECT moods FROM person;",
        columns = listOf(column("moods", type = "mood", isArray = true, notNull = false)),
        catalog = enumCatalog,
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      // Column is nullable, so the array itself is nullable. Elements are always nullable.
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType.toString()).isEqualTo("kotlin.Array<test.Mood?>?")
    }

    @Test
    fun `non-null enum array column resolves to non-null Array of nullable enum type`() {
      val statement = createStatement(
        "SELECT moods FROM person;",
        columns = listOf(column("moods", type = "mood", isArray = true, notNull = true)),
        catalog = enumCatalog,
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType.toString()).isEqualTo("kotlin.Array<test.Mood?>")
    }

    @Test
    fun `enum array resultSetAction delegates to decodeArray runtime helper`() {
      val repository = TypeRepository("test", enumCatalog)
      val col = column("moods", type = "mood", isArray = true)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).contains("getArray(1)")
      assertThat(accessor.toString()).contains("norm.decodeArray(moodAdapter)")
    }

    @Test
    fun `nullable enum array uses safe call to decodeArray`() {
      val repository = TypeRepository("test", enumCatalog)
      val col = column("moods", type = "mood", isArray = true, notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).contains("getArray(1)?.")
      assertThat(accessor.toString()).contains("norm.decodeArray(moodAdapter)")
    }

    @Test
    fun `enum array statementAction delegates to encodeToSqlArray runtime helper`() {
      val repository = TypeRepository("test", enumCatalog)
      val col = column("moods", type = "mood", isArray = true)
      val setter = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("moods"))
      assertThat(setter.toString()).contains("norm.encodeToSqlArray(connection, \"mood\", moodAdapter)")
    }

    @Test
    fun `non-null enum resultSetAction uses adapter decode`() {
      val repository = TypeRepository("test", enumCatalog)
      val col = column("current_mood", type = "mood")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("moodAdapter.decode(getString(1))")
    }

    @Test
    fun `nullable enum resultSetAction uses safe call with adapter`() {
      val repository = TypeRepository("test", enumCatalog)
      val col = column("previous_mood", type = "mood", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("getString(1)?.let { moodAdapter.decode(it) }")
    }

    @Test
    fun `non-null enum statementAction uses setObject with Types OTHER for Postgres enum coercion`() {
      val repository = TypeRepository("test", enumCatalog)
      val col = column("current_mood", type = "mood")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("current_mood"))
      // setObject(..., Types.OTHER) is required for Postgres enum columns — setString() is rejected
      // because the JDBC driver cannot implicitly coerce VARCHAR to a custom enum type.
      assertThat(action.toString()).isEqualTo("setObject(1, moodAdapter.encode(current_mood), java.sql.Types.OTHER)")
    }

    @Test
    fun `nullable enum statementAction uses setNull with Types OTHER fallback`() {
      val repository = TypeRepository("test", enumCatalog)
      val col = column("previous_mood", type = "mood", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("previous_mood"))
      assertThat(action.toString()).contains("moodAdapter.encode(")
      assertThat(action.toString()).contains("setNull(1, java.sql.Types.OTHER)")
    }

    @Test
    fun `enum with comment captures comment from catalog`() {
      val moodWithComment = Enum(
        name = "mood",
        vals = listOf("happy", "sad", "angry"),
        comment = "Represents emotional state of a person.",
      )
      val catalogWithComment =
        Catalog(schemas = listOf(Schema(name = "public", enums = listOf(moodWithComment))))

      // Create a statement that uses the enum column to trigger enum discovery
      createStatement(
        "SELECT current_mood FROM person;",
        columns = listOf(column("current_mood", type = "mood")),
        catalog = catalogWithComment,
      )

      val repository = TypeRepository("test", catalogWithComment)
      // Resolve the column to trigger enum discovery
      repository.resolveMappableType(column("current_mood", type = "mood"))

      val discoveredEnums = repository.discoveredEnums
      assertThat(discoveredEnums).contains(moodWithComment)

      val discoveredMood = discoveredEnums.first { it.name == "mood" }
      assertThat(discoveredMood.comment).isEqualTo("Represents emotional state of a person.")
    }
  }

  @Nested
  inner class DomainTypes {

    private val emailDomain = Domain(name = "email", baseType = "text")
    private val positiveIntDomain = Domain(name = "positive_integer", baseType = "int4")
    private val domainCatalog = Catalog(
      schemas = listOf(Schema(name = "public", domains = listOf(emailDomain, positiveIntDomain))),
    )

    @Test
    fun `non-null TEXT domain column resolves to generated value class type`() {
      val statement = createStatement(
        "SELECT email FROM users;",
        columns = listOf(column("email", type = "email")),
        catalog = domainCatalog,
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(ClassName("test", "Email"))
    }

    @Test
    fun `non-null INTEGER domain column resolves to generated value class type`() {
      val statement = createStatement(
        "SELECT age FROM users;",
        columns = listOf(column("age", type = "positive_integer")),
        catalog = domainCatalog,
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(ClassName("test", "PositiveInteger"))
    }

    @Test
    fun `nullable domain column resolves to nullable value class type`() {
      val statement = createStatement(
        "SELECT age FROM users;",
        columns = listOf(column("age", type = "positive_integer", notNull = false)),
        catalog = domainCatalog,
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(ClassName("test", "PositiveInteger").copy(nullable = true))
    }

    @Test
    fun `nullable domain array column resolves to nullable Array of nullable value class type`() {
      val statement = createStatement(
        "SELECT emails FROM users;",
        columns = listOf(column("emails", type = "email", isArray = true, notNull = false)),
        catalog = domainCatalog,
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType.toString()).isEqualTo("kotlin.Array<test.Email?>?")
    }

    @Test
    fun `non-null domain array column resolves to non-null Array of nullable value class type`() {
      val statement = createStatement(
        "SELECT emails FROM users;",
        columns = listOf(column("emails", type = "email", isArray = true, notNull = true)),
        catalog = domainCatalog,
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType.toString()).isEqualTo("kotlin.Array<test.Email?>")
    }

    @Test
    fun `domain array resultSetAction delegates to decodeArray runtime helper`() {
      val repository = TypeRepository("test", domainCatalog)
      val col = column("emails", type = "email", isArray = true)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).contains("getArray(1)")
      assertThat(accessor.toString()).contains("norm.decodeArray(emailAdapter)")
    }

    @Test
    fun `integer domain array also delegates to decodeArray`() {
      val repository = TypeRepository("test", domainCatalog)
      val col = column("scores", type = "positive_integer", isArray = true)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).contains("getArray(1)")
      assertThat(accessor.toString()).contains("norm.decodeArray(positiveIntegerAdapter)")
    }

    @Test
    fun `resolving a domain column populates discoveredDomains`() {
      val repository = TypeRepository("test", domainCatalog)
      repository.resolveMappableType(column("email", type = "email"))

      val discoveredDomains = repository.discoveredDomains
      assertThat(discoveredDomains).contains(emailDomain)
    }

    @Test
    fun `domain with unsupported base type throws error with helpful message`() {
      val xmlDomain = Domain(name = "xml_doc", baseType = "xml")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(xmlDomain))))
      val repository = TypeRepository("test", catalog)

      val exception = assertThrows<IllegalStateException> {
        repository.resolveMappableType(column("doc", type = "xml_doc"))
      }
      assertThat(exception.message!!).contains("unsupported base type")
      assertThat(exception.message!!).contains("xml")
    }

    @Test
    fun `non-null TEXT domain resultSetAction uses adapter decode with getString`() {
      val repository = TypeRepository("test", domainCatalog)
      val col = column("email", type = "email")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("emailAdapter.decode(getString(1))")
    }

    @Test
    fun `nullable TEXT domain resultSetAction uses safe call with adapter`() {
      val repository = TypeRepository("test", domainCatalog)
      val col = column("email", type = "email", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("getString(1)?.let { emailAdapter.decode(it) }")
    }

    @Test
    fun `non-null INTEGER domain resultSetAction uses adapter decode with getInt`() {
      val repository = TypeRepository("test", domainCatalog)
      val col = column("age", type = "positive_integer")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("positiveIntegerAdapter.decode(getInt(1))")
    }

    @Test
    fun `nullable INTEGER domain resultSetAction uses wasNull check`() {
      val repository = TypeRepository("test", domainCatalog)
      val col = column("age", type = "positive_integer", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getInt(1).takeUnless { wasNull() }?.let { positiveIntegerAdapter.decode(it) }")
    }

    @Test
    fun `non-null TEXT domain statementAction uses adapter encode with setString`() {
      val repository = TypeRepository("test", domainCatalog)
      val col = column("email", type = "email")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("email"))
      assertThat(action.toString()).isEqualTo("setString(1, emailAdapter.encode(email))")
    }

    @Test
    fun `nullable TEXT domain statementAction uses setNull fallback`() {
      val repository = TypeRepository("test", domainCatalog)
      val col = column("email", type = "email", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("email"))
      assertThat(action.toString()).contains("emailAdapter.encode(")
      assertThat(action.toString()).contains("setNull(1,")
    }

    @Test
    fun `non-null INTEGER domain statementAction uses adapter encode with setInt`() {
      val repository = TypeRepository("test", domainCatalog)
      val col = column("age", type = "positive_integer")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("age"))
      assertThat(action.toString()).isEqualTo("setInt(1, positiveIntegerAdapter.encode(age))")
    }

    @Test
    fun `nullable INTEGER domain statementAction uses setNull fallback`() {
      val repository = TypeRepository("test", domainCatalog)
      val col = column("age", type = "positive_integer", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("age"))
      assertThat(action.toString()).contains("positiveIntegerAdapter.encode(")
      assertThat(action.toString()).contains("setNull(1,")
    }
  }

  /**
   * Tests for domain base types beyond TEXT and INTEGER.
   *
   * Each base type exercises both [resolveJdbcTypeInfo] (JDBC method metadata) and
   * [domainKotlinBaseType] (Kotlin type mapping). These two functions must stay in sync —
   * a type supported in one but not the other is a bug.
   */
  @Nested
  inner class DomainBaseTypes {

    @Test
    fun `SMALLINT domain resolves to Short value class`() {
      val domain = Domain(name = "small_count", baseType = "int2")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "small_count")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "SmallCount"))
    }

    @Test
    fun `nullable SMALLINT domain resultSetAction uses wasNull check`() {
      val domain = Domain(name = "small_count", baseType = "int2")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "small_count", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getShort(1).takeUnless { wasNull() }?.let { smallCountAdapter.decode(it) }")
    }

    @Test
    fun `non-null SMALLINT domain statementAction uses setShort`() {
      val domain = Domain(name = "small_count", baseType = "int2")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "small_count")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("count"))
      assertThat(action.toString()).isEqualTo("setShort(1, smallCountAdapter.encode(count))")
    }

    @Test
    fun `json domain resolves to a value class bound with Types OTHER`() {
      val domain = Domain(name = "json_doc", baseType = "json")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("payload", type = "json_doc")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "JsonDoc"))
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("payload"))
      assertThat(action.toString())
        .isEqualTo("setObject(1, jsonDocAdapter.encode(payload), java.sql.Types.OTHER)")
    }

    @Test
    fun `nullable json domain reads through getString`() {
      val domain = Domain(name = "json_doc", baseType = "json")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("payload", type = "json_doc", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("getString(1)?.let { jsonDocAdapter.decode(it) }")
    }

    @Test
    fun `BIGINT domain resolves to Long value class`() {
      val domain = Domain(name = "big_count", baseType = "int8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "big_count")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "BigCount"))
    }

    @Test
    fun `nullable BIGINT domain resultSetAction uses wasNull check`() {
      val domain = Domain(name = "big_count", baseType = "int8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "big_count", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getLong(1).takeUnless { wasNull() }?.let { bigCountAdapter.decode(it) }")
    }

    @Test
    fun `non-null BIGINT domain statementAction uses setLong`() {
      val domain = Domain(name = "big_count", baseType = "int8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "big_count")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("count"))
      assertThat(action.toString()).isEqualTo("setLong(1, bigCountAdapter.encode(count))")
    }

    @Test
    fun `FLOAT domain resolves to Float value class`() {
      val domain = Domain(name = "latitude", baseType = "float4")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lat", type = "latitude")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "Latitude"))
    }

    @Test
    fun `nullable FLOAT domain resultSetAction uses wasNull check`() {
      val domain = Domain(name = "latitude", baseType = "float4")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lat", type = "latitude", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getFloat(1).takeUnless { wasNull() }?.let { latitudeAdapter.decode(it) }")
    }

    @Test
    fun `nullable FLOAT domain statementAction uses setNull with REAL`() {
      val domain = Domain(name = "latitude", baseType = "float4")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lat", type = "latitude", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("lat"))
      assertThat(action.toString()).contains("setFloat(")
      assertThat(action.toString()).contains("Types.REAL")
    }

    @Test
    fun `DOUBLE domain resolves to Double value class`() {
      val domain = Domain(name = "longitude", baseType = "float8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lng", type = "longitude")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "Longitude"))
    }

    @Test
    fun `nullable DOUBLE domain resultSetAction uses wasNull check`() {
      val domain = Domain(name = "longitude", baseType = "float8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lng", type = "longitude", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getDouble(1).takeUnless { wasNull() }?.let { longitudeAdapter.decode(it) }")
    }

    @Test
    fun `nullable DOUBLE domain statementAction uses setNull with DOUBLE`() {
      val domain = Domain(name = "longitude", baseType = "float8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lng", type = "longitude", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("lng"))
      assertThat(action.toString()).contains("setDouble(")
      assertThat(action.toString()).contains("Types.DOUBLE")
    }

    @Test
    fun `BOOLEAN domain resolves to Boolean value class`() {
      val domain = Domain(name = "active_flag", baseType = "bool")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("active", type = "active_flag")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "ActiveFlag"))
    }

    @Test
    fun `nullable BOOLEAN domain resultSetAction uses wasNull check`() {
      val domain = Domain(name = "active_flag", baseType = "bool")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("active", type = "active_flag", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getBoolean(1).takeUnless { wasNull() }?.let { activeFlagAdapter.decode(it) }")
    }

    @Test
    fun `nullable BOOLEAN domain statementAction uses setNull with BOOLEAN`() {
      val domain = Domain(name = "active_flag", baseType = "bool")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("active", type = "active_flag", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("active"))
      assertThat(action.toString()).contains("setBoolean(")
      assertThat(action.toString()).contains("Types.BOOLEAN")
    }

    @Test
    fun `NUMERIC domain resolves to BigDecimal-backed value class`() {
      val domain = Domain(name = "currency_amount", baseType = "numeric")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("amount", type = "currency_amount")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "CurrencyAmount"))
    }

    @Test
    fun `nullable NUMERIC domain resultSetAction uses safe call without wasNull`() {
      val domain = Domain(name = "currency_amount", baseType = "numeric")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("amount", type = "currency_amount", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      // numeric is non-primitive, so uses ?.let instead of wasNull() check
      assertThat(accessor.toString())
        .isEqualTo("getBigDecimal(1)?.let { currencyAmountAdapter.decode(it) }")
    }

    @Test
    fun `nullable NUMERIC domain statementAction uses setNull with NUMERIC`() {
      val domain = Domain(name = "currency_amount", baseType = "numeric")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("amount", type = "currency_amount", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("amount"))
      assertThat(action.toString()).contains("setBigDecimal(")
      assertThat(action.toString()).contains("Types.NUMERIC")
    }

    @Test
    fun `VARCHAR domain resolves same as TEXT domain`() {
      val domain = Domain(name = "username", baseType = "varchar")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("name", type = "username")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("usernameAdapter.decode(getString(1))")
    }

    @Test
    fun `BPCHAR domain resolves same as TEXT domain`() {
      val domain = Domain(name = "country_code", baseType = "bpchar")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("code", type = "country_code")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("countryCodeAdapter.decode(getString(1))")
    }
  }

  /**
   * Regression coverage for the build-breaking bug: `CREATE DOMAIN d AS timestamptz` (or `uuid`,
   * `date`, `time`, `timetz`, `bytea`, `oid`) aborted code generation entirely, because
   * [resolveJdbcTypeInfo] had no entry for any of these types even though
   * [TypeRepository.resolveBaseType] supports every one of them as a plain column type. Each read
   * assertion below is verified against pgjdbc 42.7.13's actual `PgResultSet`/`PgPreparedStatement`
   * source (see [resolveJdbcTypeInfo]'s KDoc for the specific methods checked), not assumed.
   */
  @Nested
  inner class DomainOverJavaTimeAndOtherNonPrimitiveBaseTypes {

    @Test
    fun `DATE domain resolves to LocalDate value class`() {
      val domain = Domain(name = "preferred_date", baseType = "date")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("day", type = "preferred_date")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "PreferredDate"))
    }

    @Test
    fun `non-null DATE domain resultSetAction uses the class-qualified getObject overload`() {
      val domain = Domain(name = "preferred_date", baseType = "date")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("day", type = "preferred_date")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("preferredDateAdapter.decode(getObject(1, java.time.LocalDate::class.java))")
    }

    @Test
    fun `nullable DATE domain resultSetAction uses a safe call, not a wasNull check`() {
      val domain = Domain(name = "preferred_date", baseType = "date")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("day", type = "preferred_date", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getObject(1, java.time.LocalDate::class.java)?.let { preferredDateAdapter.decode(it) }")
    }

    @Test
    fun `non-null DATE domain statementAction uses plain setObject`() {
      val domain = Domain(name = "preferred_date", baseType = "date")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("day", type = "preferred_date")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("day"))
      assertThat(action.toString()).isEqualTo("setObject(1, preferredDateAdapter.encode(day))")
    }

    @Test
    fun `nullable DATE domain statementAction uses setNull with DATE`() {
      val domain = Domain(name = "preferred_date", baseType = "date")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("day", type = "preferred_date", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("day"))
      assertThat(action.toString()).contains("setObject(1, preferredDateAdapter.encode(it))")
      assertThat(action.toString()).contains("setNull(1, java.sql.Types.DATE)")
    }

    @Test
    fun `TIME domain resultSetAction uses the class-qualified getObject overload`() {
      val domain = Domain(name = "opening_time", baseType = "time")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("start", type = "opening_time")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("openingTimeAdapter.decode(getObject(1, java.time.LocalTime::class.java))")
    }

    @Test
    fun `TIMETZ domain resultSetAction uses the class-qualified getObject overload`() {
      val domain = Domain(name = "meeting_time_tz", baseType = "timetz")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("start", type = "meeting_time_tz")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("meetingTimeTzAdapter.decode(getObject(1, java.time.OffsetTime::class.java))")
    }

    @Test
    fun `nullable TIMETZ domain statementAction uses setNull with TIME_WITH_TIMEZONE`() {
      val domain = Domain(name = "meeting_time_tz", baseType = "timetz")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("start", type = "meeting_time_tz", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("start"))
      assertThat(action.toString()).contains("setNull(1, java.sql.Types.TIME_WITH_TIMEZONE)")
    }

    @Test
    fun `TIMESTAMP domain resultSetAction uses the class-qualified getObject overload`() {
      val domain = Domain(name = "created_at_local", baseType = "timestamp")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("created", type = "created_at_local")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("createdAtLocalAdapter.decode(getObject(1, java.time.LocalDateTime::class.java))")
    }

    @Test
    fun `TIMESTAMPTZ domain resolves to Instant value class`() {
      val domain = Domain(name = "occurred_at", baseType = "timestamptz")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("moment", type = "occurred_at")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "OccurredAt"))
    }

    @Test
    fun `non-null TIMESTAMPTZ domain resultSetAction reads OffsetDateTime and converts to Instant`() {
      val domain = Domain(name = "occurred_at", baseType = "timestamptz")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("moment", type = "occurred_at")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("occurredAtAdapter.decode(getObject(1, java.time.OffsetDateTime::class.java).toInstant())")
    }

    @Test
    fun `nullable TIMESTAMPTZ domain resultSetAction chains safe calls through toInstant`() {
      val domain = Domain(name = "occurred_at", baseType = "timestamptz")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("moment", type = "occurred_at", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo(
        "getObject(1, java.time.OffsetDateTime::class.java)?.toInstant()?.let { occurredAtAdapter.decode(it) }",
      )
    }

    @Test
    fun `non-null TIMESTAMPTZ domain statementAction converts the encoded Instant back to OffsetDateTime`() {
      val domain = Domain(name = "occurred_at", baseType = "timestamptz")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("moment", type = "occurred_at")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("moment"))
      assertThat(action.toString()).isEqualTo(
        "setObject(1, java.time.OffsetDateTime.ofInstant(occurredAtAdapter.encode(moment), java.time.ZoneOffset.UTC))",
      )
    }

    @Test
    fun `nullable TIMESTAMPTZ domain statementAction uses setNull with TIMESTAMP_WITH_TIMEZONE`() {
      val domain = Domain(name = "occurred_at", baseType = "timestamptz")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("moment", type = "occurred_at", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("moment"))
      assertThat(action.toString())
        .contains("java.time.OffsetDateTime.ofInstant(occurredAtAdapter.encode(it), java.time.ZoneOffset.UTC)")
      assertThat(action.toString()).contains("setNull(1, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)")
    }

    @Test
    fun `UUID domain resolves to UUID value class`() {
      val domain = Domain(name = "external_id", baseType = "uuid")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("id", type = "external_id")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "ExternalId"))
    }

    @Test
    fun `non-null UUID domain resultSetAction uses the class-qualified getObject overload`() {
      // java.sql.ResultSet.getObject(int) is declared to return Object, so a bare getObject(index)
      // call is statically Any in Kotlin — it will not compile as an argument to
      // ColumnAdapter<ExternalId, UUID>.decode, even though pgjdbc's plain getObject(int) does
      // return a java.util.UUID instance at runtime for a uuid column (verified against pgjdbc's
      // PgResultSet.internalGetObject). The class-qualified overload fixes the static type.
      val domain = Domain(name = "external_id", baseType = "uuid")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("id", type = "external_id")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("externalIdAdapter.decode(getObject(1, java.util.UUID::class.java))")
    }

    @Test
    fun `nullable UUID domain resultSetAction uses a safe call through the class-qualified getObject`() {
      val domain = Domain(name = "external_id", baseType = "uuid")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("id", type = "external_id", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getObject(1, java.util.UUID::class.java)?.let { externalIdAdapter.decode(it) }")
    }

    @Test
    fun `non-null UUID domain statementAction uses plain setObject`() {
      // pgjdbc's setObject(int, Object) dispatches on UUID directly (verified against pgjdbc's
      // PgPreparedStatement.setObject), so no Types hint is needed for the non-null case.
      val domain = Domain(name = "external_id", baseType = "uuid")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("id", type = "external_id")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("id"))
      assertThat(action.toString()).isEqualTo("setObject(1, externalIdAdapter.encode(id))")
    }

    @Test
    fun `nullable UUID domain statementAction uses setNull with OTHER`() {
      val domain = Domain(name = "external_id", baseType = "uuid")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("id", type = "external_id", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("id"))
      assertThat(action.toString()).contains("setObject(1, externalIdAdapter.encode(it))")
      assertThat(action.toString()).contains("setNull(1, java.sql.Types.OTHER)")
    }

    @Test
    fun `BYTEA domain resolves to ByteArray value class`() {
      val domain = Domain(name = "thumbnail", baseType = "bytea")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("data", type = "thumbnail")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "Thumbnail"))
    }

    @Test
    fun `non-null BYTEA domain resultSetAction uses getBytes`() {
      val domain = Domain(name = "thumbnail", baseType = "bytea")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("data", type = "thumbnail")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("thumbnailAdapter.decode(getBytes(1))")
    }

    @Test
    fun `non-null BYTEA domain statementAction uses setBytes`() {
      val domain = Domain(name = "thumbnail", baseType = "bytea")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("data", type = "thumbnail")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("data"))
      assertThat(action.toString()).isEqualTo("setBytes(1, thumbnailAdapter.encode(data))")
    }

    @Test
    fun `nullable BYTEA domain statementAction uses setNull with BINARY`() {
      val domain = Domain(name = "thumbnail", baseType = "bytea")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("data", type = "thumbnail", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("data"))
      assertThat(action.toString()).contains("setBytes(1, thumbnailAdapter.encode(it))")
      assertThat(action.toString()).contains("setNull(1, java.sql.Types.BINARY)")
    }

    @Test
    fun `OID domain resolves to Blob value class`() {
      val domain = Domain(name = "large_object_ref", baseType = "oid")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("ref", type = "large_object_ref")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "LargeObjectRef"))
    }

    @Test
    fun `non-null OID domain resultSetAction uses getBlob`() {
      val domain = Domain(name = "large_object_ref", baseType = "oid")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("ref", type = "large_object_ref")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("largeObjectRefAdapter.decode(getBlob(1))")
    }

    @Test
    fun `non-null OID domain statementAction uses setBlob`() {
      val domain = Domain(name = "large_object_ref", baseType = "oid")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("ref", type = "large_object_ref")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("ref"))
      assertThat(action.toString()).isEqualTo("setBlob(1, largeObjectRefAdapter.encode(ref))")
    }

    @Test
    fun `nullable OID domain statementAction uses setNull with BLOB`() {
      val domain = Domain(name = "large_object_ref", baseType = "oid")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("ref", type = "large_object_ref", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("ref"))
      assertThat(action.toString()).contains("setBlob(1, largeObjectRefAdapter.encode(it))")
      assertThat(action.toString()).contains("setNull(1, java.sql.Types.BLOB)")
    }
  }

  /**
   * Direct tests for [resolveJdbcTypeInfo], verifying the JDBC method metadata
   * for each supported Postgres base type.
   *
   * These tests ensure that the getter/setter names, primitivity flags, and SQL type constants
   * are correct for each base type. A mistake here would generate code that compiles but uses
   * the wrong JDBC method at runtime.
   */
  @Nested
  inner class DomainBaseTypeResolution {

    @Test
    fun `text resolves to getString and setString`() {
      val info = resolveJdbcTypeInfo("text")!!
      assertThat(info.getterName).isEqualTo("getString")
      assertThat(info.setterName).isEqualTo("setString")
      assertThat(info.isPrimitive).isFalse()
      assertThat(info.sqlTypeConstant).isEqualTo("VARCHAR")
    }

    @Test
    fun `varchar resolves same as text`() {
      val info = resolveJdbcTypeInfo("varchar")!!
      assertThat(info.getterName).isEqualTo("getString")
      assertThat(info.setterName).isEqualTo("setString")
      assertThat(info.isPrimitive).isFalse()
      assertThat(info.sqlTypeConstant).isEqualTo("VARCHAR")
    }

    @Test
    fun `bpchar resolves same as text`() {
      val info = resolveJdbcTypeInfo("bpchar")!!
      assertThat(info.getterName).isEqualTo("getString")
      assertThat(info.sqlTypeConstant).isEqualTo("VARCHAR")
    }

    @Test
    fun `int2 resolves to getShort and setShort`() {
      val info = resolveJdbcTypeInfo("int2")!!
      assertThat(info.getterName).isEqualTo("getShort")
      assertThat(info.setterName).isEqualTo("setShort")
      assertThat(info.isPrimitive).isTrue()
      assertThat(info.sqlTypeConstant).isEqualTo("SMALLINT")
    }

    @Test
    fun `int4 resolves to getInt and setInt`() {
      val info = resolveJdbcTypeInfo("int4")!!
      assertThat(info.getterName).isEqualTo("getInt")
      assertThat(info.setterName).isEqualTo("setInt")
      assertThat(info.isPrimitive).isTrue()
      assertThat(info.sqlTypeConstant).isEqualTo("INTEGER")
    }

    @Test
    fun `int8 resolves to getLong and setLong`() {
      val info = resolveJdbcTypeInfo("int8")!!
      assertThat(info.getterName).isEqualTo("getLong")
      assertThat(info.setterName).isEqualTo("setLong")
      assertThat(info.isPrimitive).isTrue()
      assertThat(info.sqlTypeConstant).isEqualTo("BIGINT")
    }

    @Test
    fun `float4 resolves to getFloat and setFloat`() {
      val info = resolveJdbcTypeInfo("float4")!!
      assertThat(info.getterName).isEqualTo("getFloat")
      assertThat(info.setterName).isEqualTo("setFloat")
      assertThat(info.isPrimitive).isTrue()
      assertThat(info.sqlTypeConstant).isEqualTo("REAL")
    }

    @Test
    fun `float8 resolves to getDouble and setDouble`() {
      val info = resolveJdbcTypeInfo("float8")!!
      assertThat(info.getterName).isEqualTo("getDouble")
      assertThat(info.setterName).isEqualTo("setDouble")
      assertThat(info.isPrimitive).isTrue()
      assertThat(info.sqlTypeConstant).isEqualTo("DOUBLE")
    }

    @Test
    fun `bool resolves to getBoolean and setBoolean`() {
      val info = resolveJdbcTypeInfo("bool")!!
      assertThat(info.getterName).isEqualTo("getBoolean")
      assertThat(info.setterName).isEqualTo("setBoolean")
      assertThat(info.isPrimitive).isTrue()
      assertThat(info.sqlTypeConstant).isEqualTo("BOOLEAN")
    }

    @Test
    fun `numeric resolves to getBigDecimal and setBigDecimal`() {
      val info = resolveJdbcTypeInfo("numeric")!!
      assertThat(info.getterName).isEqualTo("getBigDecimal")
      assertThat(info.setterName).isEqualTo("setBigDecimal")
      assertThat(info.isPrimitive).isFalse()
      assertThat(info.sqlTypeConstant).isEqualTo("NUMERIC")
    }

    @Test
    fun `jsonb resolves to getString and setObject with Types OTHER`() {
      val info = resolveJdbcTypeInfo("jsonb")!!
      assertThat(info.getterName).isEqualTo("getString")
      // setObject(..., Types.OTHER) is required — Postgres JDBC rejects setString() for jsonb columns
      assertThat(info.setterName).isEqualTo("setObject")
      assertThat(info.isPrimitive).isFalse()
      assertThat(info.sqlTypeConstant).isEqualTo("OTHER")
      assertThat(info.useSqlTypeHint).isTrue()
    }

    @Test
    fun `json resolves to getString and setObject with Types OTHER`() {
      val info = resolveJdbcTypeInfo("json")!!
      assertThat(info.getterName).isEqualTo("getString")
      assertThat(info.setterName).isEqualTo("setObject")
      assertThat(info.isPrimitive).isFalse()
      assertThat(info.sqlTypeConstant).isEqualTo("OTHER")
      assertThat(info.useSqlTypeHint).isTrue()
    }

    @Test
    fun `uuid resolves to getObject and setObject with a UUID class hint`() {
      val info = resolveJdbcTypeInfo("uuid")!!
      assertThat(info.getterName).isEqualTo("getObject")
      assertThat(info.setterName).isEqualTo("setObject")
      assertThat(info.isPrimitive).isFalse()
      assertThat(info.sqlTypeConstant).isEqualTo("OTHER")
      assertThat(info.getterClassHint).isEqualTo(ClassName("java.util", "UUID"))
    }

    @Test
    fun `unsupported type returns null`() {
      // xml has no entry anywhere -- Norm has never mapped it to a Kotlin type, as a plain column
      // type or a domain base. bytea IS supported (see BASE_TYPE_RESOLVERS/DomainBaseTypes below) --
      // it used to return null here, which is exactly the bug this fix closes: CREATE DOMAIN d AS
      // bytea aborted code generation entirely.
      assertThat(resolveJdbcTypeInfo("xml")).isEqualTo(null)
    }
  }

  /**
   * Anti-drift sweep for the bug where a domain over a common base type (e.g. `CREATE DOMAIN d AS
   * timestamptz`) aborted code generation: [resolveJdbcTypeInfo] must have an entry for EVERY
   * canonical type name [BASE_TYPE_RESOLVERS] accepts, since [TypeRepository]'s domain resolution
   * chains through [resolveJdbcTypeInfo] for the domain's base type. The corpus is
   * [BASE_TYPE_RESOLVERS]'s own keys -- the exact set [TypeRepository.resolveBaseType] accepts --
   * rather than a hand-copied list here that could independently drift from it.
   */
  @Nested
  inner class DomainBaseTypeAntiDriftSweep {

    @Test
    fun `every canonical base type resolveBaseType accepts has a resolveJdbcTypeInfo entry`() {
      val unsupported = BASE_TYPE_RESOLVERS.keys.filter { resolveJdbcTypeInfo(it) == null }
      assertThat(unsupported).isEmpty()
    }
  }

  /**
   * Regression coverage for the build-breaking bug: the `uuid` entry in [resolveJdbcTypeInfo] used
   * the generic `"getObject"` getter with no [JdbcTypeInfo.getterClassHint], which generates a bare
   * `getObject(index)` read. `java.sql.ResultSet.getObject(int)` is declared to return `Object`, so
   * that read is statically `Any` in Kotlin no matter what concrete type pgjdbc's `PgResultSet`
   * returns at runtime — it does not compile as an argument to `ColumnAdapter<Application,
   * Wire>.decode`. This sweeps every entry [resolveJdbcTypeInfo] can produce so a future type added
   * with the same mistake fails immediately, rather than surfacing only when a generated scenario
   * happens to be compiled.
   */
  @Nested
  inner class GetObjectReadsRequireAClassHint {

    @Test
    fun `every resolveJdbcTypeInfo entry using the generic getObject getter supplies a class hint`() {
      val entriesMissingAClassHint = BASE_TYPE_RESOLVERS.keys
        .mapNotNull { resolveJdbcTypeInfo(it) }
        .filter { it.getterName == "getObject" && it.getterClassHint == null }

      assertThat(entriesMissingAClassHint).isEmpty()
    }
  }

  @Nested
  inner class UserTypeMappings {

    private val moodEnum = Enum(name = "mood", vals = listOf("happy", "sad", "angry"))
    private val emailDomain = Domain(name = "email", baseType = "text")
    private val positiveIntDomain = Domain(name = "positive_integer", baseType = "int4")

    @Test
    fun `type-level override on enum type`() {
      val catalog = Catalog(schemas = listOf(Schema(name = "public", enums = listOf(moodEnum))))
      val mappings = listOf(
        TypeMapping("mood", null, null, "com.example.CustomMood", "com.example.CustomMoodAdapter"),
      )
      val repository = TypeRepository("test", catalog, mappings)
      val col = column("current_mood", type = "mood")
      val kotlinType = repository.resolveColumnType(col)
      assertThat(kotlinType).isEqualTo(ClassName("com.example", "CustomMood"))
    }

    @Test
    fun `type-level override on domain type`() {
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(emailDomain))))
      val mappings = listOf(
        TypeMapping("email", null, null, "com.example.CustomEmail", "com.example.CustomEmailAdapter"),
      )
      val repository = TypeRepository("test", catalog, mappings)
      val col = column("email", type = "email")
      val kotlinType = repository.resolveColumnType(col)
      assertThat(kotlinType).isEqualTo(ClassName("com.example", "CustomEmail"))
    }

    @Test
    fun `type-level override on standard type`() {
      val mappings = listOf(
        TypeMapping("jsonb", null, null, "com.example.JsonData", "com.example.JsonDataAdapter"),
      )
      val repository = TypeRepository("test", Catalog(), mappings)
      val col = column("metadata", type = "jsonb")
      val kotlinType = repository.resolveColumnType(col)
      assertThat(kotlinType).isEqualTo(ClassName("com.example", "JsonData"))
    }

    @Test
    fun `column-level override`() {
      val mappings = listOf(
        TypeMapping("", "users", "metadata", "com.example.Metadata", "com.example.MetadataAdapter"),
      )
      val repository = TypeRepository("test", Catalog(), mappings)
      val col = column("metadata", type = "jsonb", table = Identifier(name = "users"))
      val kotlinType = repository.resolveColumnType(col)
      assertThat(kotlinType).isEqualTo(ClassName("com.example", "Metadata"))
    }

    @Test
    fun `column override takes precedence over type override`() {
      val mappings = listOf(
        TypeMapping("jsonb", null, null, "com.example.JsonData", "com.example.JsonDataAdapter"),
        TypeMapping("", "users", "preferences", "com.example.UserPrefs", "com.example.UserPrefsAdapter"),
      )
      val repository = TypeRepository("test", Catalog(), mappings)

      // Column override wins for users.preferences
      val prefsCol = column("preferences", type = "jsonb", table = Identifier(name = "users"))
      assertThat(repository.resolveColumnType(prefsCol)).isEqualTo(ClassName("com.example", "UserPrefs"))

      // Type override applies to other jsonb columns
      val settingsCol = column("settings", type = "jsonb", table = Identifier(name = "users"))
      assertThat(repository.resolveColumnType(settingsCol)).isEqualTo(ClassName("com.example", "JsonData"))
    }

    @Test
    fun `type override suppresses auto-generated enum`() {
      val catalog = Catalog(schemas = listOf(Schema(name = "public", enums = listOf(moodEnum))))
      val mappings = listOf(
        TypeMapping("mood", null, null, "com.example.CustomMood", "com.example.CustomMoodAdapter"),
      )
      val repository = TypeRepository("test", catalog, mappings)
      repository.resolveMappableType(column("current_mood", type = "mood"))

      // The enum is still in discoveredEnums (it was referenced), but the *type override*
      // suppresses generation — that filtering happens in generateCode(), not TypeRepository.
      // TypeRepository still tracks it for potential use in column-level overrides.
      // What we verify here is that the resolved type is the user's type, not the auto-generated one.
      val col = column("current_mood", type = "mood")
      val resolved = repository.resolveMappableType(col)
      assertThat(resolved.typeName).isEqualTo(ClassName("com.example", "CustomMood"))
    }

    @Test
    fun `type override suppresses auto-generated domain`() {
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(emailDomain))))
      val mappings = listOf(
        TypeMapping("email", null, null, "com.example.CustomEmail", "com.example.CustomEmailAdapter"),
      )
      val repository = TypeRepository("test", catalog, mappings)
      val col = column("email", type = "email")
      val resolved = repository.resolveMappableType(col)
      assertThat(resolved.typeName).isEqualTo(ClassName("com.example", "CustomEmail"))
    }

    @Test
    fun `column override does NOT suppress auto-generated enum`() {
      val catalog = Catalog(schemas = listOf(Schema(name = "public", enums = listOf(moodEnum))))
      val mappings = listOf(
        TypeMapping("", "users", "current_mood", "com.example.CustomMood", "com.example.CustomMoodAdapter"),
      )
      val repository = TypeRepository("test", catalog, mappings)

      // Column override applies to users.current_mood
      val overriddenCol = column("current_mood", type = "mood", table = Identifier(name = "users"))
      assertThat(repository.resolveColumnType(overriddenCol))
        .isEqualTo(ClassName("com.example", "CustomMood"))

      // Another mood column still resolves to auto-generated enum
      val otherCol = column("previous_mood", type = "mood")
      assertThat(repository.resolveColumnType(otherCol))
        .isEqualTo(ClassName("test", "Mood"))

      // The enum is discovered (for the non-overridden column)
      assertThat(repository.discoveredEnums).contains(moodEnum)
    }

    @Test
    fun `column override does NOT suppress auto-generated domain`() {
      val catalog = Catalog(
        schemas = listOf(Schema(name = "public", domains = listOf(emailDomain, positiveIntDomain))),
      )
      val mappings = listOf(
        TypeMapping("", "users", "email", "com.example.CustomEmail", "com.example.CustomEmailAdapter"),
      )
      val repository = TypeRepository("test", catalog, mappings)

      // Column override applies to users.email
      val overriddenCol = column("email", type = "email", table = Identifier(name = "users"))
      assertThat(repository.resolveColumnType(overriddenCol))
        .isEqualTo(ClassName("com.example", "CustomEmail"))

      // Another email column still resolves to auto-generated value class
      val otherCol = column("contact_email", type = "email")
      assertThat(repository.resolveColumnType(otherCol))
        .isEqualTo(ClassName("test", "Email"))

      // The domain is discovered (for the non-overridden column)
      assertThat(repository.discoveredDomains).contains(emailDomain)
    }

    @Test
    fun `column override matches the real source column, not a CTE's own output alias`() {
      // #238: JdbcAnalyzer.buildResultColumns now populates originalName from the node tree's own
      // :resorigtbl/:resorigcol -- the REAL source column, resolved through a CTE even when the
      // outer select item is a plain reference to the CTE's own (possibly renamed) output alias.
      // tryResolveColumnOverride keys columnLevelOverrides off that SAME originalName, so a
      // columnMapping("parent", "id") must match a column whose outer name is "parentId" as long as
      // its originalName is "id" -- before this fix, originalName mirrored the alias itself
      // ("parentId"), and the mapping silently never matched.
      val mappings = listOf(
        TypeMapping("", "parent", "id", "com.example.ParentId", "com.example.ParentIdAdapter"),
      )
      val repository = TypeRepository("test", Catalog(), mappings)

      val cteRenamedPassthroughColumn = column(
        name = "parentId",
        type = "int4",
        table = Identifier(name = "parent"),
        originalName = "id",
      )

      assertThat(repository.resolveColumnType(cteRenamedPassthroughColumn))
        .isEqualTo(ClassName("com.example", "ParentId"))
    }

    @Test
    fun `unsupported postgres type in type mapping produces error`() {
      val mappings = listOf(
        TypeMapping("xml", null, null, "com.example.XmlDoc", "com.example.XmlDocAdapter"),
      )
      val repository = TypeRepository("test", Catalog(), mappings)
      val col = column("doc", type = "xml")

      val exception = assertThrows<IllegalStateException> {
        repository.resolveMappableType(col)
      }
      assertThat(exception.message!!).contains("xml")
      assertThat(exception.message!!).contains("cannot be used with a custom adapter")
    }

    @Test
    fun `non-null type override uses adapter decode in resultSetAction`() {
      val mappings = listOf(
        TypeMapping("jsonb", null, null, "com.example.JsonData", "com.example.JsonDataAdapter"),
      )
      val repository = TypeRepository("test", Catalog(), mappings)
      val col = column("metadata", type = "jsonb")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("jsonbAdapter.decode(getString(1))")
    }

    @Test
    fun `nullable type override uses safe call in resultSetAction`() {
      val mappings = listOf(
        TypeMapping("jsonb", null, null, "com.example.JsonData", "com.example.JsonDataAdapter"),
      )
      val repository = TypeRepository("test", Catalog(), mappings)
      val col = column("metadata", type = "jsonb", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("getString(1)?.let { jsonbAdapter.decode(it) }")
    }

    @Test
    fun `non-null type override uses setObject with Types OTHER in statementAction`() {
      val mappings = listOf(
        TypeMapping("jsonb", null, null, "com.example.JsonData", "com.example.JsonDataAdapter"),
      )
      val repository = TypeRepository("test", Catalog(), mappings)
      val col = column("metadata", type = "jsonb")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("metadata"))
      assertThat(action.toString()).isEqualTo("setObject(1, jsonbAdapter.encode(metadata), java.sql.Types.OTHER)")
    }

    @Test
    fun `nullable type override uses setNull fallback in statementAction`() {
      val mappings = listOf(
        TypeMapping("jsonb", null, null, "com.example.JsonData", "com.example.JsonDataAdapter"),
      )
      val repository = TypeRepository("test", Catalog(), mappings)
      val col = column("metadata", type = "jsonb", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("metadata"))
      assertThat(action.toString()).contains("jsonbAdapter.encode(")
      assertThat(action.toString()).contains("setNull(1,")
    }

    @Test
    fun `type override on enum resolves JDBC type through enum`() {
      val catalog = Catalog(schemas = listOf(Schema(name = "public", enums = listOf(moodEnum))))
      val mappings = listOf(
        TypeMapping("mood", null, null, "com.example.CustomMood", "com.example.CustomMoodAdapter"),
      )
      val repository = TypeRepository("test", catalog, mappings)
      val col = column("current_mood", type = "mood")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      // Enums use getString/setString — the override should use the same wire type
      assertThat(accessor.toString()).isEqualTo("moodAdapter.decode(getString(1))")
    }

    @Test
    fun `type override on domain resolves JDBC type through domain base`() {
      val catalog = Catalog(
        schemas = listOf(Schema(name = "public", domains = listOf(positiveIntDomain))),
      )
      val mappings = listOf(
        TypeMapping(
          "positive_integer",
          null,
          null,
          "com.example.Age",
          "com.example.AgeAdapter",
        ),
      )
      val repository = TypeRepository("test", catalog, mappings)
      val col = column("age", type = "positive_integer")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      // Domain base type is int4 → getInt
      assertThat(accessor.toString()).isEqualTo("positiveIntegerAdapter.decode(getInt(1))")
    }

    @Test
    fun `type-level override with generic Kotlin type`() {
      val mappings = listOf(
        TypeMapping(
          "jsonb",
          null,
          null,
          "kotlin.collections.Map<kotlin.String, kotlin.Any?>",
          "com.example.JsonAdapter",
        ),
      )
      val repository = TypeRepository("test", Catalog(), mappings)
      val col = column("metadata", type = "jsonb")
      val kotlinType = repository.resolveColumnType(col)
      val expectedType = Map::class.asTypeName().parameterizedBy(
        String::class.asTypeName(),
        ANY.copy(nullable = true),
      )
      assertThat(kotlinType).isEqualTo(expectedType)
    }

    @Test
    fun `column-level override with generic Kotlin type`() {
      val mappings = listOf(
        TypeMapping(
          "",
          "events",
          "payload",
          "kotlin.collections.Map<kotlin.String, kotlin.Any?>",
          "com.example.PayloadAdapter",
        ),
      )
      val repository = TypeRepository("test", Catalog(), mappings)
      val col = column("payload", type = "jsonb", table = Identifier(name = "events"))
      val kotlinType = repository.resolveColumnType(col)
      val expectedType = Map::class.asTypeName().parameterizedBy(
        String::class.asTypeName(),
        ANY.copy(nullable = true),
      )
      assertThat(kotlinType).isEqualTo(expectedType)
    }
  }
}
