package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration

@Testcontainers
class JdbcAnalyzerTest {

  @Test
  fun `buildCatalog discovers tables`() {
    val catalog = analyzer.buildCatalog()

    assertThat(catalog.default_schema).isEqualTo("public")
    assertThat(catalog.schemas).hasSize(1)

    val tables = catalog.schemas.first().tables
    val tableNames = tables.map { it.rel!!.name }
    assertThat(tableNames).contains("type")
  }

  @Test
  fun `buildCatalog discovers columns with correct types`() {
    val catalog = analyzer.buildCatalog()
    val typeTable = catalog.schemas.first().tables.first { it.rel!!.name == "type" }
    val columnsByName = typeTable.columns.associateBy { it.name }

    // Serial types: JDBC reports the DDL type name ("serial"), not storage type ("int4").
    // Both resolve to the same Kotlin type in the generator.
    assertThat(columnsByName.getValue("serial_type").type!!.name).isEqualTo("serial")
    assertThat(columnsByName.getValue("int4_type").type!!.name).isEqualTo("int4")
    assertThat(columnsByName.getValue("int4_type").not_null).isTrue()

    // Verify smallint types
    assertThat(columnsByName.getValue("int2_type").type!!.name).isEqualTo("int2")
    assertThat(columnsByName.getValue("int2_type").not_null).isTrue()

    // Verify bigint types
    assertThat(columnsByName.getValue("int8_type").type!!.name).isEqualTo("int8")
    assertThat(columnsByName.getValue("int8_type").not_null).isTrue()

    // Verify float types
    assertThat(columnsByName.getValue("float4_type").type!!.name).isEqualTo("float4")
    assertThat(columnsByName.getValue("float8_type").type!!.name).isEqualTo("float8")

    // Verify text types
    assertThat(columnsByName.getValue("string_type").type!!.name).isEqualTo("text")
    assertThat(columnsByName.getValue("string_type").not_null).isTrue()
    assertThat(columnsByName.getValue("varchar_type").type!!.name).isEqualTo("varchar")
    assertThat(columnsByName.getValue("bpchar_type").type!!.name).isEqualTo("bpchar")

    // Verify boolean
    assertThat(columnsByName.getValue("bool_type").type!!.name).isEqualTo("bool")

    // Verify temporal types
    assertThat(columnsByName.getValue("date_type").type!!.name).isEqualTo("date")
    assertThat(columnsByName.getValue("time_type").type!!.name).isEqualTo("time")
    assertThat(columnsByName.getValue("timetz_type").type!!.name).isEqualTo("timetz")
    assertThat(columnsByName.getValue("timestamp_type").type!!.name).isEqualTo("timestamp")
    assertThat(columnsByName.getValue("timestamptz_type").type!!.name).isEqualTo("timestamptz")

    // Verify UUID
    assertThat(columnsByName.getValue("uuid_type").type!!.name).isEqualTo("uuid")

    // Verify bytea
    assertThat(columnsByName.getValue("bytea_type").type!!.name).isEqualTo("bytea")

    // Verify numeric
    assertThat(columnsByName.getValue("numeric_type").type!!.name).isEqualTo("numeric")

    // Verify jsonb
    assertThat(columnsByName.getValue("jsonb_type").type!!.name).isEqualTo("jsonb")

    // Verify oid
    assertThat(columnsByName.getValue("blob_type").type!!.name).isEqualTo("oid")
  }

  @Test
  fun `buildCatalog detects array types`() {
    val catalog = analyzer.buildCatalog()
    val typeTable = catalog.schemas.first().tables.first { it.rel!!.name == "type" }
    val columnsByName = typeTable.columns.associateBy { it.name }

    val intArray = columnsByName.getValue("int_array_type")
    assertThat(intArray.is_array).isTrue()
    assertThat(intArray.array_dims).isEqualTo(1)
    assertThat(intArray.type!!.name).isEqualTo("int4")

    val textArray = columnsByName.getValue("text_array_type")
    assertThat(textArray.is_array).isTrue()
    assertThat(textArray.type!!.name).isEqualTo("text")
  }

