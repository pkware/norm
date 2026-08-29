package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 2 of the `prosqlbody` cutover: proves `EXPLAIN (FORMAT JSON)` never executes the
 * statement it plans, and that it correctly reports a `MERGE`'s per-relation match-optionality —
 * the one thing [PgCatalogLoader.mergeAbsentVarnos]'s KDoc documents as invisible to
 * `:varnullingrels` on EITHER the `CREATE VIEW`/`ev_action` or `prosqlbody` route.
 */
@Testcontainers
class ExplainAnalysisTest {

  @Nested
  inner class NeverExecutes {

    @Test
    fun `EXPLAIN never executes the statement it plans`() {
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute("CREATE TABLE t (id INT NOT NULL)")
        }
        try {
          connection.createStatement().use { stmt ->
            stmt.executeQuery("EXPLAIN (FORMAT JSON) INSERT INTO t (id) VALUES (1)").use { rs ->
              check(rs.next()) { "EXPLAIN produced no rows" }
            }
          }
          val rowCount = connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT count(*) FROM t").use { rs ->
              check(rs.next())
              rs.getInt(1)
            }
          }
          assertThat(rowCount).isEqualTo(0)
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }
  }

  @Nested
  inner class MergeSideNullabilityMapping {

    @Test
    fun `WHEN MATCHED only reports neither side can be absent`() {
      val query = mergeSideNullability(
        "WHEN MATCHED THEN UPDATE SET name = src.sval",
      )
      assertThat(query).isEqualTo(MergeSideNullability(targetCanBeAbsent = false, sourceCanBeAbsent = false))
    }

    @Test
    fun `WHEN MATCHED plus WHEN NOT MATCHED THEN INSERT reports the target can be absent`() {
      val query = mergeSideNullability(
        """
        WHEN MATCHED THEN UPDATE SET name = src.sval
        WHEN NOT MATCHED THEN INSERT (id, name) VALUES (src.id, src.sval)
        """.trimIndent(),
      )
      assertThat(query).isEqualTo(MergeSideNullability(targetCanBeAbsent = true, sourceCanBeAbsent = false))
    }

    @Test
    fun `adding WHEN NOT MATCHED BY SOURCE reports either side can be absent`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      val query = mergeSideNullability(
        """
        WHEN MATCHED THEN UPDATE SET name = src.sval
        WHEN NOT MATCHED THEN INSERT (id, name) VALUES (src.id, src.sval)
        WHEN NOT MATCHED BY SOURCE THEN DELETE
        """.trimIndent(),
      )
      assertThat(query).isEqualTo(MergeSideNullability(targetCanBeAbsent = true, sourceCanBeAbsent = true))
    }

    @Test
    fun `WHEN NOT MATCHED BY SOURCE alone reports only source can be absent, through a CTE with an outer JOIN`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      // A MERGE nested in a CTE sits inside a LARGER plan the outer statement can add its own,
      // UNRELATED joins to. The CTE's own write plan is attached to that outer join as a THIRD,
      // "InitPlan" sibling (verified live) — not a join child — so the outer join's OWN 2-children
      // check correctly rejects it, and the search must continue into the MERGE's own nested join
      // to find the real one. This MERGE also flips which side is preserved relative to the
      // "WHEN NOT MATCHED THEN INSERT" tests above: with no INSERT action, the TARGET row always
      // exists (every result row IS an existing target row); only the SOURCE can be missing.
      // Verified live (target id=2 has no matching source row): id=2, sval=NULL, id=1, sval='x'.
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute(
            """
            CREATE TABLE tgt (id INT PRIMARY KEY, name TEXT NOT NULL);
            CREATE TABLE src (id INT NOT NULL, sval TEXT NOT NULL);
            CREATE TABLE other (id INT NOT NULL, label TEXT NOT NULL)
            """.trimIndent(),
          )
        }
        try {
          val result = explainMergeSideNullability(
            connection,
            """
            WITH m AS (
              MERGE INTO tgt USING src ON tgt.id = src.id
              WHEN MATCHED THEN UPDATE SET name = src.sval
              WHEN NOT MATCHED BY SOURCE THEN DELETE
              RETURNING tgt.id, src.sval
            )
            SELECT m.id, m.sval, other.label FROM m JOIN other ON other.id = m.id
            """.trimIndent(),
            targetRelationName = "tgt",
            sourceRelationNames = setOf("src"),
          )
          assertThat(result).isEqualTo(MergeSideNullability(targetCanBeAbsent = false, sourceCanBeAbsent = true))
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    @Test
    fun `an unrelated join in a WHEN condition over the same relation names does not win the MERGE's own join`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      // The WHEN MATCHED condition's own EXISTS plans as an InitPlan sibling of the MERGE's real
      // join, built from the SAME two tables the merge itself joins (tgt/src) — verified live: a
      // naive first-join-found search picks this Inner join (tgt outer, src inner) over the real
      // Left join, wrongly reporting neither side can be absent. Verified live (target id=2 has no
      // matching source row): id=2, name=NULL.
      val result = withMergeSideNullabilitySchema { connection ->
        explainMergeSideNullability(
          connection,
          """
          MERGE INTO tgt USING src ON tgt.id = src.id
            WHEN MATCHED AND EXISTS (SELECT 1 FROM tgt t2 JOIN src s2 ON t2.id = s2.id) THEN UPDATE SET name = src.name
            WHEN NOT MATCHED BY SOURCE THEN DELETE
          """.trimIndent(),
          targetRelationName = "tgt",
          sourceRelationNames = setOf("src"),
        )
      }
      assertThat(result).isEqualTo(MergeSideNullability(targetCanBeAbsent = false, sourceCanBeAbsent = true))
    }

    @Test
    fun `the same MERGE without the EXISTS condition still reports only the source can be absent`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      val result = withMergeSideNullabilitySchema { connection ->
        explainMergeSideNullability(
          connection,
          """
          MERGE INTO tgt USING src ON tgt.id = src.id
            WHEN MATCHED THEN UPDATE SET name = src.name
            WHEN NOT MATCHED BY SOURCE THEN DELETE
          """.trimIndent(),
          targetRelationName = "tgt",
          sourceRelationNames = setOf("src"),
        )
      }
      assertThat(result).isEqualTo(MergeSideNullability(targetCanBeAbsent = false, sourceCanBeAbsent = true))
    }

    @Test
    fun `a MERGE nested in a CTE keeps its own attribution despite its own unrelated join and an outer JOIN`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      // Combines two distractions at once: the CTE's OWN WHEN condition has an unrelated join over
      // tgt/src (the same hazard as the top-level test above, reached this time through the
      // CTE-nested code path), AND the outer SELECT adds a real JOIN of its own against a THIRD
      // table. Neither may cause this MERGE's target/source attribution to waver.
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute(
            """
            CREATE TABLE tgt (id INT PRIMARY KEY, name TEXT NOT NULL);
            CREATE TABLE src (id INT PRIMARY KEY, name TEXT NOT NULL);
            CREATE TABLE other (id INT NOT NULL, label TEXT NOT NULL)
            """.trimIndent(),
          )
        }
        try {
          val result = explainMergeSideNullability(
            connection,
            """
            WITH m AS (
              MERGE INTO tgt USING src ON tgt.id = src.id
                WHEN MATCHED AND EXISTS (SELECT 1 FROM tgt t2 JOIN src s2 ON t2.id = s2.id) THEN UPDATE SET name = src.name
                WHEN NOT MATCHED BY SOURCE THEN DELETE
              RETURNING tgt.id, src.name
            )
            SELECT m.id, m.name, other.label FROM m JOIN other ON other.id = m.id
            """.trimIndent(),
            targetRelationName = "tgt",
            sourceRelationNames = setOf("src"),
          )
          assertThat(result).isEqualTo(MergeSideNullability(targetCanBeAbsent = false, sourceCanBeAbsent = true))
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    @Test
    fun `a MERGE whose own plan contains multiple joins still attributes the real one`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      // TWO separate WHEN-condition EXISTS clauses each plan as their OWN InitPlan join over
      // tgt/src, sitting alongside the merge's real join -- three join nodes total inside the
      // ModifyTable's own subtree, only one of which is the merge's own.
      val result = withMergeSideNullabilitySchema { connection ->
        explainMergeSideNullability(
          connection,
          """
          MERGE INTO tgt USING src ON tgt.id = src.id
            WHEN MATCHED AND EXISTS (SELECT 1 FROM tgt t2 JOIN src s2 ON t2.id = s2.id)
              AND EXISTS (SELECT 1 FROM tgt t3 JOIN src s3 ON t3.id = s3.id AND t3.name = s3.name)
            THEN UPDATE SET name = src.name
            WHEN NOT MATCHED BY SOURCE THEN DELETE
          """.trimIndent(),
          targetRelationName = "tgt",
          sourceRelationNames = setOf("src"),
        )
      }
      assertThat(result).isEqualTo(MergeSideNullability(targetCanBeAbsent = false, sourceCanBeAbsent = true))
    }

    @Test
    fun `returns null when EXPLAIN fails against invalid SQL`() {
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
        }
        try {
          val result = explainMergeSideNullability(
            connection,
            "MERGE INTO nonexistent_table t USING nonexistent_source s ON t.id = s.id WHEN MATCHED THEN DO NOTHING",
            targetRelationName = "nonexistent_table",
            sourceRelationNames = setOf("nonexistent_source"),
          )
          assertThat(result).isNull()
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    @Test
    fun `a MERGE fed by a single-reference, non-MATERIALIZED CTE source attributes through its inlined base table`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      // PostgreSQL inlines a CTE referenced exactly once (a MERGE's USING source always is) when it
      // is not MATERIALIZED: the CTE's own name never appears anywhere in the plan, only "src" (the
      // base table its body scans). "cte_src" alone would never match.
      val result = withMergeSideNullabilitySchema { connection ->
        explainMergeSideNullability(
          connection,
          """
          WITH cte_src AS (SELECT id, name FROM src)
          MERGE INTO tgt USING cte_src ON tgt.id = cte_src.id
            WHEN MATCHED THEN UPDATE SET name = cte_src.name
            WHEN NOT MATCHED BY SOURCE THEN DELETE
          """.trimIndent(),
          targetRelationName = "tgt",
          sourceRelationNames = setOf("cte_src", "src"),
        )
      }
      assertThat(result).isEqualTo(MergeSideNullability(targetCanBeAbsent = false, sourceCanBeAbsent = true))
    }

    @Test
    fun `a MERGE fed by a MATERIALIZED CTE source attributes through its own CTE Scan node`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      // MATERIALIZED forces PostgreSQL to plan the CTE as its own "CTE Scan" node carrying "CTE
      // Name" rather than inlining it, so the literal CTE name candidate is what must match here,
      // never the underlying "src".
      val result = withMergeSideNullabilitySchema { connection ->
        explainMergeSideNullability(
          connection,
          """
          WITH cte_src AS MATERIALIZED (SELECT id, name FROM src)
          MERGE INTO tgt USING cte_src ON tgt.id = cte_src.id
            WHEN MATCHED THEN UPDATE SET name = cte_src.name
            WHEN NOT MATCHED BY SOURCE THEN DELETE
          """.trimIndent(),
          targetRelationName = "tgt",
          sourceRelationNames = setOf("cte_src", "src"),
        )
      }
      assertThat(result).isEqualTo(MergeSideNullability(targetCanBeAbsent = false, sourceCanBeAbsent = true))
    }

    /** A `tgt(id, name)`/`src(id, name)` schema, both primary-keyed on `id`, for [explainSql]. */
    private fun withMergeSideNullabilitySchema(
      explainSql: (Connection) -> MergeSideNullability?,
    ): MergeSideNullability? {
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute(
            """
            CREATE TABLE tgt (id INT PRIMARY KEY, name TEXT NOT NULL);
            CREATE TABLE src (id INT PRIMARY KEY, name TEXT NOT NULL)
            """.trimIndent(),
          )
        }
        try {
          return explainSql(connection)
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    private fun mergeSideNullability(whenClauses: String): MergeSideNullability? {
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute(
            """
            CREATE TABLE tgt (id INT PRIMARY KEY, name TEXT NOT NULL);
            CREATE TABLE src (id INT NOT NULL, sval TEXT NOT NULL)
            """.trimIndent(),
          )
        }
        try {
          val result = explainMergeSideNullability(
            connection,
            "MERGE INTO tgt USING src ON tgt.id = src.id\n$whenClauses",
            targetRelationName = "tgt",
            sourceRelationNames = setOf("src"),
          )
          // Every case here is a real, live-executable MERGE — a null result would silently hide
          // a mapping failure behind the caller's own safe fallback, defeating this test's point.
          assertThat(result != null).isTrue()
          return result
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }
  }

  private companion object {
    private val schemaCounter = AtomicInteger(0)

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_explain_test")
  }
}
