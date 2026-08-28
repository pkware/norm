package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * One select-list expression whose LAST token, absent an explicit `AS`, is syntactically
 * indistinguishable from a trailing implicit alias — the exact ambiguity #238's cutting defect
 * mishandled (`a IS NOT NULL` truncated to the unparseable `a IS NOT`; `INTERVAL '1' DAY` truncated
 * to `INTERVAL '1'`, which evaluates to a DIFFERENT value, `1 SECOND`).
 *
 * @property expression The bare expression, no alias of any kind.
 * @property fromClause Appended after the expression (and, for the aliased variant, after the
 *   alias) — `FROM t` for every shape that reads a column of [KeywordOperandProvenanceCorpusTest]'s
 *   shared single-row table, empty for a shape that is a self-contained literal.
 */
internal data class KeywordOperandCase(
  val description: String,
  val expression: String,
  val fromClause: String = "FROM t",
) {
  override fun toString() = description
}

/**
 * Live-server proof that [resolveNodeTreeProvenanceExpression] never truncates a CTE body item
 * whose last token is a keyword operand rather than a genuine alias — see this file's own
 * [KeywordOperandCase] KDoc, and #238.
 *
 * Every shape is run through the REAL pipeline (a `prosqlbody` node tree built from a live
 * PostgreSQL server, exactly as production does) in two forms:
 * - WITH a real trailing implicit (no-`AS`) alias: the resolved expression must be [case]'s
 *   COMPLETE, uncut text (expression AND alias together — see [resolveNodeTreeProvenanceExpression]'s
 *   own KDoc for why cutting is never attempted), and — the actual proof, not merely a text
 *   comparison — evaluating that resolved text standalone against the same table must produce the
 *   EXACT SAME value the original query itself produced.
 * - WITHOUT any alias at all: PostgreSQL's own auto-generated `:resname` (`?column?`, `case`, ...)
 *   has no corresponding text to verify against, so the resolved expression must be `null` — never
 *   a guess.
 */
@Testcontainers
internal class KeywordOperandProvenanceCorpusTest {

  @Test
  fun `the corpus has not silently shrunk`() {
    assertThat(CORPUS.size).isEqualTo(31)
  }

  @ParameterizedTest
  @MethodSource("corpus")
  fun `a keyword-operand shape with a real trailing alias emits its complete text, verified by evaluation`(
    case: KeywordOperandCase,
  ) {
    val bodyItem = "${case.expression} res"
    val sql = "SELECT $bodyItem ${case.fromClause}".trim()
    val cteSql = "WITH c AS ($sql) SELECT * FROM c"

    withSchemaAndData { connection ->
      val nodeTree = probeNodeTree(connection, cteSql)
      val provenance = NodeTreeProvenanceResolver().resolveColumnProvenance(nodeTree).single()
        ?: error("expected a resolvable provenance for: $cteSql")
      val resolved = resolveNodeTreeProvenanceExpression(cteSql, nodeTree, provenance)

      assertThat(resolved).isEqualTo(bodyItem)

      val queryValue = scalarValue(connection, cteSql)
      val resolvedValue = scalarValue(connection, "SELECT $resolved ${case.fromClause}".trim())
      assertThat(resolvedValue).isEqualTo(queryValue)
    }
  }

  @ParameterizedTest
  @MethodSource("corpus")
  fun `a keyword-operand shape with no alias at all resolves to nothing, never a guess`(case: KeywordOperandCase) {
    val sql = "SELECT ${case.expression} ${case.fromClause}".trim()
    val cteSql = "WITH c AS ($sql) SELECT * FROM c"

    withSchemaAndData { connection ->
      val nodeTree = probeNodeTree(connection, cteSql)
      val provenance = NodeTreeProvenanceResolver().resolveColumnProvenance(nodeTree).single()
      val resolved = provenance?.let { resolveNodeTreeProvenanceExpression(cteSql, nodeTree, it) }

      assertThat(resolved).isNull()
    }
  }