  @Test
  fun `buildCatalog detects nullability`() {
    val catalog = analyzer.buildCatalog()
    val typeTable = catalog.schemas.first().tables.first { it.rel!!.name == "type" }
    val columnsByName = typeTable.columns.associateBy { it.name }

    // NOT NULL columns
    assertThat(columnsByName.getValue("string_type").not_null).isTrue()
    assertThat(columnsByName.getValue("int4_type").not_null).isTrue()
    assertThat(columnsByName.getValue("date_notnull_type").not_null).isTrue()

    // Nullable columns
    assertThat(columnsByName.getValue("text_type").not_null).isFalse()
    assertThat(columnsByName.getValue("integer_type").not_null).isFalse()
    assertThat(columnsByName.getValue("date_type").not_null).isFalse()
  }

  @Test
  fun `analyzeQuery resolves SELECT star result columns`() {
    val catalog = analyzer.buildCatalog()
    val parsed = ParsedQuery("all", ":many", "SELECT * FROM type", emptyList())

    val query = analyzer.analyzeQuery(parsed, catalog)

    assertThat(query.name).isEqualTo("all")
    assertThat(query.cmd).isEqualTo(":many")
    assertThat(query.text).isEqualTo("SELECT * FROM type")
    assertThat(query.columns.size).isEqualTo(
      catalog.schemas.first().tables.first { it.rel!!.name == "type" }.columns.size,
    )

    // Verify a few column types
    val columnsByName = query.columns.associateBy { it.name }
    assertThat(columnsByName.getValue("string_type").type!!.name).isEqualTo("text")
    assertThat(columnsByName.getValue("string_type").not_null).isTrue()
    assertThat(columnsByName.getValue("int4_type").type!!.name).isEqualTo("int4")
  }

  @Test
  fun `analyzeQuery resolves single column result`() {
    val catalog = analyzer.buildCatalog()
    val parsed = ParsedQuery("single", ":one", "SELECT string_type FROM type", emptyList())

    val query = analyzer.analyzeQuery(parsed, catalog)

    assertThat(query.columns).hasSize(1)
    assertThat(query.columns[0].name).isEqualTo("string_type")
    assertThat(query.columns[0].type!!.name).isEqualTo("text")
  }

  @Test
  fun `analyzeQuery resolves parameter types`() {
    val catalog = analyzer.buildCatalog()
    val parsed = ParsedQuery(
      "insertOne",
      ":execrows",
      "INSERT INTO type(string_type) VALUES (?)",
      emptyList(),
    )

    val query = analyzer.analyzeQuery(parsed, catalog)

    assertThat(query.params).hasSize(1)
    assertThat(query.params[0].number).isEqualTo(1)
    assertThat(query.params[0].column!!.type!!.name).isEqualTo("text")
  }

  @Test
  fun `analyzeQuery resolves multiple parameters`() {
    val catalog = analyzer.buildCatalog()
    val parsed = ParsedQuery(
      "insertMultiple",
      ":execrows",
      "INSERT INTO type(string_type, int_type) VALUES (?, ?)",
      emptyList(),
    )

    val query = analyzer.analyzeQuery(parsed, catalog)

    assertThat(query.params).hasSize(2)
    assertThat(query.params[0].column!!.type!!.name).isEqualTo("text")
    assertThat(query.params[1].column!!.type!!.name).isEqualTo("int4")
  }

  @Test
  fun `analyzeQuery handles exec with no return`() {
    val catalog = analyzer.buildCatalog()
    val parsed = ParsedQuery(
      "deleteAll",
      ":execrows",
      "DELETE FROM type",
      emptyList(),
    )

    val query = analyzer.analyzeQuery(parsed, catalog)

    assertThat(query.columns).isEmpty()
    assertThat(query.params).isEmpty()
  }

