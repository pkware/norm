package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live-database pins for [NodeTreeProvenanceResolver]: every shape it resolves a
 * [NodeTreeColumnProvenance] for, AND every shape the "correct or silent" invariant requires it to
 * return `null` for instead of guessing.
 *
 * Each test builds the SAME node tree production analysis uses — a temporary, zero-argument
 * `BEGIN ATOMIC ... END` SQL-standard function's `prosqlbody` (see
 * [ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody]'s own KDoc for why this is the
 * production path, not `CREATE VIEW`: only `prosqlbody` is populated for a data-modifying
 * statement) — against a REAL PostgreSQL server, then feeds that raw text straight to the resolver
 * under test. No node-tree text is hand-written.
 */
@Testcontainers
class NodeTreeProvenanceResolverTest {

  private val resolver = NodeTreeProvenanceResolver()

  @Nested
  inner class SingleAndMultipleCtes {

    @Test
    fun `a single CTE's computed expression resolves to that CTE's own body position`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        "WITH a AS (SELECT UPPER(d) AS d FROM t) SELECT a.d AS ad FROM a",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("a", 1))
    }

    @Test
    fun `two independently referenced CTEs each resolve to their own body position`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE u (d TEXT)",
        "WITH a AS (SELECT UPPER(d) AS d FROM t), b AS (SELECT UPPER(d) AS d FROM u) " +
          "SELECT a.d AS ad, b.d AS bd FROM a, b",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("a", 1), NodeTreeColumnProvenance("b", 1))
    }

    @Test
    fun `sibling CTEs that both name their output column the same thing resolve independently`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE u (d TEXT)",
        "WITH a AS (SELECT UPPER(d) AS same FROM t), b AS (SELECT UPPER(d) AS same FROM u) " +
          "SELECT a.same AS asame, b.same AS bsame FROM a, b",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("a", 1), NodeTreeColumnProvenance("b", 1))
    }

    @Test
    fun `a CTE addressed through a FROM alias still resolves by its real CTE name`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        "WITH c AS (SELECT UPPER(d) AS d FROM t) SELECT x.d AS xd FROM c x",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("c", 1))
    }

    @Test
    fun `a comma-separated FROM list mixing a CTE with an ordinary table resolves the CTE column only`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE u (name TEXT)",
        "WITH c AS (SELECT UPPER(d) AS d FROM t) SELECT c.d AS cd, u.name AS uname FROM c, u",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("c", 1), null)
    }

    @Test
    fun `a CTE with an explicit column list resolves positionally, independent of the renamed alias`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        "WITH c(renamed) AS (SELECT UPPER(d) FROM t) SELECT renamed FROM c",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("c", 1))
    }

    @Test
    fun `parseCteList captures the explicit column list as columnNames, distinct from the body's own resname`() {
      val nodeTree = nodeTreeFor(
        "CREATE TABLE t (d TEXT)",
        "WITH c(renamed) AS (SELECT UPPER(d) FROM t) SELECT renamed FROM c",
      )
      val cte = PgNodeTreeParser().parseCteList(nodeTree).single()
      assertThat(cte.columnNames).isEqualTo(listOf("renamed"))
    }

    @Test
    fun `chained CTEs follow a bare column reference through to the CTE that computed it`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        "WITH x AS (SELECT UPPER(d) AS d FROM t), y AS (SELECT d FROM x) SELECT d FROM y",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("x", 1))
    }

    @Test
    fun `a CTE name containing escaped embedded double quotes resolves by its real, unescaped name`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        """WITH "He""llo" AS (SELECT UPPER(d) AS "My Col" FROM t) SELECT "My Col" FROM "He""llo"""",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("He\"llo", 1))
    }
  }

  @Nested
  inner class Joins {

    @Test
    fun `an ON-joined pair of CTEs resolves each qualified output column to its own CTE`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE u (d TEXT)",
        "WITH a AS (SELECT UPPER(d) AS d FROM t), b AS (SELECT UPPER(d) AS d FROM u) " +
          "SELECT a.d AS ad, b.d AS bd FROM a JOIN b ON a.d = b.d",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("a", 1), NodeTreeColumnProvenance("b", 1))
    }

    @Test
    fun `an INNER JOIN USING merged column still resolves, since PostgreSQL aliases it to one side directly`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE u (d TEXT)",
        "WITH a AS (SELECT UPPER(d) AS d FROM t), b AS (SELECT UPPER(d) AS d FROM u) " +
          "SELECT d FROM a JOIN b USING (d)",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("a", 1))
    }

    @Test
    fun `a FULL JOIN USING merged column resolves to nothing, since it is a COALESCE of both sides`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE u (d TEXT)",
        "WITH a AS (SELECT UPPER(d) AS d FROM t), b AS (SELECT UPPER(d) AS d FROM u) " +
          "SELECT d FROM a FULL JOIN b USING (d)",
      )
      assertThat(provenance).containsExactly(null)
    }

    @Test
    fun `a NATURAL FULL JOIN merged column resolves to nothing, for the same COALESCE reason as USING`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE u (d TEXT)",
        "WITH a AS (SELECT UPPER(d) AS d FROM t), b AS (SELECT UPPER(d) AS d FROM u) " +
          "SELECT d FROM a NATURAL FULL JOIN b",
      )
      assertThat(provenance).containsExactly(null)
    }

    @Test
    fun `a NATURAL LEFT JOIN merged column still resolves, since only the left side can supply it`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE u (d TEXT)",
        "WITH a AS (SELECT UPPER(d) AS d FROM t), b AS (SELECT UPPER(d) AS d FROM u) " +
          "SELECT d FROM a NATURAL LEFT JOIN b",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("a", 1))
    }
  }

  @Nested
  inner class RecursionAndSetOperations {

    @Test
    fun `WITH RECURSIVE resolves to nothing, since no single body position feeds every iteration`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (id INT)",
        "WITH RECURSIVE r AS (SELECT id FROM t UNION ALL SELECT id FROM r WHERE id < 10) SELECT id FROM r",
      )
      assertThat(provenance).containsExactly(null)
    }

    @Test
    fun `a CTE body with a top-level set operation resolves to nothing`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE u (d TEXT)",
        "WITH c AS (SELECT UPPER(d) AS d FROM t UNION SELECT UPPER(d) FROM u) SELECT d FROM c",
      )
      assertThat(provenance).containsExactly(null)
    }

    @Test
    fun `a main query with a top-level set operation resolves every column to nothing`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        "WITH c AS (SELECT UPPER(d) AS d FROM t) SELECT d FROM c UNION SELECT d FROM c",
      )
      assertThat(provenance).containsExactly(null)
    }
  }

  @Nested
  inner class NonCteShapes {

    @Test
    fun `a derived table (subquery in FROM) rather than a CTE resolves to nothing`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        "SELECT x.d AS xd FROM (SELECT UPPER(d) AS d FROM t) x",
      )
      assertThat(provenance).containsExactly(null)
    }

    @Test
    fun `a bare VALUES main query, with no CTE reference at all, resolves every column to nothing`() {
      val provenance = provenanceFor("CREATE TABLE t (d TEXT)", "VALUES (1, 2), (3, 4)")
      assertThat(provenance).containsExactly(null, null)
    }

    @Test
    fun `a bare TABLE main query, with no CTE reference at all, resolves every column to nothing`() {
      val provenance = provenanceFor("CREATE TABLE t (id INT, d TEXT)", "TABLE t")
      assertThat(provenance).containsExactly(null, null)
    }
  }

  @Nested
  inner class DataModifyingStatements {

    @Test
    fun `UPDATE FROM cte RETURNING resolves the returned CTE column to its body position`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE b (id INT, d TEXT)",
        "WITH c AS (SELECT UPPER(d) AS d FROM t) " +
          "UPDATE b SET d = c.d FROM c WHERE b.id = 1 RETURNING c.d AS cd",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("c", 1))
    }

    @Test
    fun `DELETE USING cte RETURNING resolves the returned CTE column to its body position`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT, id INT); CREATE TABLE b (id INT, d TEXT)",
        "WITH c AS (SELECT UPPER(d) AS d, id FROM t) " +
          "DELETE FROM b USING c WHERE b.id = c.id RETURNING c.d AS cd",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("c", 1))
    }

    @Test
    fun `INSERT SELECT FROM cte RETURNING resolves to nothing, since provenance comes from the INSERT target`() {
      // Verified live: the RETURNING Var's varno points at the INSERT's OWN target relation "b"
      // (rtekind 0), never at the feeding subquery's CTE reference — this is the CORRECT node-tree
      // reading, not a resolver gap, so "nothing" here is the right answer, not a missing feature.
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE b (id INT, d TEXT)",
        "WITH c AS (SELECT UPPER(d) AS d FROM t) " +
          "INSERT INTO b (id, d) SELECT 1, c.d FROM c RETURNING d AS rd",
      )
      assertThat(provenance).containsExactly(null)
    }

    @Test
    fun `MERGE RETURNING resolves the returned CTE column to its body position`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT, id INT); CREATE TABLE b (id INT, d TEXT)",
        "WITH c AS (SELECT UPPER(d) AS d, id FROM t) " +
          "MERGE INTO b USING c ON b.id = c.id " +
          "WHEN MATCHED THEN UPDATE SET d = c.d " +
          "WHEN NOT MATCHED THEN INSERT (id, d) VALUES (c.id, c.d) " +
          "RETURNING c.d AS cd",
      )
      assertThat(provenance).containsExactly(NodeTreeColumnProvenance("c", 1))
    }
  }

  private fun provenanceFor(@Language("PostgreSQL") ddl: String, @Language("PostgreSQL") sql: String) =
    resolver.resolveColumnProvenance(nodeTreeFor(ddl, sql))

  /**
   * Builds the SAME `prosqlbody` node tree
   * [ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody] does: a temporary, zero-argument
   * `BEGIN ATOMIC ... END` SQL-standard function, so `?` parameters never need to appear (this
   * resolver has no parameter-substitution logic of its own to exercise).
   */
  private fun nodeTreeFor(@Language("PostgreSQL") ddl: String, @Language("PostgreSQL") sql: String): String {
    val schemaName = "test_${schemaCounter.incrementAndGet()}"
    val functionName = "probe_${UUID.randomUUID().toString().replace("-", "")}"
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("CREATE SCHEMA $schemaName")
        statement.execute("SET search_path TO $schemaName")
        statement.execute(ddl)
        statement.execute(
          "CREATE FUNCTION pg_temp.$functionName() RETURNS SETOF record LANGUAGE sql " +
            "BEGIN ATOMIC $sql\n; END",
        )
      }
      try {
        return connection.createStatement().use { statement ->
          statement.executeQuery(
            "SELECT prosqlbody::text FROM pg_proc " +
              "WHERE proname = '$functionName' AND pronamespace = pg_my_temp_schema()",
          ).use { resultSet ->
            check(resultSet.next()) { "No pg_proc row found for probe function $functionName" }
            resultSet.getString(1)
          }
        }
      } finally {
        connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
      }
    }
  }

  companion object {
    private val schemaCounter = AtomicInteger(0)

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_provenance_test", inMemory = true)
  }
}
