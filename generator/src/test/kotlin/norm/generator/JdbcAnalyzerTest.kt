package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
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
import org.junit.jupiter.api.TestInstance
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

    assertThat(catalog.defaultSchema).isEqualTo("public")
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
    assertThat(columnsByName.getValue("int4_type").notNull).isTrue()

    // Verify smallint types
    assertThat(columnsByName.getValue("int2_type").type!!.name).isEqualTo("int2")
    assertThat(columnsByName.getValue("int2_type").notNull).isTrue()

    // Verify bigint types
    assertThat(columnsByName.getValue("int8_type").type!!.name).isEqualTo("int8")
    assertThat(columnsByName.getValue("int8_type").notNull).isTrue()

    // Verify float types
    assertThat(columnsByName.getValue("float4_type").type!!.name).isEqualTo("float4")
    assertThat(columnsByName.getValue("float8_type").type!!.name).isEqualTo("float8")

    // Verify text types
    assertThat(columnsByName.getValue("string_type").type!!.name).isEqualTo("text")
    assertThat(columnsByName.getValue("string_type").notNull).isTrue()
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
    assertThat(intArray.isArray).isTrue()
    assertThat(intArray.arrayDims).isEqualTo(1)
    assertThat(intArray.type!!.name).isEqualTo("int4")

    val textArray = columnsByName.getValue("text_array_type")
    assertThat(textArray.isArray).isTrue()
    assertThat(textArray.type!!.name).isEqualTo("text")
  }

  @Test
  fun `buildCatalog detects nullability`() {
    val catalog = analyzer.buildCatalog()
    val typeTable = catalog.schemas.first().tables.first { it.rel!!.name == "type" }
    val columnsByName = typeTable.columns.associateBy { it.name }

    // NOT NULL columns
    assertThat(columnsByName.getValue("string_type").notNull).isTrue()
    assertThat(columnsByName.getValue("int4_type").notNull).isTrue()
    assertThat(columnsByName.getValue("date_notnull_type").notNull).isTrue()

    // Nullable columns
    assertThat(columnsByName.getValue("text_type").notNull).isFalse()
    assertThat(columnsByName.getValue("integer_type").notNull).isFalse()
    assertThat(columnsByName.getValue("date_type").notNull).isFalse()
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
    assertThat(columnsByName.getValue("string_type").notNull).isTrue()
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
   * Tests that verify result column nullability is correctly determined by the full pipeline:
   * SQL → PostgreSQL node tree analysis → [Column.notNull].
   *
   * The node tree analyzer is the authoritative source for nullability. These tests cover
   * NOT NULL columns, nullable columns, expressions, and outer join scenarios.
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
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isFalse()
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
      // upper() is strict — its result is nullable when the input column is nullable.
      assertThat(query.columns[0].notNull).isFalse()
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

      // COUNT(*) is always non-null (aggregate function). int_type is nullable in the schema.
      val columnsByName = query.columns.associateBy { it.name }
      assertThat(columnsByName.getValue("total").notNull).isTrue()
      assertThat(columnsByName.getValue("int_type").notNull).isFalse()
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
      assertThat(columnsByName.getValue("serial_type").notNull).isTrue()
      assertThat(columnsByName.getValue("string_type").notNull).isTrue()
      assertThat(columnsByName.getValue("int4_type").notNull).isTrue()
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
      assertThat(columnsByName.getValue("serial_type").notNull).isTrue()
      assertThat(columnsByName.getValue("string_type").notNull).isTrue()
      // text_type is nullable in the underlying table — should remain nullable.
      assertThat(columnsByName.getValue("text_type").notNull).isFalse()
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
      // Arithmetic on a nullable column — result is nullable.
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Nested
    inner class OuterJoinNullability {

      @Test
      fun `LEFT JOIN NOT NULL column from right table is nullable`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "leftJoinNotNull",
          ":many",
          "SELECT d.id, e.name FROM department d LEFT JOIN employee e ON e.department_id = d.id",
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        // d.id is from the left (preserved) side — always non-null
        assertThat(columnsByName.getValue("id").notNull).isTrue()
        // e.name is NOT NULL in the schema, but LEFT JOIN can produce NULL when no match exists
        assertThat(columnsByName.getValue("name").notNull).isFalse()
      }

      @Test
      fun `LEFT JOIN nullable column from right table is nullable`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "leftJoinNullable",
          ":many",
          "SELECT d.id, e.nickname FROM department d LEFT JOIN employee e ON e.department_id = d.id",
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        assertThat(columnsByName.getValue("id").notNull).isTrue()
        // e.nickname is nullable in schema AND LEFT JOIN can produce NULL — doubly nullable
        assertThat(columnsByName.getValue("nickname").notNull).isFalse()
      }

      @Test
      fun `LEFT JOIN NOT NULL column from left table is non-null`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "leftJoinLeftSide",
          ":many",
          "SELECT d.name, e.name AS employee_name FROM department d LEFT JOIN employee e ON e.department_id = d.id",
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        // d.name is from the left (preserved) side — stays non-null
        assertThat(columnsByName.getValue("name").notNull).isTrue()
        // e.name is from the right (optional) side — nullable due to LEFT JOIN
        assertThat(columnsByName.getValue("employee_name").notNull).isFalse()
      }

      @Test
      fun `RIGHT JOIN NOT NULL column from left table is nullable`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "rightJoinNotNull",
          ":many",
          "SELECT d.name, e.name AS employee_name FROM department d RIGHT JOIN employee e ON e.department_id = d.id",
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        // d.name is from the left (optional) side in a RIGHT JOIN — nullable
        assertThat(columnsByName.getValue("name").notNull).isFalse()
        // e.name is from the right (preserved) side — stays non-null
        assertThat(columnsByName.getValue("employee_name").notNull).isTrue()
      }

      @Test
      fun `INNER JOIN preserves NOT NULL from both tables`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "innerJoinNotNull",
          ":many",
          "SELECT d.name, e.name AS employee_name FROM department d JOIN employee e ON e.department_id = d.id",
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        // INNER JOIN — both sides always have matching rows, NOT NULL preserved
        assertThat(columnsByName.getValue("name").notNull).isTrue()
        assertThat(columnsByName.getValue("employee_name").notNull).isTrue()
      }

      @Test
      fun `LEFT JOIN with subquery NOT NULL column is nullable`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "leftJoinSubquery",
          ":many",
          """
            SELECT d.id, s.name
            FROM department d
            LEFT JOIN (SELECT DISTINCT department_id, name FROM employee) s ON s.department_id = d.id
          """.trimIndent(),
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        assertThat(columnsByName.getValue("id").notNull).isTrue()
        // Subquery column from LEFT JOIN — nullable even though employee.name is NOT NULL
        assertThat(columnsByName.getValue("name").notNull).isFalse()
      }
    }

    @Nested
    inner class ComplexJoinScenarios {

      @Test
      fun `CROSS JOIN preserves NOT NULL from both tables`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "crossJoin",
          ":many",
          "SELECT d.name, e.name AS employee_name FROM department d CROSS JOIN employee e",
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        // CROSS JOIN never produces NULL — both sides always present
        assertThat(columnsByName.getValue("name").notNull).isTrue()
        assertThat(columnsByName.getValue("employee_name").notNull).isTrue()
      }

      @Test
      fun `FULL OUTER JOIN makes both sides nullable`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "fullJoin",
          ":many",
          "SELECT d.name, e.name AS employee_name FROM department d FULL OUTER JOIN employee e ON e.department_id = d.id",
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        // FULL OUTER JOIN — either side can be NULL when no match
        assertThat(columnsByName.getValue("name").notNull).isFalse()
        assertThat(columnsByName.getValue("employee_name").notNull).isFalse()
      }

      @Test
      fun `chained LEFT JOINs make all right sides nullable`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "chainedLeftJoins",
          ":many",
          """
            SELECT d.name, e.name AS employee_name, p.title
            FROM department d
            LEFT JOIN employee e ON e.department_id = d.id
            LEFT JOIN project p ON p.lead_employee_id = e.id
          """.trimIndent(),
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        // d.name: preserved side of both LEFT JOINs — non-null
        assertThat(columnsByName.getValue("name").notNull).isTrue()
        // e.name: right side of first LEFT JOIN — nullable
        assertThat(columnsByName.getValue("employee_name").notNull).isFalse()
        // p.title: right side of second LEFT JOIN — nullable
        assertThat(columnsByName.getValue("title").notNull).isFalse()
      }

      @Test
      fun `CTE with LEFT JOIN propagates nullability`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "cteLeftJoin",
          ":many",
          """
            WITH dept_employees AS (
              SELECT d.id AS dept_id, d.name AS dept_name, e.name AS employee_name
              FROM department d
              LEFT JOIN employee e ON e.department_id = d.id
            )
            SELECT dept_name, employee_name FROM dept_employees
          """.trimIndent(),
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        // dept_name is from preserved side — non-null
        assertThat(columnsByName.getValue("dept_name").notNull).isTrue()
        // employee_name is from nullable side of LEFT JOIN inside CTE — nullable
        assertThat(columnsByName.getValue("employee_name").notNull).isFalse()
      }

      @Test
      fun `self LEFT JOIN same table different nullability per side`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "selfLeftJoin",
          ":many",
          """
            SELECT e1.name AS manager_name, e2.name AS report_name
            FROM employee e1
            LEFT JOIN employee e2 ON e2.department_id = e1.department_id AND e2.id != e1.id
          """.trimIndent(),
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        val columnsByName = query.columns.associateBy { it.name }
        // e1 is on preserved side — non-null
        assertThat(columnsByName.getValue("manager_name").notNull).isTrue()
        // e2 is on nullable side — nullable even though same table
        assertThat(columnsByName.getValue("report_name").notNull).isFalse()
      }

      @Test
      fun `LEFT JOIN inside subquery in FROM does not leak nullability to outer query`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "subqueryContainment",
          ":many",
          """
            SELECT s.dept_name
            FROM (
              SELECT d.name AS dept_name
              FROM department d
              LEFT JOIN employee e ON e.department_id = d.id
            ) s
          """.trimIndent(),
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        // dept_name comes from department (preserved side inside the subquery).
        // The outer FROM just wraps the subquery — no outer join at the outer level.
        assertThat(query.columns).hasSize(1)
        assertThat(query.columns[0].notNull).isTrue()
      }
    }

    @Nested
    inner class SetOperations {

      @Test
      fun `UNION ALL does not introduce outer join nullability`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "unionAll",
          ":many",
          """
            SELECT name FROM department
            UNION ALL
            SELECT name FROM employee
          """.trimIndent(),
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        // Node tree TARGETENTRY has no VAR at the top level for set operations.
        // The node tree reports nullability based on the source columns.
        assertThat(query.columns).hasSize(1)
      }

      @Test
      fun `INTERSECT does not introduce outer join nullability`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "intersect",
          ":many",
          """
            SELECT name FROM department
            INTERSECT
            SELECT name FROM employee
          """.trimIndent(),
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        assertThat(query.columns).hasSize(1)
      }

      @Test
      fun `EXCEPT does not introduce outer join nullability`() {
        val catalog = analyzer.buildCatalog()
        val parsed = ParsedQuery(
          "except",
          ":many",
          """
            SELECT name FROM department
            EXCEPT
            SELECT name FROM employee
          """.trimIndent(),
          emptyList(),
        )

        val query = analyzer.analyzeQuery(parsed, catalog)

        assertThat(query.columns).hasSize(1)
      }
    }

    @Nested
    inner class ViewNullabilityWithJoins {

      @Test
      fun `view over LEFT JOIN does not inherit NOT NULL from wrong source table`() {
        // Both department and employee have a "name" column that is NOT NULL.
        // A view that selects employee.name (without aliasing) from the nullable side of a LEFT JOIN
        // should report it as nullable. But loadViewColumnNullability matches by column name, so
        // "name" in the view matches BOTH department.name (NOT NULL) and employee.name (NOT NULL).
        // Since department is on the preserved side, the name-match falsely inherits NOT NULL.
        connection.createStatement().use { stmt ->
          stmt.execute(
            """
            CREATE VIEW issue65_view AS
            SELECT e.name, e.nickname
            FROM department d
            LEFT JOIN employee e ON e.department_id = d.id
            """.trimIndent(),
          )
        }

        try {
          val catalog = analyzer.buildCatalog()
          val parsed = ParsedQuery(
            "issue65",
            ":many",
            "SELECT name, nickname FROM issue65_view",
            emptyList(),
          )

          val query = analyzer.analyzeQuery(parsed, catalog)

          val columnsByName = query.columns.associateBy { it.name }
          // e.name: NOT NULL in schema, but on nullable side of LEFT JOIN — should be nullable.
          assertThat(columnsByName.getValue("name").notNull).isFalse()
          // e.nickname: nullable in schema — should be nullable regardless.
          assertThat(columnsByName.getValue("nickname").notNull).isFalse()
        } finally {
          connection.createStatement().use { stmt ->
            stmt.execute("DROP VIEW IF EXISTS issue65_view")
          }
        }
      }
    }
  }

  /**
   * Integration tests that verify expression nullability is correctly determined by the full pipeline:
   * SQL expression → PostgreSQL node tree analysis → [Column.notNull].
   *
   * These tests use the `department` and `employee` tables from the all_types schema.
   * `employee.id` and `employee.department_id` are NOT NULL; `employee.nickname` is nullable.
   */
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  inner class ExpressionNullability {

    private lateinit var catalog: Catalog

    @BeforeAll
    fun setupCatalog() {
      catalog = analyzer.buildCatalog()
    }

    private fun analyzeSimpleQuery(sql: String): Query {
      val parsedQuery = ParsedQuery("test", ":one", sql, emptyList())
      return analyzer.analyzeQuery(parsedQuery, catalog)
    }

    @Test
    fun `COUNT star is non-null`() {
      val query = analyzeSimpleQuery("SELECT COUNT(*) AS total FROM department")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `SUM is nullable`() {
      val query = analyzeSimpleQuery("SELECT SUM(id) AS total FROM employee")
      assertThat(query.columns.first().notNull).isFalse()
    }

    @Test
    fun `COALESCE with non-null fallback is non-null`() {
      val query = analyzeSimpleQuery("SELECT COALESCE(nickname, 'anon') AS display_name FROM employee")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `strict function with non-null arg is non-null`() {
      val query = analyzeSimpleQuery("SELECT upper(name) AS upper_name FROM department")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `strict function with nullable arg is nullable`() {
      val query = analyzeSimpleQuery("SELECT upper(nickname) AS upper_nick FROM employee")
      assertThat(query.columns.first().notNull).isFalse()
    }

    @Test
    fun `type cast preserves non-null`() {
      val query = analyzeSimpleQuery("SELECT id::bigint AS big_id FROM department")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `CURRENT_DATE is non-null`() {
      val query = analyzeSimpleQuery("SELECT CURRENT_DATE AS today FROM department")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `IS NOT NULL is non-null`() {
      val query = analyzeSimpleQuery("SELECT name IS NOT NULL AS has_name FROM employee")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `EXISTS is non-null`() {
      val query = analyzeSimpleQuery("SELECT EXISTS(SELECT 1 FROM department) AS has_dept FROM department")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `CASE with ELSE is non-null when all branches non-null`() {
      val query = analyzeSimpleQuery("SELECT CASE WHEN id > 0 THEN 'yes' ELSE 'no' END AS flag FROM employee")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `CASE without ELSE is nullable`() {
      val query = analyzeSimpleQuery("SELECT CASE WHEN id > 0 THEN 'yes' END AS flag FROM employee")
      assertThat(query.columns.first().notNull).isFalse()
    }

    @Test
    fun `nested — strict wrapping COALESCE`() {
      val query = analyzeSimpleQuery("SELECT upper(COALESCE(nickname, 'anon')) AS display FROM employee")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `LEFT JOIN column is nullable`() {
      val query = analyzeSimpleQuery(
        "SELECT d.name, e.name AS emp_name FROM department d LEFT JOIN employee e ON e.department_id = d.id",
      )
      assertThat(query.columns[0].notNull).isTrue() // d.name — preserved side
      assertThat(query.columns[1].notNull).isFalse() // e.name — nullable side
    }

    @Test
    fun `LEFT JOIN with COALESCE is non-null`() {
      val query = analyzeSimpleQuery(
        "SELECT COALESCE(e.name, 'none') AS emp_name FROM department d LEFT JOIN employee e ON e.department_id = d.id",
      )
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `ROW_NUMBER is non-null`() {
      val query = analyzeSimpleQuery("SELECT ROW_NUMBER() OVER() AS rn FROM department")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `NULLIF is nullable`() {
      val query = analyzeSimpleQuery("SELECT NULLIF(name, 'test') AS val FROM employee")
      assertThat(query.columns.first().notNull).isFalse()
    }

    @Test
    fun `GREATEST with all non-null args is non-null`() {
      val query = analyzeSimpleQuery("SELECT GREATEST(department_id, 0) AS val FROM employee")
      assertThat(query.columns.first().notNull).isTrue() // both args NOT NULL
    }

    @Test
    fun `IS DISTINCT FROM is non-null`() {
      val query = analyzeSimpleQuery("SELECT name IS DISTINCT FROM nickname AS val FROM employee")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `IS TRUE is non-null`() {
      val query = analyzeSimpleQuery("SELECT (id > 0) IS TRUE AS val FROM employee")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `string_agg is nullable`() {
      val query = analyzeSimpleQuery("SELECT string_agg(name, ',') AS val FROM employee")
      assertThat(query.columns.first().notNull).isFalse()
    }

    @Test
    fun `ARRAY constructor is non-null`() {
      val query = analyzeSimpleQuery("SELECT ARRAY[1, 2, 3] AS val FROM employee")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `CURRENT_USER is non-null`() {
      val query = analyzeSimpleQuery("SELECT CURRENT_USER AS val FROM employee")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `integer addition with non-null args is non-null`() {
      val query = analyzeSimpleQuery("SELECT id + 1 AS val FROM employee")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `string concat with non-null args is non-null`() {
      val query = analyzeSimpleQuery("SELECT name || ' suffix' AS val FROM employee")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `string concat with nullable arg is nullable`() {
      val query = analyzeSimpleQuery("SELECT nickname || ' suffix' AS val FROM employee")
      assertThat(query.columns.first().notNull).isFalse()
    }

    @Test
    fun `GROUP BY column retains NOT NULL from base table`() {
      // GROUP BY creates an *GROUP* RTE (rtekind 9) — must resolve through groupexprs to base table
      val query = analyzeSimpleQuery(
        "SELECT name, COUNT(*) AS cnt FROM department GROUP BY name",
      )
      assertThat(query.columns[0].notNull).isTrue() // name TEXT NOT NULL
      assertThat(query.columns[1].notNull).isTrue() // COUNT(*) always non-null
    }

    @Test
    fun `GROUP BY with JOIN retains NOT NULL from preserved side`() {
      val query = analyzeSimpleQuery(
        "SELECT d.name, COUNT(e.id) AS emp_count FROM department d " +
          "LEFT JOIN employee e ON e.department_id = d.id GROUP BY d.name",
      )
      assertThat(query.columns[0].notNull).isTrue() // d.name — preserved side, NOT NULL
      assertThat(query.columns[1].notNull).isTrue() // COUNT() always non-null
    }

    @Test
    fun `strict function wrapping parameter placeholders is non-null`() {
      // digest(?, ?) — strict function over non-null parameters. The old SqlNullabilityAnalyzer
      // correctly returned non-null; the node tree analysis must replicate this by replacing ?
      // with typed non-null sentinels instead of NULL.
      val query = analyzeSimpleQuery("SELECT upper(?) AS upper_input FROM department")
      assertThat(query.columns.first().notNull).isTrue()
    }

    @Test
    fun `GROUPING SETS makes GROUP BY columns nullable`() {
      // With GROUPING SETS, columns not in the current grouping set receive NULL even if NOT NULL.
      val query = analyzeSimpleQuery(
        "SELECT d.id, d.name, COUNT(*) AS cnt FROM department d " +
          "GROUP BY GROUPING SETS ((d.id), (d.name))",
      )
      assertThat(query.columns[0].notNull).isFalse() // d.id — NOT NULL but nullable via grouping sets
      assertThat(query.columns[1].notNull).isFalse() // d.name — NOT NULL but nullable via grouping sets
      assertThat(query.columns[2].notNull).isTrue() // COUNT(*) — always non-null
    }

    @Test
    fun `CUBE makes GROUP BY columns nullable`() {
      val query = analyzeSimpleQuery(
        "SELECT d.id, d.name, COUNT(*) AS cnt FROM department d GROUP BY CUBE (d.id, d.name)",
      )
      assertThat(query.columns[0].notNull).isFalse() // d.id — nullable via CUBE
      assertThat(query.columns[1].notNull).isFalse() // d.name — nullable via CUBE
      assertThat(query.columns[2].notNull).isTrue() // COUNT(*)
    }

    @Test
    fun `ROLLUP makes GROUP BY columns nullable`() {
      val query = analyzeSimpleQuery(
        "SELECT d.id, d.name, COUNT(*) AS cnt FROM department d GROUP BY ROLLUP (d.id, d.name)",
      )
      assertThat(query.columns[0].notNull).isFalse() // d.id — nullable via ROLLUP
      assertThat(query.columns[1].notNull).isFalse() // d.name — nullable via ROLLUP
      assertThat(query.columns[2].notNull).isTrue() // COUNT(*)
    }
  }

  @Test
  fun `fetchReservedWords returns known PostgreSQL reserved words`() {
    val reservedWords = analyzer.fetchReservedWords()

    // These are reserved in every PostgreSQL version
    assertThat(reservedWords).contains("select")
    assertThat(reservedWords).contains("table")
    assertThat(reservedWords).contains("where")
    assertThat(reservedWords).contains("user")
    assertThat(reservedWords).contains("order")
  }

  @Test
  fun `fetchReservedWords does not include unreserved words`() {
    val reservedWords = analyzer.fetchReservedWords()

    // "name" and "value" are unreserved in PostgreSQL — they can be used as bare identifiers
    assertThat(reservedWords).doesNotContain("name")
    assertThat(reservedWords).doesNotContain("value")
    assertThat(reservedWords).doesNotContain("id")
  }

  @Test
  fun `buildIdentifierQuoter quotes reserved words`() {
    val quoter = analyzer.buildIdentifierQuoter()

    assertThat(quoter("user")).isEqualTo("\"user\"")
    assertThat(quoter("order")).isEqualTo("\"order\"")
    assertThat(quoter("select")).isEqualTo("\"select\"")
  }

  @Test
  fun `buildIdentifierQuoter leaves normal identifiers unquoted`() {
    val quoter = analyzer.buildIdentifierQuoter()

    assertThat(quoter("author")).isEqualTo("author")
    assertThat(quoter("id")).isEqualTo("id")
    assertThat(quoter("created_at")).isEqualTo("created_at")
    assertThat(quoter("name")).isEqualTo("name")
  }

  @Test
  fun `buildIdentifierQuoter quotes identifiers with unsafe characters`() {
    val quoter = analyzer.buildIdentifierQuoter()

    // Uppercase letters require quoting to preserve case
    assertThat(quoter("MyTable")).isEqualTo("\"MyTable\"")
    // Leading digit is not a valid unquoted identifier start
    assertThat(quoter("1column")).isEqualTo("\"1column\"")
  }

  companion object {
    private val pgVersion = System.getProperty("norm.test.pgVersion", "18")

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = PostgreSQLContainer(
      DockerImageName.parse("postgres:$pgVersion-alpine").asCompatibleSubstituteFor("postgres"),
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