  @Test
  fun `analyzeQuery handles CALL statement`() {
    val catalog = analyzer.buildCatalog()
    val parsed = ParsedQuery(
      "resetTypes",
      ":exec",
      "CALL reset_type_table()",
      emptyList(),
    )

    val query = analyzer.analyzeQuery(parsed, catalog)

    assertThat(query.columns).isEmpty()
    assertThat(query.params).isEmpty()
  }

  @Test
  fun `analyzeQuery handles CALL with parameters`() {
    val catalog = analyzer.buildCatalog()
    val parsed = ParsedQuery(
      "updateStringType",
      ":exec",
      "CALL update_string_type(?, ?)",
      emptyList(),
    )

    val query = analyzer.analyzeQuery(parsed, catalog)

    assertThat(query.columns).isEmpty()
    assertThat(query.params).hasSize(2)
    assertThat(query.params[0].column!!.type!!.name).isEqualTo("int4")
    assertThat(query.params[1].column!!.type!!.name).isEqualTo("text")
  }

  @Test
  fun `analyzeQuery preserves comments`() {
    val catalog = analyzer.buildCatalog()
    val comments = listOf("This is a test query", "with multiple comment lines")
    val parsed = ParsedQuery("test", ":many", "SELECT * FROM type", comments)

    val query = analyzer.analyzeQuery(parsed, catalog)

    assertThat(query.comments).containsExactly("This is a test query", "with multiple comment lines")
  }

  @Test
  fun `analyzeQuery result columns reference tables`() {
    val catalog = analyzer.buildCatalog()
    val parsed = ParsedQuery("all", ":many", "SELECT * FROM type", emptyList())

    val query = analyzer.analyzeQuery(parsed, catalog)

    // All columns from SELECT * should reference the "type" table
    val firstColumn = query.columns.first()
    assertThat(firstColumn.table).isNotNull()
    assertThat(firstColumn.table!!.name).isEqualTo("type")
  }

