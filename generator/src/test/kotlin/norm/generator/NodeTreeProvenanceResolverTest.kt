package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assumptions.assumeTrue
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
 * [NodeTreeColumnProvenance] for, and every shape the "correct or silent" invariant requires it to
 * return `null` for instead of guessing.
 *
 * Each test builds the same node tree production analysis uses — a temporary, zero-argument
 * `BEGIN ATOMIC ... END` SQL-standard function's `prosqlbody` (see
 * [ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody]'s own KDoc for why this is the
 * production path, not `CREATE VIEW`: only `prosqlbody` is populated for a data-modifying
 * statement) — against a real PostgreSQL server, then feeds that raw text straight to the resolver
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
    fun `chained CTEs follow a bare column reference through to the CTE that computed it`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        "WITH x AS (SELECT UPPER(d) AS d FROM t), y AS (SELECT d FROM x) SELECT d FROM y",
      )
      // "y" is declared at the top level (hop 1, ctelevelsup 0 relative to the outer query's own
      // scope). y's own body has no nested WITH of its own, so its reference to "x" is one level
      // further up (hop 2, ctelevelsup 1) -- back at the same top-level scope hop 1 was found in.
      assertThat(provenance).containsExactly(
        NodeTreeColumnProvenance(listOf(CteHop("y", 0), CteHop("x", 1)), 1),
      )
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
  inner class ChainedCtes {

    @Test
    fun `a chain of three plain CTEs resolves through every hop back to the one that computed the value`() {
      // The resolver used to grow its scope stack by one frame per hop, never per lexical nesting
      // level, so by the third hop `:ctelevelsup 1` (which every hop here carries, since none of
      // these CTEs nests its own WITH) indexed a frame that no longer held the top-level list at
      // all, and this returned no provenance whatsoever.
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        "WITH c1 AS (SELECT UPPER(d) AS d FROM t), c2 AS (SELECT d FROM c1), c3 AS (SELECT d FROM c2) " +
          "SELECT d FROM c3",
      )
      assertThat(provenance).containsExactly(
        NodeTreeColumnProvenance(listOf(CteHop("c3", 0), CteHop("c2", 1), CteHop("c1", 1)), 1),
      )
    }

    @Test
    fun `a chain of four plain CTEs resolves through every hop back to the one that computed the value`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        "WITH c1 AS (SELECT UPPER(d) AS d FROM t), c2 AS (SELECT d FROM c1), c3 AS (SELECT d FROM c2), " +
          "c4 AS (SELECT d FROM c3) SELECT d FROM c4",
      )
      assertThat(provenance).containsExactly(
        NodeTreeColumnProvenance(
          listOf(CteHop("c4", 0), CteHop("c3", 1), CteHop("c2", 1), CteHop("c1", 1)),
          1,
        ),
      )
    }

    @Test
    fun `a chain of five plain CTEs resolves through every hop back to the one that computed the value`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT)",
        "WITH c1 AS (SELECT UPPER(d) AS d FROM t), c2 AS (SELECT d FROM c1), c3 AS (SELECT d FROM c2), " +
          "c4 AS (SELECT d FROM c3), c5 AS (SELECT d FROM c4) SELECT d FROM c5",
      )
      assertThat(provenance).containsExactly(
        NodeTreeColumnProvenance(
          listOf(CteHop("c5", 0), CteHop("c4", 1), CteHop("c3", 1), CteHop("c2", 1), CteHop("c1", 1)),
          1,
        ),
      )
    }
  }

  @Nested
  inner class NestedCteShadowing {

    // A resolver-level test asserting on [NodeTreeColumnProvenance] alone cannot distinguish the bug
    // from a fix here. [CteHop] records only a CTE's name and its own `:ctelevelsup`, never which
    // `:ctequery` block that hop actually landed on, and in this exact shape both the wrongly-scoped
    // inner "c" and the correctly-scoped outer "c" happen to yield the identical (name, ctelevelsup,
    // bodyPosition) tuple despite reading completely different `:ctequery` bodies. Only reading the
    // actual text at that position observes the divergence -- see
    // NodeTreeProvenanceExpressionTest.NestedCteShadowing's own test for this exact shape, which
    // asserts the emitted expression is `UPPER(name)`, never `LOWER(description)`.

    @Test
    fun `a nested WITH that shadows an outer CTE name resolves against the INNER, correctly-scoped body`() {
      // Before the resolver tracked a scope stack, a flat, outermost-only CTE map resolved "c"
      // against the outer definition (UPPER(name)) even when the reference was written from inside
      // "d"'s own body, which declares its own, shadowing "c" (LOWER(description)).
      val provenance = provenanceFor(
        "CREATE TABLE parent (name TEXT, description TEXT)",
        "WITH c AS (SELECT UPPER(name) AS ux FROM parent), " +
          "d AS (WITH c AS (SELECT LOWER(description) AS ux FROM parent) SELECT ux, 1 AS n FROM c) " +
          "SELECT ux, n FROM d",
      )
      // "ux" resolves through d's own reference to its inner "c" (ctelevelsup 0 relative to d's own
      // body), landing on the inner CTE's own computed expression -- hop 1 is "d" (declared at the
      // top level), hop 2 is the inner "c" (declared inside d's own body, ctelevelsup 0 relative to
      // that body). "n" never enters a CTE reference at all -- d's own body position 2 is the
      // literal "1 AS n" -- so it resolves to d's own body alone, not to either "c".
      assertThat(provenance).containsExactly(
        NodeTreeColumnProvenance(listOf(CteHop("d", 0), CteHop("c", 0)), 1),
        NodeTreeColumnProvenance("d", 2),
      )
    }

    @Test
    fun `sibling CTEs where the second one shadows the first's name resolve against the INNER body`() {
      val provenance = provenanceFor(
        "CREATE TABLE t (x TEXT, y TEXT)",
        "WITH a AS (SELECT UPPER(x) AS ux FROM t), " +
          "b AS (WITH a AS (SELECT LOWER(y) AS ux FROM t) SELECT ux FROM a) " +
          "SELECT ux FROM b",
      )
      assertThat(provenance).containsExactly(
        NodeTreeColumnProvenance(listOf(CteHop("b", 0), CteHop("a", 0)), 1),
      )
    }

    @Test
    fun `a nested WITH with no name collision at all still resolves through both levels`() {
      // Non-adversarial control: proves the scope-stack fix supports genuine multi-level CTE nesting,
      // not merely "decline whenever names collide".
      val provenance = provenanceFor(
        "CREATE TABLE t (y TEXT)",
        "WITH d AS (WITH e AS (SELECT LOWER(y) AS uy FROM t) SELECT uy FROM e) SELECT uy FROM d",
      )
      assertThat(provenance).containsExactly(
        NodeTreeColumnProvenance(listOf(CteHop("d", 0), CteHop("e", 0)), 1),
      )
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
  inner class LevelsUpGuards {

    // Neither shape below can occur through a real, live PostgreSQL round trip: a top-level
    // statement's own :targetList Var always has :varlevelsup 0 (there is no enclosing scope to
    // point past), and an ordinary (non-merged) :joinaliasvars entry always references one of the
    // JOIN's own two inputs at :varlevelsup 0. Both guards are defensive code for a shape PostgreSQL
    // never actually produces -- see NodeTreeProvenanceResolver's own KDoc -- so these are
    // hand-crafted `pg_node_tree` fixtures (the same established pattern PgNodeTreeParserTest and
    // NodeTreeProvenanceExpressionTest's AllPositionCheckGate test use) exercising exactly the shape
    // each guard exists to refuse.

    @Test
    fun `an outer target-list Var with a nonzero levelsup resolves to nothing, never an enclosing scope's CTE`() {
      val nodeTree = """
        {QUERY :targetList (
          {TARGETENTRY :expr {VAR :varno 1 :varattno 1 :varlevelsup 1} :resno 1 :resname x :resjunk false}
        ) :rtable (
          {RANGETBLENTRY :rtekind 6 :ctename c :ctelevelsup 0}
        ) :cteList (
          {COMMONTABLEEXPR :ctename c :ctequery {QUERY :targetList (
            {TARGETENTRY :expr {CONST :constvalue 1} :resno 1 :resname ux :resjunk false}
          ) :rtable ()}}
        )}
      """.trimIndent()

      assertThat(NodeTreeProvenanceResolver().resolveColumnProvenance(nodeTree)).containsExactly(null)
    }

    @Test
    fun `a JOIN alias var with a nonzero levelsup resolves to nothing, never an enclosing scope's CTE`() {
      val nodeTree = """
        {QUERY :targetList (
          {TARGETENTRY :expr {VAR :varno 3 :varattno 1 :varlevelsup 0} :resno 1 :resname x :resjunk false}
        ) :rtable (
          {RANGETBLENTRY :rtekind 6 :ctename a :ctelevelsup 0}
          {RANGETBLENTRY :rtekind 6 :ctename b :ctelevelsup 0}
          {RANGETBLENTRY :rtekind 2 :jointype 0 :joinmergedcols 0 :joinaliasvars (
            {VAR :varno 1 :varattno 1 :varlevelsup 1}
          )}
        ) :cteList (
          {COMMONTABLEEXPR :ctename a :ctequery {QUERY :targetList (
            {TARGETENTRY :expr {CONST :constvalue 1} :resno 1 :resname ux :resjunk false}
          ) :rtable ()}}
          {COMMONTABLEEXPR :ctename b :ctequery {QUERY :targetList (
            {TARGETENTRY :expr {CONST :constvalue 2} :resno 1 :resname ux :resjunk false}
          ) :rtable ()}}
        )}
      """.trimIndent()

      assertThat(NodeTreeProvenanceResolver().resolveColumnProvenance(nodeTree)).containsExactly(null)
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
      // The RETURNING Var's varno points at the INSERT's own target relation "b" (rtekind 0), never
      // at the feeding subquery's CTE reference — this is the correct node-tree reading, not a
      // resolver gap, so "nothing" here is the right answer, not a missing feature.
      val provenance = provenanceFor(
        "CREATE TABLE t (d TEXT); CREATE TABLE b (id INT, d TEXT)",
        "WITH c AS (SELECT UPPER(d) AS d FROM t) " +
          "INSERT INTO b (id, d) SELECT 1, c.d FROM c RETURNING d AS rd",
      )
      assertThat(provenance).containsExactly(null)
    }

    @Test
    fun `MERGE RETURNING resolves the returned CTE column to its body position`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "MERGE RETURNING requires PostgreSQL 17+")
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
   * Builds the same `prosqlbody` node tree
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
