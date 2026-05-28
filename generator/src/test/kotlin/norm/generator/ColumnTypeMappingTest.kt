package norm.generator

import assertk.assertThat
import assertk.assertions.contains
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
import org.junit.jupiter.api.assertThrows
import plugin.Catalog
import plugin.Column
import plugin.Domain
import plugin.Enum
import plugin.Identifier
import plugin.Parameter
import plugin.Query
import plugin.Schema
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
    }
  }

  @Nested
  inner class ArrayResultSetAccessors {

    @Test
    fun `int array generates getArray accessor`() {
      val col = column("tags", type = "int4", isArray = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      val accessorString = accessor.toString()

      assertThat(accessorString).contains("getArray(1)")
      assertThat(accessorString).contains("as kotlin.Array<kotlin.Int?>")
    }

    @Test
    fun `nullable int array uses safe call operators`() {
      val col = column("tags", type = "int4", isArray = true, notNull = false)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      val accessorString = accessor.toString()

      assertThat(accessorString).contains("getArray(1)")
      assertThat(accessorString).contains("?.")
    }

    @Test
    fun `text array generates getArray accessor with Array cast`() {
      val col = column("labels", type = "text", isArray = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      val accessorString = accessor.toString()

      assertThat(accessorString).contains("getArray(1)")
      assertThat(accessorString).contains("as kotlin.Array<kotlin.String?>")
    }

    @Test
    fun `UUID array generates getArray accessor`() {
      val col = column("user_ids", type = "uuid", isArray = true)
      val accessor = typeRepository.resolveMappableType(col).resultSetAction(1)
      val accessorString = accessor.toString()

      assertThat(accessorString).contains("getArray(1)")
      assertThat(accessorString).contains("as kotlin.Array<java.util.UUID?>")
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

    private val emailDomain = Domain(name = "email", base_type = "text")
    private val positiveIntDomain = Domain(name = "positive_integer", base_type = "int4")
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
      val xmlDomain = Domain(name = "xml_doc", base_type = "xml")
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
      val domain = Domain(name = "small_count", base_type = "int2")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "small_count")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "SmallCount"))
    }

    @Test
    fun `nullable SMALLINT domain resultSetAction uses wasNull check`() {
      val domain = Domain(name = "small_count", base_type = "int2")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "small_count", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getShort(1).takeUnless { wasNull() }?.let { smallCountAdapter.decode(it) }")
    }

    @Test
    fun `non-null SMALLINT domain statementAction uses setShort`() {
      val domain = Domain(name = "small_count", base_type = "int2")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "small_count")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("count"))
      assertThat(action.toString()).isEqualTo("setShort(1, smallCountAdapter.encode(count))")
    }

    @Test
    fun `BIGINT domain resolves to Long value class`() {
      val domain = Domain(name = "big_count", base_type = "int8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "big_count")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "BigCount"))
    }

    @Test
    fun `nullable BIGINT domain resultSetAction uses wasNull check`() {
      val domain = Domain(name = "big_count", base_type = "int8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "big_count", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getLong(1).takeUnless { wasNull() }?.let { bigCountAdapter.decode(it) }")
    }

    @Test
    fun `non-null BIGINT domain statementAction uses setLong`() {
      val domain = Domain(name = "big_count", base_type = "int8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("count", type = "big_count")
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("count"))
      assertThat(action.toString()).isEqualTo("setLong(1, bigCountAdapter.encode(count))")
    }

    @Test
    fun `FLOAT domain resolves to Float value class`() {
      val domain = Domain(name = "latitude", base_type = "float4")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lat", type = "latitude")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "Latitude"))
    }

    @Test
    fun `nullable FLOAT domain resultSetAction uses wasNull check`() {
      val domain = Domain(name = "latitude", base_type = "float4")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lat", type = "latitude", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getFloat(1).takeUnless { wasNull() }?.let { latitudeAdapter.decode(it) }")
    }

    @Test
    fun `nullable FLOAT domain statementAction uses setNull with REAL`() {
      val domain = Domain(name = "latitude", base_type = "float4")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lat", type = "latitude", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("lat"))
      assertThat(action.toString()).contains("setFloat(")
      assertThat(action.toString()).contains("Types.REAL")
    }

    @Test
    fun `DOUBLE domain resolves to Double value class`() {
      val domain = Domain(name = "longitude", base_type = "float8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lng", type = "longitude")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "Longitude"))
    }

    @Test
    fun `nullable DOUBLE domain resultSetAction uses wasNull check`() {
      val domain = Domain(name = "longitude", base_type = "float8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lng", type = "longitude", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getDouble(1).takeUnless { wasNull() }?.let { longitudeAdapter.decode(it) }")
    }

    @Test
    fun `nullable DOUBLE domain statementAction uses setNull with DOUBLE`() {
      val domain = Domain(name = "longitude", base_type = "float8")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("lng", type = "longitude", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("lng"))
      assertThat(action.toString()).contains("setDouble(")
      assertThat(action.toString()).contains("Types.DOUBLE")
    }

    @Test
    fun `BOOLEAN domain resolves to Boolean value class`() {
      val domain = Domain(name = "active_flag", base_type = "bool")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("active", type = "active_flag")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "ActiveFlag"))
    }

    @Test
    fun `nullable BOOLEAN domain resultSetAction uses wasNull check`() {
      val domain = Domain(name = "active_flag", base_type = "bool")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("active", type = "active_flag", notNull = false)
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString())
        .isEqualTo("getBoolean(1).takeUnless { wasNull() }?.let { activeFlagAdapter.decode(it) }")
    }

    @Test
    fun `nullable BOOLEAN domain statementAction uses setNull with BOOLEAN`() {
      val domain = Domain(name = "active_flag", base_type = "bool")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("active", type = "active_flag", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("active"))
      assertThat(action.toString()).contains("setBoolean(")
      assertThat(action.toString()).contains("Types.BOOLEAN")
    }

    @Test
    fun `NUMERIC domain resolves to BigDecimal-backed value class`() {
      val domain = Domain(name = "currency_amount", base_type = "numeric")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("amount", type = "currency_amount")

      assertThat(repository.resolveColumnType(col)).isEqualTo(ClassName("test", "CurrencyAmount"))
    }

    @Test
    fun `nullable NUMERIC domain resultSetAction uses safe call without wasNull`() {
      val domain = Domain(name = "currency_amount", base_type = "numeric")
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
      val domain = Domain(name = "currency_amount", base_type = "numeric")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("amount", type = "currency_amount", notNull = false)
      val action = repository.resolveMappableType(col).statementAction(1, CodeBlock.of("amount"))
      assertThat(action.toString()).contains("setBigDecimal(")
      assertThat(action.toString()).contains("Types.NUMERIC")
    }

    @Test
    fun `VARCHAR domain resolves same as TEXT domain`() {
      val domain = Domain(name = "username", base_type = "varchar")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("name", type = "username")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("usernameAdapter.decode(getString(1))")
    }

    @Test
    fun `BPCHAR domain resolves same as TEXT domain`() {
      val domain = Domain(name = "country_code", base_type = "bpchar")
      val catalog = Catalog(schemas = listOf(Schema(name = "public", domains = listOf(domain))))
      val repository = TypeRepository("test", catalog)
      val col = column("code", type = "country_code")
      val accessor = repository.resolveMappableType(col).resultSetAction(1)
      assertThat(accessor.toString()).isEqualTo("countryCodeAdapter.decode(getString(1))")
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
    fun `unsupported type returns null`() {
      assertThat(resolveJdbcTypeInfo("xml")).isEqualTo(null)
      assertThat(resolveJdbcTypeInfo("bytea")).isEqualTo(null)
    }
  }

  @Nested
  inner class UserTypeMappings {

    private val moodEnum = Enum(name = "mood", vals = listOf("happy", "sad", "angry"))
    private val emailDomain = Domain(name = "email", base_type = "text")
    private val positiveIntDomain = Domain(name = "positive_integer", base_type = "int4")

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

  // Helper to create SqlStatement with common defaults
  private fun createStatement(
    sql: String,
    cmd: String = ":one",
    name: String = "TestQuery",
    columns: List<Column> = emptyList(),
    params: List<Parameter> = emptyList(),
    catalog: Catalog = Catalog(),
    comments: List<String> = emptyList(),
  ): SqlStatement {
    val repository = TypeRepository("test", catalog)
    return SqlStatement(
      catalog,
      Query(
        text = sql,
        cmd = cmd,
        name = name,
        columns = columns,
        params = params,
        comments = comments,
      ),
      repository,
    )
  }

  // Helper to create a column with common defaults
  private fun column(
    name: String,
    type: String = "varchar",
    notNull: Boolean = true,
    isArray: Boolean = false,
    table: Identifier? = null,
    embedTable: Identifier? = null,
  ) = Column(
    name = name,
    not_null = notNull,
    type = Identifier(name = type),
    is_array = isArray,
    table = table,
    embed_table = embedTable,
  )
}