  /**
   * Tests that verify the JDBC driver's nullability metadata is correctly interpreted by
   * the full pipeline: JDBC metadata -> SqlNullabilityAnalyzer -> Column.not_null.
   *
   * Pure SQL pattern matching (EXISTS/COUNT/COALESCE detection, strict function analysis, etc.)
   * is covered by [SqlNullabilityAnalyzerTest] without needing a database. The tests below focus
   * on JDBC-specific behavior: columnNoNulls vs columnNullable vs columnNullableUnknown.
   */
  @Nested
  inner class ResultColumnNullability {
    @Test
    fun `EXISTS is non-null (full pipeline)`() {
      val catalog = analyzer.buildCatalog()
      val parsed = ParsedQuery(
        "existsCheck",
        ":one",
        "SELECT EXISTS(SELECT 1 FROM type WHERE string_type = ?) AS found",
        emptyList(),
      )

      val query = analyzer.analyzeQuery(parsed, catalog)

      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].name).isEqualTo("found")
      assertThat(query.columns[0].type!!.name).isEqualTo("bool")
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `NOT NULL table column is non-null`() {
      val catalog = analyzer.buildCatalog()
      val parsed = ParsedQuery(
        "notNull",
        ":one",
        "SELECT string_type FROM type WHERE serial_type = ?",
        emptyList(),
      )

      val query = analyzer.analyzeQuery(parsed, catalog)

      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `nullable table column is nullable`() {
      val catalog = analyzer.buildCatalog()
      val parsed = ParsedQuery(
        "nullable",
        ":one",
        "SELECT text_type FROM type WHERE serial_type = ?",
        emptyList(),
      )

      val query = analyzer.analyzeQuery(parsed, catalog)

      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `strict function on nullable column is nullable`() {
      val catalog = analyzer.buildCatalog()
      val parsed = ParsedQuery(
        "upper",
        ":one",
        "SELECT upper(text_type) AS uppered FROM type WHERE serial_type = ?",
        emptyList(),
      )

      val query = analyzer.analyzeQuery(parsed, catalog)

      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].name).isEqualTo("uppered")
      // upper() is strict, but its input is a nullable column reference — not a ? param —
      // so the expression can't be proven non-null. JDBC reports columnNullableUnknown.
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `non-null expression alongside nullable column`() {
      val catalog = analyzer.buildCatalog()
      val parsed = ParsedQuery(
        "mixed",
        ":one",
        "SELECT COUNT(*) AS total, int_type FROM type WHERE serial_type = ? GROUP BY int_type",
        emptyList(),
      )

      val query = analyzer.analyzeQuery(parsed, catalog)

      // Verifies that JDBC correctly reports columnNullable for int_type and columnNullableUnknown
      // for COUNT(*), and the analyzer overrides the unknown to non-null.
      val columnsByName = query.columns.associateBy { it.name }
      assertThat(columnsByName.getValue("total").not_null).isTrue()
      assertThat(columnsByName.getValue("int_type").not_null).isFalse()
    }

    @Test
    fun `view column backed by NOT NULL table column is non-null`() {
      val catalog = analyzer.buildCatalog()
      val parsed = ParsedQuery(
        "viewQuery",
        ":many",
        "SELECT serial_type, string_type, int4_type FROM not_null_view",
        emptyList(),
      )

      val query = analyzer.analyzeQuery(parsed, catalog)

      val columnsByName = query.columns.associateBy { it.name }
      // These columns are NOT NULL in the underlying "type" table.
      // JDBC reports columnNullableUnknown for view columns, but the analyzer should
      // resolve nullability from the catalog.
      assertThat(columnsByName.getValue("serial_type").not_null).isTrue()
      assertThat(columnsByName.getValue("string_type").not_null).isTrue()
      assertThat(columnsByName.getValue("int4_type").not_null).isTrue()
    }

    @Test
    fun `materialized view column backed by nullable table column is nullable`() {
      val catalog = analyzer.buildCatalog()
      val parsed = ParsedQuery(
        "matViewQuery",
        ":one",
        "SELECT serial_type, string_type, text_type FROM not_null_materialized_view LIMIT 1",
        emptyList(),
      )

      val query = analyzer.analyzeQuery(parsed, catalog)

      val columnsByName = query.columns.associateBy { it.name }
      // serial_type and string_type are NOT NULL in the underlying table.
      assertThat(columnsByName.getValue("serial_type").not_null).isTrue()
      assertThat(columnsByName.getValue("string_type").not_null).isTrue()
      // text_type is nullable in the underlying table — should remain nullable.
      assertThat(columnsByName.getValue("text_type").not_null).isFalse()
    }

    @Test
    fun `arithmetic expression is nullable`() {
      val catalog = analyzer.buildCatalog()
      val parsed = ParsedQuery(
        "arithmetic",
        ":one",
        "SELECT int_type + 1 AS incremented FROM type WHERE serial_type = ?",
        emptyList(),
      )

      val query = analyzer.analyzeQuery(parsed, catalog)

      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].name).isEqualTo("incremented")
      // Arithmetic on a nullable column — JDBC reports columnNullableUnknown, we default to nullable
      assertThat(query.columns[0].not_null).isFalse()
    }
  }

  companion object {
    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = PostgreSQLContainer(
      DockerImageName.parse("postgres:18").asCompatibleSubstituteFor("postgres"),
    ).apply {
      withDatabaseName("norm_test")
      waitingFor(
        WaitAllStrategy()
          .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
          .withStrategy(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(60)),
      )
    }

    private lateinit var connection: Connection
    private lateinit var analyzer: JdbcAnalyzer

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

      // Apply the all_types schema and enable extensions used by tests
      val schema = java.io.File("../test-scenarios/all_types/schema.sql").readText()
      connection.createStatement().use { it.execute(schema) }

      analyzer = JdbcAnalyzer(connection)
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::connection.isInitialized) connection.close()
    }
  }
}