  private fun withSchemaAndData(block: (Connection) -> Unit) {
    val schemaName = "test_${schemaCounter.incrementAndGet()}"
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("CREATE SCHEMA $schemaName")
        statement.execute("SET search_path TO $schemaName")
        statement.execute(SCHEMA_DDL)
        statement.execute(INSERT_ROW)
      }
      try {
        block(connection)
      } finally {
        connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
      }
    }
  }

  private fun probeNodeTree(connection: Connection, sql: String): String {
    val functionName = "probe_${UUID.randomUUID().toString().replace("-", "")}"
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE FUNCTION pg_temp.$functionName() RETURNS SETOF record LANGUAGE sql " +
          "BEGIN ATOMIC $sql\n; END",
      )
    }
    return connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT prosqlbody::text FROM pg_proc " +
          "WHERE proname = '$functionName' AND pronamespace = pg_my_temp_schema()",
      ).use { resultSet ->
        check(resultSet.next()) { "No pg_proc row found for probe function $functionName" }
        resultSet.getString(1)
      }
    }
  }

  private fun scalarValue(connection: Connection, sql: String): String? =
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use { resultSet ->
        check(resultSet.next()) { "Expected exactly one row from: $sql" }
        resultSet.getString(1)
      }
    }

  companion object {
    private val schemaCounter = AtomicInteger(0)

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_keyword_operand_corpus_test", inMemory = true)

    private const val SCHEMA_DDL = """
      CREATE TABLE t (
        a INT NOT NULL,
        b INT NOT NULL,
        flag BOOLEAN NOT NULL,
        nb BOOLEAN,
        s TEXT NOT NULL,
        ts TIMESTAMP NOT NULL,
        arr INT[] NOT NULL
      )
    """

    private const val INSERT_ROW =
      "INSERT INTO t VALUES (5, 3, true, NULL, 'abc', '2024-01-01 10:00:00', ARRAY[10, 20, 30])"

    /**
     * Every shape the issue calls out, verified live against PostgreSQL 18.4 before being encoded
     * here (`docker exec verify-pg18 psql -U postgres`) — see this file's own KDoc.
     */
    private val CORPUS = listOf(
      KeywordOperandCase("IS NULL", "a IS NULL"),
      KeywordOperandCase("IS NOT NULL", "a IS NOT NULL"),
      KeywordOperandCase("IS DISTINCT FROM", "a IS DISTINCT FROM b"),
      KeywordOperandCase("IS NOT DISTINCT FROM", "a IS NOT DISTINCT FROM b"),
      KeywordOperandCase("IS TRUE", "flag IS TRUE"),
      KeywordOperandCase("IS NOT TRUE", "flag IS NOT TRUE"),
      KeywordOperandCase("IS FALSE", "flag IS FALSE"),
      KeywordOperandCase("IS NOT FALSE", "flag IS NOT FALSE"),
      KeywordOperandCase("IS UNKNOWN", "nb IS UNKNOWN"),
      KeywordOperandCase("IS NOT UNKNOWN", "nb IS NOT UNKNOWN"),
      KeywordOperandCase("CASE WHEN ... END", "CASE WHEN a > 1 THEN 'x' ELSE 'y' END"),
      KeywordOperandCase("COLLATE", "s COLLATE \"C\""),
      KeywordOperandCase("AT TIME ZONE", "ts AT TIME ZONE 'UTC'"),
      KeywordOperandCase("BETWEEN", "a BETWEEN 1 AND 10"),
      KeywordOperandCase("NOT BETWEEN", "a NOT BETWEEN 100 AND 200"),
      KeywordOperandCase("LIKE", "s LIKE 'a%'"),
      KeywordOperandCase("NOT LIKE", "s NOT LIKE 'z%'"),
      KeywordOperandCase("ILIKE", "s ILIKE 'A%'"),
      KeywordOperandCase("NOT ILIKE", "s NOT ILIKE 'Z%'"),
      KeywordOperandCase("SIMILAR TO", "s SIMILAR TO 'a%'"),
      KeywordOperandCase("NOT SIMILAR TO", "s NOT SIMILAR TO 'z%'"),
      KeywordOperandCase("AND", "a > 1 AND b > 1"),
      KeywordOperandCase("OR", "a > 100 OR b > 1"),
      KeywordOperandCase("INTERVAL ... DAY", "INTERVAL '1' DAY", fromClause = ""),
      KeywordOperandCase("INTERVAL ... DAY TO SECOND", "INTERVAL '1 2:3:4' DAY TO SECOND", fromClause = ""),
      KeywordOperandCase("OPERATOR(pg_catalog.+)", "a OPERATOR(pg_catalog.+) b"),
      KeywordOperandCase("ARRAY[...]", "ARRAY[1, 2, 3]", fromClause = ""),
      KeywordOperandCase("subscript", "arr[1]"),
      KeywordOperandCase("whole-row cast (.*)", "(t.*)::text"),
      KeywordOperandCase("CAST(x AS text)", "CAST(a AS text)"),
      KeywordOperandCase("x::text", "a::text"),
    )

    @JvmStatic
    fun corpus(): List<KeywordOperandCase> = CORPUS
  }
}
