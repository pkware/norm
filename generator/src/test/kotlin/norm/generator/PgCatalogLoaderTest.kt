package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
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
class PgCatalogLoaderTest {

  @Nested
  inner class QueryColumnNullability {

    @Nested
    inner class PlainSelect {

      @Test
      fun `single table`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT id, name FROM department",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `LEFT JOIN marks right side as nullable`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT d.id, d.name, e.name AS employee_name, e.nickname " +
            "FROM department d LEFT JOIN employee e ON e.department_id = d.id",
        )
        assertThat(result).containsExactly(false, false, true, true)
      }

      @Test
      fun `INNER JOIN marks no columns as nullable`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT d.name, e.name AS employee_name " +
            "FROM department d JOIN employee e ON e.department_id = d.id",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `SELECT DISTINCT`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT DISTINCT name FROM department",
        )
        assertThat(result).containsExactly(false)
      }

      @Test
      fun `GROUP BY with aggregate`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT d.name, COUNT(*) AS cnt FROM department d " +
            "LEFT JOIN employee e ON e.department_id = d.id GROUP BY d.name",
        )
        // d.name: preserved side, not nullable from outer join
        // COUNT(*): aggregate, no VAR → not nullable from outer join
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `ORDER BY and LIMIT`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT id, name FROM department ORDER BY id LIMIT 10",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `SELECT FOR UPDATE falls back to all non-nullable`() {
        // PostgreSQL rejects FOR UPDATE in view bodies. The fallback path returns
        // all-false via PreparedStatement column count since no joins are present.
        val result = catalogLoader.queryColumnNullability(
          "SELECT id, name FROM department FOR UPDATE",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `subquery in FROM`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT * FROM (SELECT id, name FROM department) sub",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `set-returning function`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT * FROM generate_series(1, 10) AS x",
        )
        assertThat(result).containsExactly(false)
      }

      @Test
      fun `TABLE shorthand`() {
        // TABLE t is shorthand for SELECT * FROM t
        val result = catalogLoader.queryColumnNullability(
          "TABLE department",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `VALUES expression`() {
        val result = catalogLoader.queryColumnNullability(
          "VALUES (1, 'a'), (2, 'b')",
        )
        // Two columns from VALUES — no outer join
        assertThat(result).containsExactly(false, false)
      }
    }

    @Nested
    inner class ParameterPlaceholders {

      @Test
      fun `replaces placeholders before view creation`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT d.id, e.name FROM department d " +
            "LEFT JOIN employee e ON e.department_id = d.id WHERE d.id = ?",
        )
        assertThat(result).containsExactly(false, true)
      }

      @Test
      fun `question mark inside string literal is preserved`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT d.id, d.name FROM department d WHERE d.name = 'what?' OR d.id = ?",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `question mark inside comment is preserved`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT d.id, d.name FROM department d -- why?\nWHERE d.id = ?",
        )
        assertThat(result).containsExactly(false, false)
      }
    }

    @Nested
    inner class SetOperations {

      @Test
      fun `UNION ALL`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT name FROM department UNION ALL SELECT name FROM employee",
        )
        // No VAR at top level — node tree analysis returns false
        assertThat(result).containsExactly(false)
      }

      @Test
      fun `INTERSECT removes duplicates without introducing nullability`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT name FROM department INTERSECT SELECT name FROM employee",
        )
        assertThat(result).containsExactly(false)
      }

      @Test
      fun `EXCEPT removes matching rows without introducing nullability`() {
        val result = catalogLoader.queryColumnNullability(
          "SELECT name FROM department EXCEPT SELECT name FROM employee",
        )
        assertThat(result).containsExactly(false)
      }

      @Test
      fun `UNION ALL where one branch has LEFT JOIN`() {
        val result = catalogLoader.queryColumnNullability(
          """
          SELECT d.name FROM department d
          UNION ALL
          SELECT e.name FROM department d LEFT JOIN employee e ON e.department_id = d.id
          """.trimIndent(),
        )
        // Top-level TARGETENTRY references set operation output, not inner VARs.
        // Outer join inside a branch is invisible to the outer node tree.
        assertThat(result).containsExactly(false)
      }
    }

    @Nested
    inner class CteWithSelect {

      @Test
      fun `simple CTE`() {
        val result = catalogLoader.queryColumnNullability(
          "WITH cte AS (SELECT id, name FROM department) SELECT * FROM cte",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `CTE named 'recursive_cte' is not misread as RECURSIVE keyword`() {
        val result = catalogLoader.queryColumnNullability(
          "WITH recursive_cte AS (SELECT id, name FROM department) SELECT * FROM recursive_cte",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `CTE with LEFT JOIN inside`() {
        val result = catalogLoader.queryColumnNullability(
          """
          WITH cte AS (
            SELECT d.name AS dept_name, e.name AS employee_name
            FROM department d LEFT JOIN employee e ON e.department_id = d.id
          )
          SELECT * FROM cte
          """.trimIndent(),
        )
        // CTE resolution: dept_name from preserved side, employee_name from nullable side
        assertThat(result).containsExactly(false, true)
      }

      @Test
      fun `WITH RECURSIVE`() {
        val result = catalogLoader.queryColumnNullability(
          """
          WITH RECURSIVE counter(n) AS (
            SELECT 1
            UNION ALL
            SELECT n + 1 FROM counter WHERE n < 10
          )
          SELECT n FROM counter
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false)
      }

      @Test
      fun `multiple CTEs`() {
        val result = catalogLoader.queryColumnNullability(
          """
          WITH
            dept AS (SELECT id, name FROM department),
            emp AS (SELECT id, name, department_id FROM employee)
          SELECT d.name, e.name AS emp_name
          FROM dept d LEFT JOIN emp e ON e.department_id = d.id
          """.trimIndent(),
        )
        // d.name: preserved side; e.name: nullable side of LEFT JOIN
        assertThat(result).containsExactly(false, true)
      }
    }

    @Nested
    inner class CteReferencingAnotherCte {

      @Test
      fun `second CTE references first, LEFT JOIN nullability propagates`() {
        val result = catalogLoader.queryColumnNullability(
          """
          WITH
            joined AS (
              SELECT d.id, d.name AS dept_name, e.name AS employee_name
              FROM department d LEFT JOIN employee e ON e.department_id = d.id
            ),
            filtered AS (SELECT dept_name, employee_name FROM joined)
          SELECT dept_name, employee_name FROM filtered
          """.trimIndent(),
        )
        // LEFT JOIN nullability from the first CTE propagates through the second CTE
        // to the outer query: dept_name is preserved, employee_name is nullable.
        assertThat(result).containsExactly(false, true)
      }
    }

    @Nested
    inner class CteWithDmlBody {

      @Test
      fun `CTE with DELETE RETURNING, outer SELECT`() {
        // Outer SELECT from CTE with no joins → no outer join nullability
        val result = catalogLoader.queryColumnNullability(
          """
          WITH deleted AS (
            DELETE FROM employee WHERE id = -1 RETURNING *
          )
          SELECT * FROM deleted
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false, false, false)
      }

      @Test
      fun `CTE with UPDATE RETURNING, outer SELECT`() {
        val result = catalogLoader.queryColumnNullability(
          """
          WITH updated AS (
            UPDATE employee SET nickname = 'x' WHERE id = -1 RETURNING *
          )
          SELECT * FROM updated
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false, false, false)
      }

      @Test
      fun `CTE with INSERT RETURNING, outer SELECT`() {
        val result = catalogLoader.queryColumnNullability(
          """
          WITH inserted AS (
            INSERT INTO department(name) VALUES ('temp') RETURNING *
          )
          SELECT * FROM inserted
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `CTE with DELETE RETURNING, outer SELECT with LEFT JOIN`() {
        // Hard case: outer SELECT has LEFT JOIN → need outer join detection
        // despite DML inside the CTE body.
        val result = catalogLoader.queryColumnNullability(
          """
          WITH deleted AS (
            DELETE FROM employee WHERE id = -1 RETURNING *
          )
          SELECT d.name, del.name AS deleted_name
          FROM department d
          LEFT JOIN deleted del ON del.department_id = d.id
          """.trimIndent(),
        )
        // d.name: preserved side; del.name: nullable side of LEFT JOIN
        assertThat(result).containsExactly(false, true)
      }
    }

    @Nested
    inner class DmlWithReturning {

      @Test
      fun `INSERT VALUES RETURNING`() {
        // INSERT has no FROM clause → no joins → all non-nullable from outer joins
        val result = catalogLoader.queryColumnNullability(
          "INSERT INTO department(name) VALUES ('test') RETURNING *",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `UPDATE RETURNING`() {
        // Plain UPDATE without FROM → no joins
        val result = catalogLoader.queryColumnNullability(
          "UPDATE department SET name = 'x' WHERE id = -1 RETURNING *",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `DELETE RETURNING`() {
        // Plain DELETE without USING → no joins
        val result = catalogLoader.queryColumnNullability(
          "DELETE FROM department WHERE id = -1 RETURNING *",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `INSERT RETURNING specific columns`() {
        val result = catalogLoader.queryColumnNullability(
          "INSERT INTO department(name) VALUES ('test') RETURNING id, name",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `INSERT SELECT RETURNING`() {
        // INSERT ... SELECT: RETURNING refers to target table columns only
        val result = catalogLoader.queryColumnNullability(
          "INSERT INTO department(name) SELECT name FROM department WHERE false RETURNING *",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `INSERT ON CONFLICT RETURNING`() {
        val result = catalogLoader.queryColumnNullability(
          """
          INSERT INTO department(name) VALUES ('test')
          ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name
          RETURNING *
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `UPDATE with FROM clause RETURNING`() {
        // UPDATE FROM with INNER JOIN: no outer join nullability
        val result = catalogLoader.queryColumnNullability(
          """
          UPDATE employee SET nickname = d.name
          FROM department d WHERE employee.department_id = d.id AND employee.id = -1
          RETURNING employee.*
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false, false, false)
      }

      @Test
      fun `UPDATE with FROM LEFT JOIN RETURNING nullable side`() {
        // UPDATE FROM with LEFT JOIN: columns from nullable side of LEFT JOIN
        // should be detected as outer-join-nullable in RETURNING
        val result = catalogLoader.queryColumnNullability(
          """
          UPDATE department SET name = department.name
          FROM employee e LEFT JOIN project p ON p.lead_employee_id = e.id
          WHERE e.department_id = department.id AND department.id = -1
          RETURNING department.id, department.name, p.title
          """.trimIndent(),
        )
        // department.id, department.name: target table → not nullable from outer join
        // p.title: right side of LEFT JOIN → nullable from outer join
        assertThat(result).containsExactly(false, false, true)
      }

      @Test
      fun `DELETE USING RETURNING`() {
        // DELETE USING with INNER JOIN: no outer join nullability
        val result = catalogLoader.queryColumnNullability(
          """
          DELETE FROM employee
          USING department d WHERE employee.department_id = d.id AND employee.id = -1
          RETURNING employee.*
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false, false, false)
      }

      @Test
      fun `DELETE USING LEFT JOIN RETURNING nullable side`() {
        // DELETE USING with LEFT JOIN: columns from nullable side should be
        // detected as outer-join-nullable
        val result = catalogLoader.queryColumnNullability(
          """
          DELETE FROM department
          USING employee e LEFT JOIN project p ON p.lead_employee_id = e.id
          WHERE e.department_id = department.id AND department.id = -1
          RETURNING department.id, department.name, p.title
          """.trimIndent(),
        )
        // department columns: target table → not nullable from outer join
        // p.title: right side of LEFT JOIN → nullable from outer join
        assertThat(result).containsExactly(false, false, true)
      }
    }

    @Nested
    inner class CteWithDmlOuter {

      @Test
      fun `CTE before INSERT RETURNING`() {
        // INSERT RETURNING: no joins in INSERT → no outer join nullability
        val result = catalogLoader.queryColumnNullability(
          """
          WITH source AS (SELECT 'new dept' AS name)
          INSERT INTO department(name) SELECT name FROM source RETURNING *
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `CTE before UPDATE RETURNING`() {
        val result = catalogLoader.queryColumnNullability(
          """
          WITH target_ids AS (SELECT id FROM department WHERE false)
          UPDATE department SET name = 'x' WHERE id IN (SELECT id FROM target_ids) RETURNING *
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `CTE before DELETE RETURNING`() {
        val result = catalogLoader.queryColumnNullability(
          """
          WITH target_ids AS (SELECT id FROM department WHERE false)
          DELETE FROM department WHERE id IN (SELECT id FROM target_ids) RETURNING *
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `DML CTE feeding into DML outer`() {
        // Both CTE and outer statement are DML — no outer joins
        val result = catalogLoader.queryColumnNullability(
          """
          WITH removed AS (
            DELETE FROM employee WHERE id = -1 RETURNING *
          )
          INSERT INTO department(name) SELECT name FROM removed RETURNING *
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false)
      }
    }

    @Nested
    inner class Merge {

      @Test
      fun `MERGE with RETURNING`() {
        // MERGE RETURNING added in PostgreSQL 17.
        // MERGE has no outer join structure — USING is always inner.
        // RETURNING * expands to all columns from both target (department: id, name)
        // and source (s: id, name), so 4 columns total.
        val result = catalogLoader.queryColumnNullability(
          """
          MERGE INTO department d
          USING (SELECT -1 AS id, 'test' AS name) s ON d.id = s.id
          WHEN NOT MATCHED THEN INSERT (name) VALUES (s.name)
          RETURNING *
          """.trimIndent(),
        )
        assertThat(result).containsExactly(false, false, false, false)
      }
    }

    @Nested
    inner class LeadingComments {

      @Test
      fun `line comment before SELECT`() {
        val result = catalogLoader.queryColumnNullability(
          "-- comment\nSELECT id, name FROM department",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `block comment before SELECT`() {
        val result = catalogLoader.queryColumnNullability(
          "/* block comment */ SELECT id, name FROM department",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `multiple comments before SELECT`() {
        val result = catalogLoader.queryColumnNullability(
          "-- line 1\n-- line 2\n/* block */\nSELECT id, name FROM department",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `comment before CTE with SELECT`() {
        val result = catalogLoader.queryColumnNullability(
          "-- setup\nWITH cte AS (SELECT id, name FROM department) SELECT * FROM cte",
        )
        assertThat(result).containsExactly(false, false)
      }

      @Test
      fun `comment before INSERT RETURNING`() {
        val result = catalogLoader.queryColumnNullability(
          "-- cleanup\nINSERT INTO department(name) VALUES ('test') RETURNING *",
        )
        assertThat(result).containsExactly(false, false)
      }
    }
  }

  @Nested
  inner class CheckPostgresVersion {

    @Test
    fun `passes on PostgreSQL 18`() {
      catalogLoader.checkPostgresVersion()
    }
  }

  @Nested
  inner class LoadViewOuterJoinNullableColumns {

    @Test
    fun `returns empty set for schema with no views`() {
      val result = catalogLoader.loadViewOuterJoinNullableColumns("nonexistent_schema")
      assertThat(result).isEmpty()
    }

    @Test
    fun `LEFT JOIN view marks right-side NOT NULL columns as outer-join-nullable`() {
      connection.createStatement().use { stmt ->
        stmt.execute(
          """
          CREATE VIEW test_left_join_view AS
          SELECT d.id, d.name AS dept_name, e.name AS employee_name, e.nickname
          FROM department d LEFT JOIN employee e ON e.department_id = d.id
          """.trimIndent(),
        )
      }
      try {
        val result = catalogLoader.loadViewOuterJoinNullableColumns("public")
        assertThat(result).contains("test_left_join_view.employee_name")
        assertThat(result).contains("test_left_join_view.nickname")
        assertThat(result).doesNotContain("test_left_join_view.dept_name")
        assertThat(result).doesNotContain("test_left_join_view.id")
      } finally {
        connection.createStatement().use { stmt ->
          stmt.execute("DROP VIEW IF EXISTS test_left_join_view")
        }
      }
    }

    @Test
    fun `INNER JOIN view does not mark any columns as outer-join-nullable`() {
      connection.createStatement().use { stmt ->
        stmt.execute(
          """
          CREATE VIEW test_inner_join_view AS
          SELECT d.name AS dept_name, e.name AS employee_name
          FROM department d JOIN employee e ON e.department_id = d.id
          """.trimIndent(),
        )
      }
      try {
        val result = catalogLoader.loadViewOuterJoinNullableColumns("public")
        assertThat(result).doesNotContain("test_inner_join_view.dept_name")
        assertThat(result).doesNotContain("test_inner_join_view.employee_name")
      } finally {
        connection.createStatement().use { stmt ->
          stmt.execute("DROP VIEW IF EXISTS test_inner_join_view")
        }
      }
    }

    @Test
    fun `materialized views are excluded`() {
      val result = catalogLoader.loadViewOuterJoinNullableColumns("public")
      assertThat(result.none { it.startsWith("not_null_materialized_view.") }).isTrue()
      assertThat(result.none { it.startsWith("type_summary.") }).isTrue()
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
    private lateinit var catalogLoader: PgCatalogLoader

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
      val schema = java.io.File("../test-scenarios/all_types/schema.sql").readText()
      connection.createStatement().use { it.execute(schema) }
      catalogLoader = PgCatalogLoader(connection)
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::connection.isInitialized) connection.close()
    }
  }
}
