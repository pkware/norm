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
      ],
    )
    fun `the legacy words and an option list are skipped`(sql: String) {
      assertThat(wrapped(sql)).isEqualTo("UPDATE t SET a = ?")
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
    fun `a parenthesis followed by a query keyword or another parenthesis opens the wrapped query`(sql: String) {
      assertThat(wrapped(sql)).isEqualTo(sql.removePrefix("EXPLAIN").trim())
    }

    @Test
    fun `a query after an option list or the legacy words is the wrapped statement`() {
      assertThat(wrapped("EXPLAIN (ANALYZE) (SELECT 1)")).isEqualTo("(SELECT 1)")
      assertThat(wrapped("EXPLAIN ANALYZE (SELECT 1)")).isEqualTo("(SELECT 1)")
    }

    @Test
    fun `a keyword that ends the text leaves nothing wrapped`() {
      assertThat(wrapped("EXPLAIN")).isEqualTo("")
      assertThat(wrapped("EXPLAIN ANALYZE")).isEqualTo("")
      assertThat(wrapped("EXPLAIN (ANALYZE")).isEqualTo("")
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
}
