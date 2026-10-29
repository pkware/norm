package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.postgresql.PGResultSetMetaData
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * One input whose items [parseOutputItemsWithAlias] must return as [expected], with a live server's column count and
 * labels agreeing.
 */
internal data class LiveItemsCase(val sql: String, val expected: List<OutputItemWithAlias>) {
  override fun toString(): String = sql
}

private fun item(
  expression: String,
  columnName: String? = null,
  tableName: String? = null,
  alias: String? = null,
  isAliasExplicit: Boolean = alias != null,
): OutputItemWithAlias = OutputItemWithAlias(SelectItem(expression, columnName, tableName), alias, isAliasExplicit)

/**
 * Checks the item count and the labels of [parseOutputItemsWithAlias] against the column count and labels a live server
 * reports for the same statement.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
internal class SelectItemsLiveTest {

  @Nested
  internal inner class KeywordNamedColumns {

    @ParameterizedTest
    @MethodSource("norm.generator.SelectItemsLiveTest#keywordNamedColumnCases")
    fun `items and labels agree with the server`(case: LiveItemsCase) {
      assertThat(liveLabeledItems(case.sql)).isEqualTo(case.expected)
    }
  }

  @Nested
  internal inner class ReturningWithOldAndNew {

    @ParameterizedTest
    @MethodSource("norm.generator.SelectItemsLiveTest#returningWithOldAndNewCases")
    fun `items and labels agree with the server`(case: LiveItemsCase) {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING WITH (OLD, NEW) requires PostgreSQL 18+")
      assertThat(liveLabeledItems(case.sql)).isEqualTo(case.expected)
    }
  }

  @Nested
  internal inner class ImplicitAliasSplit {

    @ParameterizedTest
    @MethodSource("norm.generator.SelectItemsLiveTest#implicitAliasCases")
    fun `the trailing word is the label and not part of the expression`(case: LiveItemsCase) {
      assertThat(liveLabeledItems(case.sql)).isEqualTo(case.expected)
    }
  }

  @Nested
  internal inner class ExplainStatement {

    @Test
    fun `the QUERY PLAN column is not paired with the explained select list`() {
      val sql = "EXPLAIN SELECT a FROM t"
      assertThat(labels(sql)).containsExactly("QUERY PLAN")
      assertThat(parseOutputItemsWithAlias(sql)).isEmpty()
    }
  }

  @Nested
  internal inner class ParenthesizedSetOperation {

    @Test
    fun `the result column is named after the first branch`() {
      val sql = "(SELECT a FROM t) UNION (SELECT b FROM t)"
      assertThat(labels(sql)).containsExactly("a")
      assertThat(parseOutputItemsWithAlias(sql)).containsExactly(item("a", columnName = "a"))
      assertThat(hasTopLevelSetOperation(sql)).isTrue()
    }

    @Test
    fun `a parenthesized query that is not a set operation is not one`() {
      assertThat(hasTopLevelSetOperation("(SELECT a FROM t)")).isFalse()
    }
  }

  @Nested
  internal inner class ParenthesizedColumnReference {

    @Test
    fun `PostgreSQL resolves the parenthesized column to its source column`() {
      val sql = "SELECT (a) AS b FROM t"
      val metaData = connection.prepareStatement(sql).use { it.metaData.unwrap(PGResultSetMetaData::class.java) }
      assertThat(metaData.getBaseColumnName(1)).isEqualTo("a")
      assertThat(parseOutputItemsWithAlias(sql)).containsExactly(item("(a)", columnName = "a", alias = "b"))
    }

    @Test
    fun `an unaliased parenthesized column is labeled with the column name`() {
      val sql = "SELECT (age) FROM users"
      assertThat(labels(sql)).containsExactly("age")
      assertThat(parseOutputItemsWithAlias(sql)).containsExactly(item("(age)", columnName = "age"))
    }
  }

  @Nested
  internal inner class SetOperationBranches {

    @Test
    fun `separately parenthesized branches name the column after the first`() {
      val sql = "(SELECT id FROM parent) UNION (SELECT id FROM child)"
      assertThat(labels(sql)).containsExactly("id")
      assertThat(parseOutputItemsWithAlias(sql)).containsExactly(item("id", columnName = "id"))
    }
  }

  @Nested
  internal inner class StatementsWithoutItems {

    @Test
    fun `an empty select list has no columns`() {
      assertThat(labels("SELECT")).isEmpty()
      assertThat(parseOutputItemsWithAlias("SELECT")).isEmpty()
    }

    @Test
    fun `INSERT SELECT without RETURNING has no result set`() {
      val sql = "INSERT INTO t(a) SELECT 1 AS returning"
      assertThat(connection.prepareStatement(sql).use { it.metaData }).isNull()
      assertThat(parseOutputItemsWithAlias(sql)).isEmpty()
    }
  }

  @Nested
  internal inner class ClausesAfterTheSelectList {

    @Test
    fun `a WHERE clause without FROM is not part of the last item`() {
      val sql = "SELECT 1 AS a, 'x' WHERE true"
      assertThat(labels(sql)).containsExactly("a", "?column?")
      assertThat(parseSelectItems(sql).map { it.expression }).containsExactly("1", "'x'")
    }

    @Test
    fun `an INTO clause is not part of the last item`() {
      val sql = "SELECT a INTO TEMPORARY TABLE copied FROM t"
      assertThat(parseSelectItems(sql).map { it.expression }).containsExactly("a")
    }

    @Test
    fun `a keyword-named explicit alias before INTO is the alias`() {
      assertThat(labels("SELECT a AS insert FROM t")).containsExactly("insert")
      assertThat(parseOutputItemsWithAlias("SELECT a AS insert INTO copied FROM t"))
        .containsExactly(item("a", columnName = "a", alias = "insert"))
    }
  }

  @Nested
  internal inner class MultipleStatements {

    @Test
    fun `the first statement names the columns`() {
      val sql = "SELECT 1 AS a; SELECT 2 AS b, 3 AS c"
      assertThat(labels(sql)).containsExactly("a")
      assertThat(parseOutputItemsWithAlias(sql)).containsExactly(item("1", alias = "a"))
    }
  }

  @Nested
  internal inner class TableShorthand {

    @Test
    fun `TABLE returns every column of the table, which the text does not list`() {
      val sql = "TABLE s"
      assertThat(labels(sql).size).isEqualTo(6)
      assertThat(parseOutputItemsWithAlias(sql)).isEmpty()
    }
  }

  @Nested
  internal inner class RejectedBySyntax {

    @ParameterizedTest
    @MethodSource("norm.generator.SelectItemsLiveTest#rejectedInputs")
    fun `PostgreSQL rejects the input and there are no items`(sql: String) {
      var rejected = false
      try {
        connection.prepareStatement(sql).use { it.metaData }
      } catch (exception: SQLException) {
        rejected = exception.sqlState == SYNTAX_ERROR_STATE
      }
      assertThat(rejected).isTrue()
      assertThat(parseOutputItemsWithAlias(sql)).isEmpty()
    }
  }

  private fun labels(sql: String): List<String> = connection.prepareStatement(sql).use { statement ->
    val metaData = statement.metaData
    (1..metaData.columnCount).map { metaData.getColumnLabel(it) }
  }

  private fun liveLabeledItems(sql: String): List<OutputItemWithAlias> {
    val items = parseOutputItemsWithAlias(sql)
    val labels = labels(sql)
    assertThat(items.size, "item count").isEqualTo(labels.size)
    for ((index, item) in items.withIndex()) {
      val parsedLabel = item.alias?.let(::logicalIdentifier) ?: item.selectItem.columnName
      if (parsedLabel != null) assertThat(parsedLabel, "label of item $index").isEqualTo(labels[index])
    }
    return items
  }

  companion object {
    private const val SYNTAX_ERROR_STATE = "42601"

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_select_items_test")

    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
      connection.createStatement().use {
        it.execute(
          """
          CREATE TABLE t (id int, a int, b int, x int, name text, description text, preferences text, dataas int);
          CREATE TABLE users (id int, email text, age int, preferences text);
          CREATE TABLE parent (id int, name text);
          CREATE TABLE child (id int);
          CREATE TABLE s (id int, "from" int, "is" int, "returning" int, a int, b int);
          CREATE TABLE "٣" (id int, "from" int);
          """.trimIndent(),
        )
      }
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::connection.isInitialized) connection.close()
    }

    @JvmStatic
    fun keywordNamedColumnCases(): List<LiveItemsCase> = listOf(
      LiveItemsCase("SELECT s.from FROM s", listOf(item("s.from", columnName = "from", tableName = "s"))),
      LiveItemsCase("SELECT 1 AS from FROM s", listOf(item("1", alias = "from"))),
      LiveItemsCase("SELECT a AS distinct FROM t", listOf(item("a", columnName = "a", alias = "distinct"))),
      LiveItemsCase(
        "SELECT s.is distinct FROM s",
        listOf(item("s.is", columnName = "is", tableName = "s", alias = "distinct", isAliasExplicit = false)),
      ),
      LiveItemsCase(
        "INSERT INTO t(a) SELECT s.returning FROM s RETURNING a",
        listOf(item("a", columnName = "a")),
      ),
      LiveItemsCase(
        "INSERT INTO t(a) SELECT 1 AS as RETURNING a",
        listOf(item("a", columnName = "a")),
      ),
      LiveItemsCase("SELECT ٣.from FROM ٣", listOf(item("٣.from", columnName = "from", tableName = "٣"))),
    )

    @JvmStatic
    fun returningWithOldAndNewCases(): List<LiveItemsCase> {
      val items = listOf(
        item("o.x", columnName = "x", tableName = "o"),
        item("n.x", columnName = "x", tableName = "n"),
      )
      return listOf(
        "UPDATE t SET x = 1 RETURNING WITH (OLD AS o, NEW AS n) o.x, n.x",
        "INSERT INTO t(x) VALUES (1) RETURNING WITH (OLD AS o, NEW AS n) o.x, n.x",
        "DELETE FROM t RETURNING WITH (OLD AS o, NEW AS n) o.x, n.x",
        "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE RETURNING WITH (OLD AS o, NEW AS n) o.x, n.x",
      ).map { LiveItemsCase(it, items) }
    }

    @JvmStatic
    fun implicitAliasCases(): List<LiveItemsCase> = listOf(
      LiveItemsCase(
        "SELECT a y FROM t",
        listOf(item("a", columnName = "a", alias = "y", isAliasExplicit = false)),
      ),
      LiveItemsCase(
        "SELECT UPPER(name) y, b FROM t",
        listOf(
          item("UPPER(name)", alias = "y", isAliasExplicit = false),
          item("b", columnName = "b"),
        ),
      ),
      LiveItemsCase(
        "SELECT description \"He\"\"llo\" FROM t",
        listOf(
          item("description", columnName = "description", alias = "\"He\"\"llo\"", isAliasExplicit = false),
        ),
      ),
      LiveItemsCase(
        "UPDATE t SET x = 1 RETURNING id y, a",
        listOf(
          item("id", columnName = "id", alias = "y", isAliasExplicit = false),
          item("a", columnName = "a"),
        ),
      ),
      LiveItemsCase(
        "SELECT count(*) total FROM t",
        listOf(item("count(*)", alias = "total", isAliasExplicit = false)),
      ),
      LiveItemsCase(
        "SELECT preferences prefs, id FROM t",
        listOf(
          item("preferences", columnName = "preferences", alias = "prefs", isAliasExplicit = false),
          item("id", columnName = "id"),
        ),
      ),
      LiveItemsCase(
        "SELECT dataAS b FROM t",
        listOf(item("dataAS", columnName = "dataas", alias = "b", isAliasExplicit = false)),
      ),
      LiveItemsCase(
        "SELECT age AS€b FROM users",
        listOf(item("age", columnName = "age", alias = "AS€b", isAliasExplicit = false)),
      ),
      LiveItemsCase(
        "SELECT age x€AS FROM users",
        listOf(item("age", columnName = "age", alias = "x€AS", isAliasExplicit = false)),
      ),
      LiveItemsCase(
        "SELECT 2.*3 lbl, a FROM t",
        listOf(
          item("2.*3", alias = "lbl", isAliasExplicit = false),
          item("a", columnName = "a"),
        ),
      ),
      LiveItemsCase(
        "SELECT 'lit *' foo, id FROM t",
        listOf(
          item("'lit *'", alias = "foo", isAliasExplicit = false),
          item("id", columnName = "id"),
        ),
      ),
      LiveItemsCase(
        "SELECT a * b -- c\n, id FROM t",
        listOf(
          item("a * b"),
          item("id", columnName = "id"),
        ),
      ),
    )

    @JvmStatic
    fun rejectedInputs(): List<String> = listOf(
      "SELECT col a b FROM t",
      "SELECT description \"a\" \"b\" FROM t",
      "UPDATE t SET x = 1 RETURNING id, ) (bogus AS alias",
      "UPDATE t SET x = 1 RETURNING id, a[b AS c",
      "SELECT 2€, email FROM users",
      "SELECT \"\" FROM t",
      "SELECT x AS \"abc FROM t",
      "SELECT x AS 1x FROM t",
      "SELECT x AS ux zz FROM t",
      "SELECT 'a' /* c */\n'b' AS x FROM t",
    )
  }
}
