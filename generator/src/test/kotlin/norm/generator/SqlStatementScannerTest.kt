package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SqlStatementScannerTest {

  @Nested
  inner class SplitStatements {
    @Test
    fun `a semicolon at the top level splits and each part keeps its own text`() {
      assertThat(splitStatements("UPDATE t SET a = ? WHERE id = ?; DELETE FROM u WHERE id = ?"))
        .containsExactly("UPDATE t SET a = ? WHERE id = ?", " DELETE FROM u WHERE id = ?")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "UPDATE t SET note = 'a;b', a = ?",
        "UPDATE t SET note = \$\$a;b\$\$, a = ?",
        "UPDATE t SET note = concat(';', 'x'), a = ?",
        "UPDATE t SET note = 'x' /* ; */, a = ? -- ;",
        "UPDATE t SET note = \"a;b\", a = ?",
        "UPDATE t SET note = 'x', a = ?;",
      ],
    )
    fun `a semicolon in a literal, a quoted name, a dollar quote, a comment, or at the end does not split`(
      sql: String,
    ) {
      assertThat(splitStatements(sql).size).isEqualTo(1)
    }

    @Test
    fun `a semicolon inside parentheses does not split`() {
      val sql = "CREATE RULE r AS ON UPDATE TO t DO ALSO (UPDATE u SET a = ?; DELETE FROM u WHERE id = ?)"

      assertThat(splitStatements(sql)).containsExactly(sql)
    }

    @Test
    fun `a part with nothing but whitespace and comments is dropped`() {
      assertThat(splitStatements("SELECT 1; -- note\n ;/* x */ ;")).containsExactly("SELECT 1")
      assertThat(splitStatements(" -- note\n /* x */ ")).isEmpty()
      assertThat(splitStatements("")).isEmpty()
    }

    @Test
    fun `the semicolons of a BEGIN ATOMIC body do not split`() {
      val sql = "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT ?; SELECT 2; END"

      assertThat(splitStatements(sql)).containsExactly(sql)
      assertThat(splitStatements("$sql; SELECT 3")).containsExactly(sql, " SELECT 3")
    }

    @Test
    fun `a parser split that fails keeps the scanner's parts`() {
      assertThat(splitStatements("SELECT 1; FROM x y; SELECT 2")).containsExactly("SELECT 1", " FROM x y", " SELECT 2")
    }

    @Test
    fun `text the lexer rejects is one part`() {
      assertThat(splitStatements("SELECT \"unterminated; SELECT 2")).containsExactly("SELECT \"unterminated; SELECT 2")
    }

    @Test
    fun `text with unbalanced parentheses is no statement`() {
      assertThat(splitStatements("SELECT 1); SELECT ?")).isEmpty()
      assertThat(splitStatements("(SELECT 1")).isEmpty()
    }

    @Test
    fun `text with no keyword is no statement`() {
      assertThat(splitStatements("1")).isEmpty()
      assertThat(splitStatements("1; 2")).isEmpty()
    }
  }

  @Nested
  inner class ExplainedStatementStart {
    private fun wrapped(sql: String) = sql.substring(explainedStatementStart(sql)).trim()

    @Test
    fun `a statement that does not start with EXPLAIN starts at zero`() {
      assertThat(explainedStatementStart("UPDATE t SET a = ?")).isEqualTo(0)
      assertThat(explainedStatementStart("  -- EXPLAIN\n SELECT 1")).isEqualTo(0)
      assertThat(explainedStatementStart("")).isEqualTo(0)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "EXPLAIN UPDATE t SET a = ?",
        "EXPLAIN ANALYZE UPDATE t SET a = ?",
        "explain analyse verbose UPDATE t SET a = ?",
        "EXPLAIN VERBOSE UPDATE t SET a = ?",
        "EXPLAIN (ANALYZE, FORMAT JSON) UPDATE t SET a = ?",
        "/* c */ EXPLAIN -- c\n (ANALYZE true, SETTINGS true, FORMAT 'json') UPDATE t SET a = ?",
        "EXPLAIN (ANALYZE) UPDATE t SET a = ?",
        "EXPLAIN /* x */ ANALYZE -- y\n VERBOSE UPDATE t SET a = ?",
        "EXPLAIN (ANALYZE /* ) */, -- )\n FORMAT JSON) UPDATE t SET a = ?",
      ],
    )
    fun `the legacy words and an option list are skipped`(sql: String) {
      assertThat(wrapped(sql)).isEqualTo("UPDATE t SET a = ?")
    }

    @Test
    fun `a comment after the last token of EXPLAIN stays with the wrapped statement`() {
      assertThat(wrapped("EXPLAIN ANALYZE /* x */ -- y\n UPDATE t SET a = ?"))
        .isEqualTo("/* x */ -- y\n UPDATE t SET a = ?")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "EXPLAIN (SELECT 1)",
        "EXPLAIN ((SELECT 1))",
        "EXPLAIN (VALUES (1))",
        "EXPLAIN (TABLE t)",
        "EXPLAIN (WITH w AS (SELECT 1) SELECT * FROM w)",
      ],
    )
    fun `a parenthesized query is the wrapped statement`(sql: String) {
      assertThat(wrapped(sql)).isEqualTo(sql.removePrefix("EXPLAIN").trim())
    }

    @Test
    fun `a query after an option list or the legacy words is the wrapped statement`() {
      assertThat(wrapped("EXPLAIN (ANALYZE) (SELECT 1)")).isEqualTo("(SELECT 1)")
      assertThat(wrapped("EXPLAIN ANALYZE (SELECT 1)")).isEqualTo("(SELECT 1)")
      assertThat(wrapped("EXPLAIN (ANALYZE, FORMAT JSON) SELECT a FROM t WHERE a = ?"))
        .isEqualTo("SELECT a FROM t WHERE a = ?")
    }

    @Test
    fun `text that PostgreSQL rejects starts at zero`() {
      assertThat(explainedStatementStart("EXPLAIN")).isEqualTo(0)
      assertThat(explainedStatementStart("EXPLAIN ANALYZE")).isEqualTo(0)
      assertThat(explainedStatementStart("EXPLAIN (ANALYZE")).isEqualTo(0)
    }
  }

  @Nested
  inner class StartsPreparableStatement {
    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = ["SELECT", "VALUES", "TABLE", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "select", "(SELECT"],
    )
    fun `the first significant token opens a statement that PREPARE accepts`(opening: String) {
      assertThat(startsPreparableStatement("/* note */ -- more\n $opening 1")).isTrue()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["EXPLAIN", "DECLARE", "CREATE", "COPY", "FETCH", "explain", "(EXPLAIN", "'SELECT'", "1"])
    fun `any other first token does not`(opening: String) {
      assertThat(startsPreparableStatement("/* note */ -- more\n $opening 1")).isFalse()
    }

    @Test
    fun `text with no token does not`() {
      assertThat(startsPreparableStatement("")).isFalse()
      assertThat(startsPreparableStatement(" -- SELECT")).isFalse()
    }
  }

  @Nested
  inner class WithoutEachIntoClause {
    private val reservedWords = setOf("from", "where", "table", "union", "select", "into")

    private fun removals(sql: String) = withoutEachIntoClause(sql, reservedWords).toList()

    @Test
    fun `removes the INTO keyword through the table name`() {
      val removals = mapOf(
        "SELECT a INTO copied FROM t WHERE a = ?" to "SELECT a  FROM t WHERE a = ?",
        "SELECT a INTO TEMPORARY TABLE copied FROM t" to "SELECT a  FROM t",
        "SELECT a INTO temp FROM t" to "SELECT a  FROM t",
        "SELECT a INTO public . \"x y\" FROM t" to "SELECT a  FROM t",
        "SELECT a INTO public.from FROM t" to "SELECT a  FROM t",
        "SELECT a INTO /* x */ copied -- y\n FROM t" to "SELECT a  -- y\n FROM t",
        "SELECT a INTO copied" to "SELECT a ",
        "SELECT a INTO copied UNION SELECT 1" to "SELECT a  UNION SELECT 1",
        "SELECT a AS insert INTO copied FROM t" to "SELECT a AS insert  FROM t",
        "SELECT a INTO U&\"cop\" FROM t" to "SELECT a  FROM t",
        "SELECT a INTO public.u&\"c!0061p\" UESCAPE '!' FROM t" to "SELECT a  FROM t",
      )

      assertThat(removals.keys.associateWith { removals(it).single() }).isEqualTo(removals)
    }

    @Test
    fun `gives one removal per INTO keyword, in order`() {
      val sql = "WITH w AS (INSERT INTO t (a) VALUES (1) RETURNING a) SELECT a INTO c FROM w"

      assertThat(removals(sql)).containsExactly(
        "WITH w AS (INSERT  (a) VALUES (1) RETURNING a) SELECT a INTO c FROM w",
        "WITH w AS (INSERT INTO t (a) VALUES (1) RETURNING a) SELECT a  FROM w",
      )
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["SELECT a FROM t WHERE note = 'INTO x'", "SELECT \"into\" FROM t -- INTO x"])
    fun `an INTO in a literal, a quoted name, or a comment gives no removal`(sql: String) {
      assertThat(removals(sql)).isEmpty()
    }
  }
}
