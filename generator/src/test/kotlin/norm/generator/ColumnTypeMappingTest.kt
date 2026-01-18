package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isIn
import assertk.assertions.isTrue
import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asTypeName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import plugin.Catalog
import plugin.Column
import plugin.Identifier
import plugin.Parameter
import plugin.Query
import java.sql.Blob
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.util.UUID

/**
 * Tests for PostgreSQL to Kotlin type mapping logic in Column.mappableType extension property.
 */
class ColumnTypeMappingTest {

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
    fun `timestamptz maps to OffsetDateTime`() {
      val statement = createStatement(
        "SELECT updated_at FROM records;",
        columns = listOf(column("updated_at", type = "timestamptz")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(OffsetDateTime::class.asTypeName())
    }

    @Test
    fun `pg_catalog timestamptz maps to OffsetDateTime`() {
      val statement = createStatement(
        "SELECT updated_at FROM records;",
        columns = listOf(column("updated_at", type = "pg_catalog.timestamptz")),
      )
      assertThat(statement.resultRowShape.kotlinType)
        .isEqualTo(OffsetDateTime::class.asTypeName())
    }

    @Test
    fun `nullable timestamptz column`() {
      val statement = createStatement(
        "SELECT updated_at FROM records;",
        columns = listOf(column("updated_at", type = "timestamptz", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(OffsetDateTime::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `non-null timestamptz column`() {
      val statement = createStatement(
        "SELECT updated_at FROM records;",
        columns = listOf(column("updated_at", type = "timestamptz", notNull = true)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isFalse()
      assertThat(kotlinType).isEqualTo(OffsetDateTime::class.asTypeName())
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
      fun `timestamptz array maps to Array of OffsetDateTime`() {
        val statement = createStatement(
          "SELECT updated_dates FROM audit;",
          columns = listOf(column("updated_dates", type = "timestamptz", isArray = true)),
        )
        assertThat(statement.resultRowShape.kotlinType)
          .isEqualTo(ARRAY.parameterizedBy(OffsetDateTime::class.asTypeName().copy(nullable = true)))
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
      val accessor = col.mappableType.resultSetAction(1)
      val accessorString = accessor.toString()

      assertThat(accessorString).contains("getArray(1)")
      assertThat(accessorString).contains("as kotlin.Array<kotlin.Int?>")
    }

    @Test
    fun `nullable int array uses safe call operators`() {
      val col = column("tags", type = "int4", isArray = true, notNull = false)
      val accessor = col.mappableType.resultSetAction(1)
      val accessorString = accessor.toString()

      assertThat(accessorString).contains("getArray(1)")
      assertThat(accessorString).contains("?.")
    }

    @Test
    fun `text array generates getArray accessor with Array cast`() {
      val col = column("labels", type = "text", isArray = true)
      val accessor = col.mappableType.resultSetAction(1)
      val accessorString = accessor.toString()

      assertThat(accessorString).contains("getArray(1)")
      assertThat(accessorString).contains("as kotlin.Array<kotlin.String?>")
    }

    @Test
    fun `UUID array generates getArray accessor`() {
      val col = column("user_ids", type = "uuid", isArray = true)
      val accessor = col.mappableType.resultSetAction(1)
      val accessorString = accessor.toString()

      assertThat(accessorString).contains("getArray(1)")
      assertThat(accessorString).contains("as kotlin.Array<java.util.UUID?>")
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
