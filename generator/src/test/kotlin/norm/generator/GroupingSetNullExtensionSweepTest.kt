package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cross-version brute-force verification, against a real PostgreSQL instance, that the analyzer's
 * `notNull` decisions for grouping-set queries never disagree with what PostgreSQL actually returns.
 * For every [cases] entry, this test runs the query through the real pipeline
 * ([JdbcAnalyzer.analyzeQuery]) and executes it live, then fails on any column where the analyzer
 * reports `notNull = true` but PostgreSQL returned `null` in some row -- a wrong non-null, the one
 * direction of error this gate exists to prevent. A column the analyzer reports nullable while
 * PostgreSQL never actually returns `null` is not a failure here -- that is an accepted, safe
 * over-widening, the same standard [NodeTreeNullabilityAnalyzer]'s own KDoc holds itself to.
 *
 * This sweep reads its expectation from live PostgreSQL alone, never from a hand-written true/false
 * table, mirroring [SafeListSweepTest]'s reasoning for why a brute-force sweep -- not hand-reasoning
 * about a specific shape -- backs a claim like this.
 *
 * [cases] covers: the five grouping-set null-extension shapes this fix recovers (`now()`,
 * `current_date`, `concat_ws`, `xmlelement`, `JSON_OBJECT`), constructs that must stay nullable
 * despite superficially resembling a recovered shape, the self-match guard (a nested occurrence of
 * the grouping key inside an already-shipped `isAlwaysNonNull` call), a `Var`-free grouping key never
 * itself selected in the target list, and a `SubLink` in a target entry alongside a grouping-sets key.
 */
@Testcontainers
class GroupingSetNullExtensionSweepTest {

  private data class Case(val description: String, val ddl: String, val sql: String)

  private val twoNotNullTextColumns = """
    CREATE TABLE t2 (a TEXT NOT NULL, b TEXT NOT NULL);
    INSERT INTO t2 VALUES ('x', 'y'), ('z', 'w');
  """.trimIndent()

