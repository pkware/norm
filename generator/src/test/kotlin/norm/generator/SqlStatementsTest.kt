package norm.generator

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SqlStatementsTest {

  @Nested
  inner class SplitStatements {
    @Test
    fun `a single statement that covers the whole text is the given ParsedSql`() {
      val parsed = ParsedSql("SELECT 1")
      assertThat(splitStatements(parsed).single()).isSameInstanceAs(parsed)
    }

    @Test
    fun `each statement is a part of the given ParsedSql`() {
      val parsed = ParsedSql("SELECT 1; SELECT 2")
      val statements = splitStatements(parsed)
      assertThat(statements[0]).isSameInstanceAs(parsed.part(0, 8))
      assertThat(statements[1]).isSameInstanceAs(parsed.part(9, 18))
    }

    @Test
    fun `a semicolon at the top level splits and each part keeps its own text`() {
      assertThat(
        splitStatements(ParsedSql("UPDATE t SET a = ? WHERE id = ?; DELETE FROM u WHERE id = ?")).map {
          it.sql
        },
      )
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
      assertThat(splitStatements(ParsedSql(sql)).size).isEqualTo(1)
    }

    @Test
    fun `a semicolon inside parentheses does not split`() {
      val sql = "CREATE RULE r AS ON UPDATE TO t DO ALSO (UPDATE u SET a = ?; DELETE FROM u WHERE id = ?)"

      assertThat(splitStatements(ParsedSql(sql)).map { it.sql }).containsExactly(sql)
    }

    @Test
    fun `a part with nothing but whitespace and comments is dropped`() {
      assertThat(
        splitStatements(ParsedSql("SELECT 1; -- note\n ;/* x */ ;")).map {
          it.sql
        },
      ).containsExactly("SELECT 1")
      assertThat(splitStatements(ParsedSql(" -- note\n /* x */ ")).map { it.sql }).isEmpty()
      assertThat(splitStatements(ParsedSql("")).map { it.sql }).isEmpty()
    }

    @Test
    fun `the semicolons of a BEGIN ATOMIC body do not split`() {
      val sql = "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT ?; SELECT 2; END"

      assertThat(splitStatements(ParsedSql(sql)).map { it.sql }).containsExactly(sql)
      assertThat(splitStatements(ParsedSql("$sql; SELECT 3")).map { it.sql }).containsExactly(sql, " SELECT 3")
    }

    @Test
    fun `a parser split that fails keeps the scanner's parts`() {
      assertThat(
        splitStatements(ParsedSql("SELECT 1; FROM x y; SELECT 2")).map {
          it.sql
        },
      ).containsExactly("SELECT 1", " FROM x y", " SELECT 2")
    }

    @Test
    fun `text the lexer rejects is one part`() {
      assertThat(
        splitStatements(ParsedSql("SELECT \"unterminated; SELECT 2")).map {
          it.sql
        },
      ).containsExactly("SELECT \"unterminated; SELECT 2")
    }

    @Test
    fun `text with unbalanced parentheses is no statement`() {
      assertThat(splitStatements(ParsedSql("SELECT 1); SELECT ?")).map { it.sql }).isEmpty()
      assertThat(splitStatements(ParsedSql("(SELECT 1")).map { it.sql }).isEmpty()
    }

    @Test
    fun `text with no keyword is no statement`() {
      assertThat(splitStatements(ParsedSql("1")).map { it.sql }).isEmpty()
      assertThat(splitStatements(ParsedSql("1; 2")).map { it.sql }).isEmpty()
    }
  }

  @Nested
  inner class ExplainedStatementStart {
    private fun wrapped(sql: String) = sql.substring(explainedStatementStart(ParsedSql(sql))).trim()

    @Test
    fun `a statement that does not start with EXPLAIN starts at zero`() {
      assertThat(explainedStatementStart(ParsedSql("UPDATE t SET a = ?"))).isEqualTo(0)
      assertThat(explainedStatementStart(ParsedSql("  -- EXPLAIN\n SELECT 1"))).isEqualTo(0)
      assertThat(explainedStatementStart(ParsedSql(""))).isEqualTo(0)
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
      assertThat(explainedStatementStart(ParsedSql("EXPLAIN"))).isEqualTo(0)
      assertThat(explainedStatementStart(ParsedSql("EXPLAIN ANALYZE"))).isEqualTo(0)
      assertThat(explainedStatementStart(ParsedSql("EXPLAIN (ANALYZE"))).isEqualTo(0)
    }
  }

  @Nested
  inner class StartsPreparableStatement {
    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = ["SELECT", "VALUES", "TABLE", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "select", "(SELECT"],
    )
    fun `the first significant token opens a statement that PREPARE accepts`(opening: String) {
      assertThat(startsPreparableStatement(ParsedSql("/* note */ -- more\n $opening 1"))).isTrue()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["EXPLAIN", "DECLARE", "CREATE", "COPY", "FETCH", "explain", "(EXPLAIN", "'SELECT'", "1"])
    fun `any other first token does not`(opening: String) {
      assertThat(startsPreparableStatement(ParsedSql("/* note */ -- more\n $opening 1"))).isFalse()
    }

    @Test
    fun `text with no token does not`() {
      assertThat(startsPreparableStatement(ParsedSql(""))).isFalse()
      assertThat(startsPreparableStatement(ParsedSql(" -- SELECT"))).isFalse()
    }

    @Test
    fun `every opening parenthesis is skipped`() {
      assertThat(startsPreparableStatement(ParsedSql("((SELECT 1))"))).isTrue()
    }

    @Test
    fun `a comment after an opening parenthesis is skipped and no token after it does not open a statement`() {
      assertThat(startsPreparableStatement(ParsedSql("-- x\n("))).isFalse()
    }

    @Test
    fun `text that PostgreSQL rejects still starts a statement by its first token`() {
      assertThat(startsPreparableStatement(ParsedSql("TABLE 1 WHERE ? = 1"))).isTrue()
    }
  }

  @Nested
  inner class WithoutIntoClause {
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

      assertThat(removals.keys.associateWith { withoutIntoClause(ParsedSql(it))?.sql }).isEqualTo(removals)
    }

    @Test
    fun `removes only the INTO of the SELECT when a CTE inserts`() {
      val sql = "WITH w AS (INSERT INTO t (a) VALUES (1) RETURNING a) SELECT a INTO c FROM w"

      assertThat(withoutIntoClause(ParsedSql(sql))?.sql)
        .isEqualTo("WITH w AS (INSERT INTO t (a) VALUES (1) RETURNING a) SELECT a  FROM w")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "SELECT a FROM t WHERE note = 'INTO x'",
        "SELECT \"into\" FROM t -- INTO x",
        "SELECT a FROM t /* INTO x */",
      ],
    )
    fun `an INTO in a literal, a quoted name, or a comment gives null`(sql: String) {
      assertThat(withoutIntoClause(ParsedSql(sql))?.sql).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = ["INSERT INTO t (a) VALUES (?)", "MERGE INTO t USING u ON t.id = u.id WHEN MATCHED THEN DELETE"],
    )
    fun `an INTO of INSERT or MERGE gives null`(sql: String) {
      assertThat(withoutIntoClause(ParsedSql(sql))?.sql).isNull()
    }

    @Test
    fun `removes the INTO of the leftmost branch of a set operation`() {
      assertThat(
        withoutIntoClause(ParsedSql("SELECT a INTO c FROM t UNION SELECT 1"))?.sql,
      ).isEqualTo("SELECT a  FROM t UNION SELECT 1")
    }

    @Test
    fun `removes the INTO of a parenthesized SELECT`() {
      assertThat(withoutIntoClause(ParsedSql("(SELECT a INTO c FROM t)"))?.sql).isEqualTo("(SELECT a  FROM t)")
    }

    @Test
    fun `removes a catalog, schema and table name`() {
      assertThat(withoutIntoClause(ParsedSql("SELECT a INTO d.s.c FROM t"))?.sql).isEqualTo("SELECT a  FROM t")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "SELECT a INTO UNLOGGED TABLE c FROM t",
        "SELECT a INTO LOCAL TEMP TABLE \"Into\" FROM t",
        "SELECT a INTO GLOBAL TEMPORARY c FROM t",
        "SELECT a INTO UNLOGGED c FROM t",
      ],
    )
    fun `removes the persistence and TABLE keywords before the name`(sql: String) {
      assertThat(withoutIntoClause(ParsedSql(sql))?.sql).isEqualTo("SELECT a  FROM t")
    }

    @Test
    fun `removes a clause with a comment between every token`() {
      val sql = "SELECT/*1*/a/*2*/INTO/*3*/LOCAL/*4*/TEMP/*5*/TABLE/*6*/d/*7*/./*8*/c/*9*/FROM t"

      assertThat(withoutIntoClause(ParsedSql(sql))?.sql).isEqualTo("SELECT/*1*/a/*2*//*9*/FROM t")
    }

    @Test
    fun `removes a clause whose UESCAPE has a comment between its tokens`() {
      val sql = "SELECT a INTO U&\"c!0061p\" /*1*/ UESCAPE /*2*/ '!' /*3*/ FROM t"

      assertThat(withoutIntoClause(ParsedSql(sql))?.sql).isEqualTo("SELECT a  /*3*/ FROM t")
    }

    @Test
    fun `removes a qualified name whose first part has a UESCAPE clause`() {
      val sql = "SELECT a INTO U&\"s!0061\" UESCAPE '!' . c FROM t"

      assertThat(withoutIntoClause(ParsedSql(sql))?.sql).isEqualTo("SELECT a  FROM t")
    }

    @Test
    fun `a question mark in the statement does not hide the clause`() {
      assertThat(withoutIntoClause(ParsedSql("SELECT a INTO c FROM t WHERE a = ? AND b = ?"))?.sql)
        .isEqualTo("SELECT a  FROM t WHERE a = ? AND b = ?")
    }

    @Test
    fun `text that PostgreSQL rejects gives null`() {
      assertThat(withoutIntoClause(ParsedSql("SELECT a INTO c FROM"))?.sql).isNull()
      assertThat(withoutIntoClause(ParsedSql(""))?.sql).isNull()
    }

    @Test
    fun `a NUL character is rejected`() {
      assertFailure {
        withoutIntoClause(ParsedSql("SELECT a INTO c\u0000 FROM t"))
      }.isInstanceOf<IllegalArgumentException>()
    }
  }
}
