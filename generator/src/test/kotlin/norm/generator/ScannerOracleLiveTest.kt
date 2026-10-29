package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import norm.generator.baseline.GLUED_ISSUE_INPUTS
import norm.generator.baseline.ScannerCorpus
import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * One claim of a scanner function that a live server contradicts.
 *
 * @property scenario scenario directory as `<root>/<scenario>`.
 * @property query name of the query in the scenario's `queries.sql`.
 * @property claim what is compared, for example `parameterCount`.
 * @property live what PostgreSQL or the JDBC driver reports.
 * @property main what the scanner functions return.
 */
internal data class OracleDisagreement(
  val scenario: String,
  val query: String,
  val claim: String,
  val live: String,
  val main: String,
)

/**
 * An [OracleDisagreement] that is already filed.
 *
 * @property issue number of the GitHub issue that tracks it.
 */
internal data class KnownOracleDisagreement(val issue: Int, val disagreement: OracleDisagreement)

/**
 * Compares the hand-written scanner functions with a live server on every query of every scenario except the embed
 * scenarios: parameter counts from the JDBC driver and from `PREPARE`, and the result column count and labels from
 * the driver. Each statement is also rebuilt with every item's expression in parentheses, which the server must
 * describe exactly as the original, so an expression that ends early or late is caught.
 *
 * [KNOWN_DISAGREEMENTS] lists the disagreements that exist. The test requires the observed set to equal it, so
 * fixing a disagreement fails the test until its entry is removed.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class ScannerOracleLiveTest {

  data class ScenarioCase(val directory: Path) {
    override fun toString(): String = ScannerCorpus.scenarioKey(directory)
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  fun `scanner functions agree with the live server`(case: ScenarioCase) {
    val scenario = ScannerCorpus.scenarioKey(case.directory)
    resetPublicSchema()
    connection.createStatement().use { it.execute(case.directory.resolve("schema.sql").readText()) }

    val observed = QueryFileParser.parse(case.directory.resolve("queries.sql").readText(), "").flatMap { query ->
      disagreements(scenario, query.name, query.sql)
    }

    val known = KNOWN_DISAGREEMENTS.map { it.disagreement }.filter { it.scenario == scenario }
    assertThat(observed.sortedBy(::sortKey)).isEqualTo(known.sortedBy(::sortKey))
  }

  @Test
  fun `parenthesized items agree with the live server on every literal and issue entry`() {
    resetPublicSchema()
    connection.createStatement().use { it.execute(LITERAL_SCHEMA) }
    val observed = ScannerCorpus.load()
      .filterNot { it.key.startsWith("test-scenarios") || '\u0000' in it.text }
      .mapNotNull { entry -> parenthesizedItemsDisagreement(entry.key, entry.text) }
    assertThat(observed).isEqualTo(emptyList())
  }

  @Test
  fun `the glued select lists are accepted by the server and yield items`() {
    resetPublicSchema()
    connection.createStatement().use { it.execute(LITERAL_SCHEMA) }
    val withoutItems = GLUED_ISSUE_INPUTS.filter { sql ->
      describe(sql) == "rejected" || parseOutputItemsWithAlias(sql).isEmpty()
    }
    assertThat(withoutItems).isEqualTo(emptyList())
  }

  @Test
  fun `a known disagreement names a scenario that is checked`() {
    val checked = scenarios().map { ScannerCorpus.scenarioKey(it.directory) }.toSet()
    assertThat(KNOWN_DISAGREEMENTS.map { it.disagreement.scenario }.filter { it !in checked }).isEqualTo(emptyList())
  }

  private fun sortKey(disagreement: OracleDisagreement): String =
    "${disagreement.scenario}\u0000${disagreement.query}\u0000${disagreement.claim}"

  private fun disagreements(scenario: String, query: String, sql: String): List<OracleDisagreement> {
    val found = mutableListOf<OracleDisagreement>()

    fun compare(claim: String, live: Any?, main: Any?) {
      if (live.toString() != main.toString()) {
        found += OracleDisagreement(scenario, query, claim, live.toString(), main.toString())
      }
    }

    connection.prepareStatement(sql).use { statement ->
      compare("parameterCount", statement.parameterMetaData.parameterCount, placeholderPositions(sql).size)
      comparePreparedParameters(sql, ::compare)
      compare("rewrittenSqlParses", "true", parsesAfterRewrite(sql))
      statement.metaData?.let { compareColumns(sql, it, ::compare) }
    }
    parenthesizedItemsDisagreement(scenario, sql, query)?.let { found += it }
    return found
  }

  private fun parenthesizedItemsDisagreement(scope: String, sql: String, query: String = ""): OracleDisagreement? {
    val rewritten = parenthesizeItems(sql) ?: return null
    val original = describe(sql)
    val parenthesized = describe(rewritten)
    if (original == parenthesized) return null
    return OracleDisagreement(scope, query, "parenthesizedItems", original, "$parenthesized for: $rewritten")
  }

  /** Whether the server accepts [sql] and, when it returns rows, each column's label and type name. */
  private fun describe(sql: String): String = try {
    connection.prepareStatement(sql).use { statement ->
      val metaData = statement.metaData
      if (metaData == null) {
        "no result set"
      } else {
        (1..metaData.columnCount).joinToString { "${metaData.getColumnLabel(it)}:${metaData.getColumnTypeName(it)}" }
      }
    }
  } catch (_: SQLException) {
    "rejected"
  } catch (_: ArrayIndexOutOfBoundsException) {
    // pgjdbc fails this way when the text has a `$n` parameter the driver did not count.
    "rejected"
  }

  /**
   * [sql] with each item's expression in parentheses and its alias kept, or `null` when there is nothing to rewrite:
   * no items, or a star item.
   */
  private fun parenthesizeItems(sql: String): String? {
    val items = parseOutputItemsWithAlias(sql)
    if (items.isEmpty() || items.any { it.selectItem.isStar }) return null
    val statement = parseOrNull(replaceParameterPlaceholdersKeepingOffsets(sql))?.let(::findOutputStatement)
    val starts = statement?.targets?.map { it.intField("location") } ?: return null
    if (starts.size != items.size || starts.any { it == null }) return null
    val result = StringBuilder()
    var copiedUpTo = 0
    for ((index, item) in items.withIndex()) {
      val start = starts[index]!!
      result.append(sql, copiedUpTo, start).append('(').append(item.selectItem.expression).append(')')
      copiedUpTo = start + item.selectItem.expression.length
    }
    return result.append(sql, copiedUpTo, sql.length).toString()
  }

  private fun parsesAfterRewrite(sql: String): String = try {
    LibPgQuery.parse(replaceParameterPlaceholdersKeepingOffsets(sql))
    "true"
  } catch (exception: LibPgQueryException) {
    "parse failed: ${exception.message}"
  }

  private fun comparePreparedParameters(sql: String, compare: (String, Any?, Any?) -> Unit) {
    var sumOfPartCounts = 0
    for ((index, part) in splitStatements(sql).withIndex()) {
      val text = part.substring(explainedStatementStart(part))
      val mainCount = placeholderPositions(text).size
      sumOfPartCounts += mainCount
      if (!startsPreparableStatement(text)) continue
      val replaced = replaceParameterPlaceholders(text) { "\$${it + 1}" }
      compare("part[$index].prepareParameterCount", preparedParameterCount(replaced), mainCount)
    }
    connection.prepareStatement(sql).use {
      compare("sumOfPartPlaceholders", it.parameterMetaData.parameterCount, sumOfPartCounts)
    }
  }

  private fun preparedParameterCount(replaced: String): String = try {
    connection.createStatement().use { statement ->
      statement.execute("PREPARE oracle_probe AS $replaced")
      try {
        statement.executeQuery(
          "SELECT cardinality(parameter_types) FROM pg_prepared_statements WHERE name = 'oracle_probe'",
        )
          .use { resultSet ->
            resultSet.next()
            resultSet.getInt(1).toString()
          }
      } finally {
        statement.execute("DEALLOCATE oracle_probe")
      }
    }
  } catch (exception: SQLException) {
    "PREPARE failed: ${exception.message}"
  }

  private fun compareColumns(sql: String, metaData: java.sql.ResultSetMetaData, compare: (String, Any?, Any?) -> Unit) {
    val items = parseOutputItemsWithAlias(sql)
    if (items.isNotEmpty() && items.none { it.selectItem.isStar }) {
      compare("columnCount", metaData.columnCount, items.size)
    }
    if (alignSelectItems(sql, metaData.columnCount).isEmpty()) return
    for ((index, item) in items.withIndex()) {
      val expected = expectedLabel(item) ?: continue
      compare("columnLabel[$index]", metaData.getColumnLabel(index + 1), expected)
    }
  }

  private fun expectedLabel(item: OutputItemWithAlias): String? =
    item.alias?.let(::logicalIdentifier) ?: item.selectItem.columnName

  private fun resetPublicSchema() {
    connection.createStatement().use {
      it.execute(
        """
        DEALLOCATE ALL;
        DROP SCHEMA public CASCADE;
        CREATE SCHEMA public;
        GRANT ALL ON SCHEMA public TO public;
        """.trimIndent(),
      )
    }
  }

  companion object {
    private const val LITERAL_SCHEMA = """
      CREATE TABLE t (id int, a int, b int, c int, x text, y text, ts timestamptz, tz text, name text, description text);
      CREATE TABLE s (id int, "from" int, "is" int, "returning" int, a int, b int);
      CREATE TABLE users (id int, email text, age int, preferences text);
    """

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_scanner_oracle_test")

    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::connection.isInitialized) connection.close()
    }

    @JvmStatic
    fun scenarios(): List<ScenarioCase> = ScannerCorpus.scenarioDirectories()
      .filter { it.name !in SourceReferenceLiveVerificationTest.EMBED_SCENARIOS }
      .map(::ScenarioCase)
  }
}