  private val cases = listOf(
    // Shapes recovered by this fix -- must now be non-null.
    Case(
      "shape 1 — now() is non-null under ROLLUP(a)",
      twoNotNullTextColumns,
      "SELECT now() AS generated_at, a, count(*) FROM t2 GROUP BY ROLLUP(a)",
    ),
    Case(
      "shape 2 — current_date is non-null under ROLLUP(a)",
      twoNotNullTextColumns,
      "SELECT current_date AS as_of, count(*) FROM t2 GROUP BY ROLLUP(a)",
    ),
    Case(
      "shape 7 — concat_ws with a literal separator is non-null under ROLLUP(a, b)",
      twoNotNullTextColumns,
      "SELECT concat_ws(',', a, b) AS label, count(*) AS n FROM t2 GROUP BY ROLLUP(a, b)",
    ),
    Case(
      "shape 8 — xmlelement is non-null under ROLLUP(a)",
      twoNotNullTextColumns,
      "SELECT xmlelement(name e, xmlattributes(lower(a) AS q), count(*)::text) AS x FROM t2 GROUP BY ROLLUP(a)",
    ),
    Case(
      "shape 9 — JSON_OBJECT is non-null under ROLLUP(a)",
      twoNotNullTextColumns,
      "SELECT JSON_OBJECT('k': lower(a)) AS j FROM t2 GROUP BY ROLLUP(a)",
    ),

    // The other two XmlExpr ops sharing shape 8's node kind. Neither is total over null input
    // (xmlforest(NULL::text AS q) and xmlpi(name php, NULL::text) are both NULL, live on 16/17/18),
    // so null-extending an argument really can null the result and they must stay nullable.
    Case(
      "negative — xmlforest under ROLLUP(a) stays nullable",
      twoNotNullTextColumns,
      "SELECT xmlforest(lower(a) AS q) AS x, count(*) AS c FROM t2 GROUP BY ROLLUP(a)",
    ),
    Case(
      "negative — xmlforest under CUBE(a, b) stays nullable",
      twoNotNullTextColumns,
      "SELECT xmlforest(a AS q, b AS r) AS x, count(*) AS c FROM t2 GROUP BY CUBE(a, b)",
    ),
    Case(
      "negative — xmlpi under ROLLUP(a) stays nullable",
      twoNotNullTextColumns,
      "SELECT xmlpi(name php, lower(a)) AS x, count(*) AS c FROM t2 GROUP BY ROLLUP(a)",
    ),
    Case(
      "negative — xmlpi under GROUPING SETS ((a), ()) stays nullable",
      twoNotNullTextColumns,
      "SELECT xmlpi(name php, a) AS x, count(*) AS c FROM t2 GROUP BY GROUPING SETS ((a), ())",
    ),

    // Constructs that must stay nullable on every version despite resembling a recovered shape.
    Case(
      "negative — now() as its own grouping key stays nullable",
      twoNotNullTextColumns,
      "SELECT now() AS n, count(*) FROM t2 GROUP BY ROLLUP(now())",
    ),
    Case(
      "negative — current_date as its own grouping key stays nullable",
      twoNotNullTextColumns,
      "SELECT current_date AS d, count(*) FROM t2 GROUP BY ROLLUP(current_date)",
    ),
    Case(
      "negative — JSON_OBJECT RETURNING jsonb as its own grouping key stays nullable",
      twoNotNullTextColumns,
      "SELECT JSON_OBJECT('k': lower(a) RETURNING jsonb) AS j, count(*) FROM t2 " +
        "GROUP BY ROLLUP(JSON_OBJECT('k': lower(a) RETURNING jsonb))",
    ),
    Case(
      "negative — concat_ws whose separator is the grouping key stays nullable",
      twoNotNullTextColumns,
      "SELECT concat_ws(concat(a, b), 'x', 'y') AS label, count(*) AS n FROM t2 GROUP BY ROLLUP(concat(a, b))",
    ),
    Case(
      "negative — an expression built on the grouping key stays nullable regardless of cross-version divergence",
      twoNotNullTextColumns,
      "SELECT now() || 'x' AS label, count(*) AS n FROM t2 GROUP BY ROLLUP(now())",
    ),
    Case(
      // A pass-through CoerceViaIo (now()::text) wrapping the exact grouping key: ordinary isNonNull
      // for CoerceViaIo is an unconditional pass-through to its argument (now() is independently
      // non-null via the isNeverNullForNonNullInput safe list), so the grouping-set gate's own
      // structural-match condition (leg B, condition 3) is the only thing standing between this and a
      // wrong non-null -- unlike the `now() || 'x'` case above, which stays nullable for an unrelated
      // reason (the `||` overload PostgreSQL picks for a non-text left operand is not on the operator
      // safe list at all, masking the gate's own answer). PostgreSQL 16: NULL in the ROLLUP summary row.
      "negative — a cast pass-through wrapping the exact grouping key stays nullable",
      twoNotNullTextColumns,
      "SELECT now()::text AS label, count(*) AS n FROM t2 GROUP BY ROLLUP(now())",
    ),

    // Self-match guard: a nested occurrence of the grouping key inside an already-shipped
    // isAlwaysNonNull call must stay nullable.
    Case(
      "criterion 5 — a nested occurrence of the grouping key inside concat() stays nullable",
      twoNotNullTextColumns,
      "SELECT count(*)::text || concat(a, b) AS label FROM t2 GROUP BY ROLLUP(concat(a, b))",
    ),

    Case(
      "a Var-free grouping key not selected anywhere leaves the aggregate non-null",
      twoNotNullTextColumns,
      "SELECT count(*) AS c FROM t2 GROUP BY ROLLUP(now())",
    ),

    // A SubLink in a target entry alongside a grouping-sets key.
    Case(
      "an EXISTS sublink target entry under a rollup stays non-null",
      twoNotNullTextColumns,
      "SELECT EXISTS(SELECT 1 FROM t2 t3 WHERE t3.a = t2.a) AS has_match, count(*) AS c FROM t2 GROUP BY ROLLUP(a)",
    ),
  )

  @Test
  fun `analyzer notNull never disagrees with live PostgreSQL under grouping sets`() {
    val failures = mutableListOf<String>()
    cases.forEach { case -> verify(case, failures) }
    assertThat(failures).isEmpty()
  }

  private fun verify(case: Case, failures: MutableList<String>) {
    val schemaName = "sweep_${schemaCounter.incrementAndGet()}"
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("CREATE SCHEMA $schemaName")
        statement.execute("SET search_path TO $schemaName")
        statement.execute(case.ddl)
      }
      try {
        val analyzer = JdbcAnalyzer(connection)
        val catalog = analyzer.buildCatalog(listOf(schemaName))
        val query = analyzer.analyzeQuery(
          ParsedQuery(name = "test", command = ":many", sql = case.sql, comments = emptyList()),
          catalog,
        )
        val liveEverNull = connection.createStatement().use { statement ->
          statement.executeQuery(case.sql).use { resultSet ->
            val columnCount = resultSet.metaData.columnCount
            val everNull = MutableList(columnCount) { false }
            while (resultSet.next()) {
              for (index in 1..columnCount) {
                resultSet.getObject(index)
                if (resultSet.wasNull()) everNull[index - 1] = true
              }
            }
            everNull
          }
        }
        query.columns.forEachIndexed { index, column ->
          if (column.notNull && liveEverNull[index]) {
            failures += "${case.description}: column ${index + 1} (${column.name}) reported notNull=true " +
              "but live PostgreSQL returned NULL — sql: ${case.sql}"
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
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_grouping_sweep", inMemory = true)
  }
}
