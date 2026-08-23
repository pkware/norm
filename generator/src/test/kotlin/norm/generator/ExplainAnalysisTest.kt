package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 2 of the `prosqlbody` cutover: proves `EXPLAIN (FORMAT JSON)` never executes the
 * statement it plans, and that it correctly reports a `MERGE`'s per-relation match-optionality —
 * the one thing [PgCatalogLoader.hasUnsafeMergeReturning]'s KDoc documents as invisible to
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
            sourceRelationName = "nonexistent_source",
          )
          assertThat(result).isNull()
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
            sourceRelationName = "src",
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
    private val pgVersion = System.getProperty("norm.test.pgVersion", "18")

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = PostgreSQLContainer(
      DockerImageName.parse("postgres:$pgVersion-alpine").asCompatibleSubstituteFor("postgres"),
    ).apply {
      withDatabaseName("norm_explain_test")
      waitingFor(
        WaitAllStrategy()
          .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
          .withStrategy(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(60)),
      )
    }
  }
}
