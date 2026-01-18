package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isIn
import assertk.assertions.isTrue
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.INT_ARRAY
import com.squareup.kotlinpoet.asTypeName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import plugin.Catalog
import plugin.Column
import plugin.Identifier
import plugin.Parameter
import plugin.Query
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetTime

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
  inner class ArrayTypes {
    @Test
    fun `array column maps correctly`() {
      val statement = createStatement(
        "SELECT tags FROM post;",
        columns = listOf(column("tags", type = "int4", isArray = true)),
      )
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(INT_ARRAY)
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

  // Helper to create a parameter
  private fun param(number: Int, name: String = "p$number", type: String = "varchar") =
    Parameter(number = number, column = Column(name = name, type = Identifier(name = type)))
}
