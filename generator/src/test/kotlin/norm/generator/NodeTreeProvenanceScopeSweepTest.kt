package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Brute-force, ground-truth sweep guarding [NodeTreeProvenanceResolver]'s "correct or silent"
 * invariant across the scope-stack bug class #238's P0/P1 both belong to — a bug class two unit
 * test suites (this one's own sibling files) already caught ONLY because a fresh-context verifier
 * happened to probe the exact shape that triggers it. This test exists so the NEXT scope defect,
 * whatever shape it takes, is caught by a systematic sweep instead of by luck.
 *
 * For every generated shape, this test does NOT compare against a hand-computed expected string —
 * it runs the ACTUAL query against seeded rows to capture what PostgreSQL itself returns for the
 * result column ([groundTruthValue]), then — if [NodeTreeProvenanceResolver] emitted anything at
 * all — evaluates the EMITTED expression text directly against the same seeded row and requires
 * the two to agree. Emitting nothing is a PASS (the "or silent" half of the invariant); emitting
 * something that does not reproduce what the query itself returns is the one and only failure
 * mode, matching this whole subsystem's design (see [NodeTreeProvenanceResolver]'s own KDoc).
 *
 * [generateSweepCases] crosses four axes, all named in issue #238's own review:
 * - **chain depth** (`1..6`): total CTEs in a straight reference chain `c1 <- c2 <- ... <- cN`.
 * - **sibling count** (`0..2`): extra, UNREFERENCED CTEs declared in the same top-level `WITH`
 *   clause, computing a DIFFERENT value than the real chain — proving their mere presence never
 *   changes what the real chain resolves to.
 * - **nesting depth** (`0..2`): the whole chain wrapped in that many extra layers of a genuinely
 *   LEXICALLY nested `WITH` (`ctelevelsup 0` at every such layer, never a sibling hop) — proving
 *   real nesting neither breaks a correct resolution nor "accidentally" fixes a wrong one.
 * - **shadowing**: for `chainDepth >= 2`, the OUTERMOST CTE additionally declares its own nested
 *   `WITH c1 AS (...)`, re-declaring the FIRST CTE's name with a DIFFERENTLY-computed, easily
 *   distinguished body. This is the exact shape of the #238 P0 repro, generalized: at `chainDepth
 *   == 3` specifically, this reproduces a scope-stack index collision that made the OLD (buggy)
 *   resolver silently attribute the result to the WRONG CTE — a genuine non-null WRONG answer, not
 *   merely a missing one (see this file's own mutation note below). At other depths the same
 *   shadow shape does not happen to hit that exact index collision (the corrupted lookup falls
 *   further down the chain, past the SINGLE unrelated shadow frame this test declares, and
 *   correctly bails to `null` instead) — still asserted here, since "correct or silent" makes
 *   `null` a pass, not a gap in coverage.
 *
 * MUTATION TESTED: reverting [NodeTreeProvenanceResolver.resolveVar]'s `currentScopeStack =
 * listOf(ownScope) + currentScopeStack.drop(reference.ctelevelsup)` back to the old, unconditional
 * `listOf(ownScope) + currentScopeStack` was run by hand against this test and confirmed to turn
 * `every generated CTE chain shape emits either nothing or the value it actually produced` red,
 * failing on the `chainDepth=3, shadowed=true` cases with the emitted expression evaluating to the
 * shadow's `LOWER(description)` value instead of the real chain's `UPPER(name)` value — before
 * being reverted back to the fix.
 */
@Testcontainers
class NodeTreeProvenanceScopeSweepTest {

  @Test
  fun `every generated CTE chain shape emits either nothing or the value it actually produced`() {
    val cases = generateSweepCases()
    // The REAL corpus size, not a lower bound: shrinking any axis below changes this literal,
    // forcing a conscious update rather than a silently smaller sweep going unnoticed.
    assertThat(cases.size).isEqualTo(99)

    val failures = mutableListOf<String>()
    var evaluatedNonNullCount = 0
    for (case in cases) {
      val sql = case.toSql()
      val actualValue = groundTruthValue(sql)
      val nodeTree = nodeTreeFor(sql)
      val provenance = NodeTreeProvenanceResolver().resolveColumnProvenance(nodeTree).getOrNull(1)
      val emitted = provenance?.let { resolveNodeTreeProvenanceExpression(sql, nodeTree, it) } ?: continue
      evaluatedNonNullCount++
      val evaluatedValue = evaluateExpression(emitted)
      if (evaluatedValue != actualValue) {
        failures += "${case.description}: emitted `$emitted` evaluates to `$evaluatedValue`, but the " +
          "query itself produced `$actualValue` for: $sql"
      }
    }
    // The sweep's own sensitivity: if NOTHING in the corpus ever emits a non-null expression, this
    // test would trivially pass no matter what NodeTreeProvenanceResolver did.
    assertThat(evaluatedNonNullCount).isGreaterThan(0)
    assertThat(failures).isEmpty()
  }

  /** One point in the sweep's four-axis space — see this file's own KDoc for what each axis means. */
  private data class SweepCase(
    val chainDepth: Int,
    val shadowed: Boolean,
    val siblingCount: Int,
    val nestingDepth: Int,
  ) {

    val description: String
      get() = "chainDepth=$chainDepth shadowed=$shadowed siblingCount=$siblingCount nestingDepth=$nestingDepth"

    fun toSql(): String {
      val chainDefinitions = buildChainDefinitions()
      val siblingDefinitions = (1..siblingCount).map { index ->
        "sibling_$index AS (SELECT id, LOWER(description) AS x FROM parent)"
      }
      val core = "WITH ${(chainDefinitions + siblingDefinitions).joinToString(", ")} " +
        "SELECT id, x FROM c$chainDepth"
      return wrapWithNesting(core)
    }

    /**
     * `c1` computes the real value (`UPPER(name)`); every `c2..cN` is an ordinary pass-through of
     * its predecessor, EXCEPT the outermost (`cN`) when [shadowed] — which additionally declares
     * its own nested `WITH c1 AS (...)`, a DIFFERENT body ([shadowed]'s KDoc explains why this
     * specific placement is the one that reproduces the #238 scope-stack bug at `chainDepth == 3`)
     * that `cN`'s own `SELECT` never itself references.
     */
    private fun buildChainDefinitions(): List<String> {
      val definitions = mutableListOf("c1 AS (SELECT id, UPPER(name) AS x FROM parent)")
      for (index in 2..chainDepth) {
        val body = if (index == chainDepth && shadowed) {
          "WITH c1 AS (SELECT id, LOWER(description) AS x FROM parent) SELECT id, x FROM c${index - 1}"
        } else {
          "SELECT id, x FROM c${index - 1}"
        }
        definitions += "c$index AS ($body)"
      }
      return definitions
    }

    /**
     * Wraps [core] in [nestingDepth] extra layers of a genuinely LEXICALLY nested `WITH` — each
     * layer's own `wrap_N` CTE has [core] (or the PREVIOUS layer) as its ENTIRE body, so the
     * reference from one layer into the next is always `ctelevelsup 0` (a real nesting level),
     * never a sibling hop `ctelevelsup 1` — the one relationship
     * [NodeTreeProvenanceResolver.resolveVar]'s scope-stack fix changes the handling of at all (see
     * this file's own KDoc).
     */
    private fun wrapWithNesting(core: String): String {
      var current = core
      for (level in 1..nestingDepth) {
        current = "WITH wrap_$level AS ($current) SELECT id, x FROM wrap_$level"
      }
      return current
    }
  }

  private fun generateSweepCases(): List<SweepCase> {
    val cases = mutableListOf<SweepCase>()
    for (chainDepth in 1..6) {
      // A shadow re-declares "c1" from INSIDE the outermost CTE's own nested WITH -- meaningless
      // (and, since chainDepth 1 has no "c{chainDepth - 1}" to pass through from at all, not even
      // constructible) for a chain shorter than 2 CTEs.
      val shadowOptions = if (chainDepth >= 2) listOf(false, true) else listOf(false)
      for (shadowed in shadowOptions) {
        for (siblingCount in 0..2) {
          for (nestingDepth in 0..2) {
            cases += SweepCase(chainDepth, shadowed, siblingCount, nestingDepth)
          }
        }
      }
    }
    return cases
  }

  /** Executes [sql] directly (a plain, parameter-free `SELECT`) and reads its single row's `x` column. */
  private fun groundTruthValue(sql: String): String? = connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { resultSet ->
      check(resultSet.next()) { "Expected exactly one row from: $sql" }
      resultSet.getString("x")
    }
  }

  /** Evaluates [expression] (a fragment referencing `parent`'s own columns) against the seeded row. */
  private fun evaluateExpression(expression: String): String? = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT ($expression) FROM parent WHERE id = 1").use { resultSet ->
      check(resultSet.next()) { "Expected exactly one row evaluating: $expression" }
      resultSet.getString(1)
    }
  }

  /**
   * Builds the SAME `prosqlbody` node tree [ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody]
   * does: a temporary, zero-argument `BEGIN ATOMIC ... END` SQL-standard function. See
   * [NodeTreeProvenanceResolverTest]'s identical helper — no parameter substitution is needed here,
   * since no case in this sweep has a `?` placeholder.
   */
  private fun nodeTreeFor(@Language("PostgreSQL") sql: String): String {
    val functionName = "probe_${UUID.randomUUID().toString().replace("-", "")}"
    connection.createStatement().use { statement ->
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
      connection.createStatement().use { it.execute("DROP FUNCTION IF EXISTS pg_temp.$functionName()") }
    }
  }

  companion object {
    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_provenance_scope_sweep_test", inMemory = true)

    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
      connection.createStatement().use { statement ->
        statement.execute("CREATE TABLE parent (id INT, name TEXT, description TEXT)")
        statement.execute("INSERT INTO parent (id, name, description) VALUES (1, 'alpha', 'zeta')")
      }
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::connection.isInitialized) connection.close()
    }
  }
}
