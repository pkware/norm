package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlCteClauseTest {

  @Nested
  inner class ParseCteClauseTest {

    @Test
    fun `two definitions report the offsets of their body parentheses`() {
      val sql = "WITH x AS (SELECT 1 AS a), y AS (SELECT 2 AS b) SELECT a FROM x"
      val result = parseCteClause(sql)!!
      assertThat(result.definitions).isEqualTo(
        listOf(CteDefinition("x", "x", 10, 24), CteDefinition("y", "y", 32, 46)),
      )
      assertThat(result.mainQueryStart).isEqualTo(48)
    }

    @Test
    fun `a nested WITH in a body is absent from the outer parse and found by parsing the body`() {
      val sql = "WITH o AS (WITH i AS (SELECT 1 AS a) SELECT a FROM i) SELECT a FROM o"
      val outer = parseCteClause(sql)!!
      assertThat(outer.definitions).isEqualTo(listOf(CteDefinition("o", "o", 10, 52)))

      val body = sql.substring(outer.definitions[0].bodyOpenParenthesis + 1, outer.definitions[0].bodyCloseParenthesis)
      assertThat(body).isEqualTo("WITH i AS (SELECT 1 AS a) SELECT a FROM i")
      val inner = parseCteClause(body)!!
      assertThat(inner.definitions).isEqualTo(listOf(CteDefinition("i", "i", 10, 24)))
      assertThat(inner.mainQueryStart).isEqualTo(26)
    }

    @Test
    fun `WITH RECURSIVE reports the definition and the main query start`() {
      val sql =
        "WITH RECURSIVE recursive_cte AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM recursive_cte WHERE n < 3) " +
          "SELECT n FROM recursive_cte"
      val result = parseCteClause(sql)!!
      assertThat(result.definitions).isEqualTo(listOf(CteDefinition("recursive_cte", "recursive_cte", 32, 100)))
      assertThat(result.mainQueryStart).isEqualTo(102)
    }

    @Test
    fun `main query start follows SEARCH and CYCLE clauses of a recursive CTE`() {
      val sql = "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < 3) " +
        "SEARCH DEPTH FIRST BY n SET ordercol CYCLE n SET is_cycle USING path SELECT n FROM t"
      val result = parseCteClause(sql)!!
      assertThat(result.definitions).isEqualTo(listOf(CteDefinition("t", "t", 23, 74)))
      assertThat(result.mainQueryStart).isEqualTo(sql.indexOf("SEARCH"))
    }

    @Test
    fun `data-modifying bodies report their parentheses`() {
      val sql = "WITH d AS (DELETE FROM t WHERE id = 1 RETURNING id), " +
        "i AS (INSERT INTO t (id) VALUES (2) RETURNING id), " +
        "u AS (UPDATE t SET id = 3 RETURNING id) SELECT id FROM d"
      val result = parseCteClause(sql)!!
      assertThat(result.definitions.map { it.name }).isEqualTo(listOf("d", "i", "u"))
      val bodies = result.definitions.map { sql.substring(it.bodyOpenParenthesis + 1, it.bodyCloseParenthesis) }
      assertThat(bodies).isEqualTo(
        listOf(
          "DELETE FROM t WHERE id = 1 RETURNING id",
          "INSERT INTO t (id) VALUES (2) RETURNING id",
          "UPDATE t SET id = 3 RETURNING id",
        ),
      )
      assertThat(result.mainQueryStart).isEqualTo(sql.indexOf("SELECT id FROM d"))
    }

    @Test
    fun `a Unicode-escaped name keeps its token text in rawName and the decoded value in name`() {
      val result = parseCteClause("WITH U&\"d\\0061t\" AS (SELECT 1) SELECT 1")!!
      assertThat(result.definitions[0].rawName).isEqualTo("U&\"d\\0061t\"")
      assertThat(result.definitions[0].name).isEqualTo("dat")
    }

    @Test
    fun `a column list is skipped to find the body parentheses`() {
      val sql = "WITH x(a, b) AS (SELECT 1, 2) SELECT a FROM x"
      assertThat(parseCteClause(sql)!!.definitions).isEqualTo(listOf(CteDefinition("x", "x", 16, 28)))
    }

    @Test
    fun `MATERIALIZED and NOT MATERIALIZED hints are skipped to find the body parentheses`() {
      val sql = "WITH m AS MATERIALIZED (SELECT 1), n AS NOT MATERIALIZED (SELECT 2) SELECT 1"
      assertThat(parseCteClause(sql)!!.definitions).isEqualTo(
        listOf(CteDefinition("m", "m", 23, 32), CteDefinition("n", "n", 57, 66)),
      )
    }

    @Test
    fun `comments between every token do not move the reported offsets`() {
      val sql = "WITH /* ( */ x /* ) */ ( /* ( */ c ) /* ( */ AS /* ) */ NOT /* ( */ MATERIALIZED /* ) */ " +
        "( /* ( */ SELECT 1 -- )\n ) /* ( */ , -- (\n y /* ) */ AS /* ( */ ( SELECT 2 ) /* ) */ -- (\n SELECT 1"
      val result = parseCteClause(sql)!!
      assertThat(result.definitions.map { it.name to it.rawName }).isEqualTo(listOf("x" to "x", "y" to "y"))
      val bodies = result.definitions.map { sql.substring(it.bodyOpenParenthesis + 1, it.bodyCloseParenthesis) }
      assertThat(bodies).isEqualTo(listOf(" /* ( */ SELECT 1 -- )\n ", " SELECT 2 "))
      assertThat(result.mainQueryStart).isEqualTo(sql.lastIndexOf("SELECT 1"))
    }

    @Test
    fun `question mark placeholders in a body keep every offset`() {
      val sql = "WITH x AS (SELECT ? AS a, 'b?' AS b, '{}'::jsonb ?? 'k' AS c) SELECT a FROM x WHERE a = ?"
      val result = parseCteClause(sql)
      assertThat(result!!.definitions).isEqualTo(listOf(CteDefinition("x", "x", 10, 60)))
      assertThat(result.mainQueryStart).isEqualTo(62)
    }

    @Test
    fun `a set operation main query still has its definitions`() {
      val sql = "WITH x AS (SELECT 1 AS a) SELECT a FROM x UNION SELECT 2"
      val result = parseCteClause(sql)!!
      assertThat(result.definitions).isEqualTo(listOf(CteDefinition("x", "x", 10, 24)))
      assertThat(result.mainQueryStart).isEqualTo(26)
    }

    @Test
    fun `a WITH clause on a data-modifying statement is read`() {
      val statements = listOf(
        "WITH x AS (SELECT 1 AS a) INSERT INTO t SELECT a FROM x",
        "WITH x AS (SELECT 1 AS a) UPDATE t SET a = (SELECT a FROM x)",
        "WITH x AS (SELECT 1 AS a) DELETE FROM t WHERE a IN (SELECT a FROM x)",
        "WITH x AS (SELECT 1 AS a) MERGE INTO t USING x ON t.a = x.a WHEN MATCHED THEN DELETE",
      )
      for (sql in statements) {
        assertThat(parseCteClause(sql)!!.definitions).isEqualTo(listOf(CteDefinition("x", "x", 10, 24)))
      }
    }

    @Test
    fun `a parenthesized statement yields its definitions`() {
      val sql = "(WITH x AS (SELECT 1) SELECT 1 FROM x)"
      assertThat(parseCteClause(sql)!!.definitions).isEqualTo(listOf(CteDefinition("x", "x", 11, 20)))
    }

    @Test
    fun `text that is not a WITH statement yields null`() {
      assertThat(parseCteClause("SELECT 1")).isNull()
      assertThat(parseCteClause("SELECT * FROM (WITH x AS (SELECT 1) SELECT 1) AS s")).isNull()
      assertThat(parseCteClause("INSERT INTO t WITH x AS (SELECT 1) SELECT * FROM x")).isNull()
      assertThat(parseCteClause("")).isNull()
    }

    @Test
    fun `text that PostgreSQL rejects yields null`() {
      assertThat(parseCteClause("WITH x AS (SELECT 1), y AS SELECT 2")).isNull()
      assertThat(parseCteClause("WITH x AS (SELECT 1")).isNull()
      assertThat(parseCteClause("WITH \"x AS (SELECT 1) SELECT 1")).isNull()
    }

    @Test
    fun `text with a NUL character yields null`() {
      assertThat(parseCteClause("WITH x AS (SELECT 1) SELECT 1\u0000")).isNull()
    }

    @Test
    fun `a 70 byte ASCII name is truncated to 63 bytes in name and kept whole in rawName`() {
      val name = "c".repeat(70)
      val result = parseCteClause("WITH $name AS (SELECT 1) SELECT 1 FROM $name")!!
      assertThat(result.definitions[0].name).isEqualTo("c".repeat(63))
      assertThat(result.definitions[0].rawName).isEqualTo(name)
    }

    @Test
    fun `a 32 character multi-byte name is truncated to whole characters within 63 bytes`() {
      val name = "é".repeat(32)
      val result = parseCteClause("WITH $name AS (SELECT 1) SELECT 1 FROM $name")!!
      assertThat(result.definitions[0].name).isEqualTo("é".repeat(31))
      assertThat(result.definitions[0].rawName).isEqualTo(name)
    }

    @Test
    fun `plain unquoted name has an identical name and rawName`() {
      val result = parseCteClause("WITH c AS (SELECT 1) SELECT * FROM c")
      assertThat(result!!.definitions[0].name).isEqualTo("c")
      assertThat(result.definitions[0].rawName).isEqualTo("c")
    }

    @Test
    fun `quoted name strips quotes for name but keeps them verbatim in rawName`() {
      val result = parseCteClause("""WITH "MyCte" AS (SELECT 1) SELECT * FROM "MyCte"""")
      assertThat(result!!.definitions[0].name).isEqualTo("MyCte")
      assertThat(result.definitions[0].rawName).isEqualTo("\"MyCte\"")
    }

    @Test
    fun `mixed-case unquoted name is folded in name but preserved as-is in rawName`() {
      val result = parseCteClause("WITH MyCte AS (SELECT 1) SELECT * FROM MyCte")
      assertThat(result!!.definitions[0].name).isEqualTo("mycte")
      assertThat(result.definitions[0].rawName).isEqualTo("MyCte")
    }

    @Test
    fun `CTE body containing a closing parenthesis inside a string literal parses correctly`() {
      // The ')' inside 'closing )' belongs to a string token, so the body closes at the ')' before the comma.
      val sql = """
        WITH note AS (
          SELECT 'closing )'::TEXT AS msg
        ),
        counted AS (
          SELECT length(msg) AS len FROM note
        )
        SELECT len FROM counted
      """.trimIndent()
      val result = parseCteClause(sql)
      assertThat(result!!.definitions).hasSize(2)
      assertThat(result.definitions[0].name).isEqualTo("note")
      assertThat(result.definitions[1].name).isEqualTo("counted")
      val noteBody = sql.substring(
        result.definitions[0].bodyOpenParenthesis + 1,
        result.definitions[0].bodyCloseParenthesis,
      ).trim()
      assertThat(noteBody).isEqualTo("SELECT 'closing )'::TEXT AS msg")
    }

    @Test
    fun `CTE body containing a column named with two dollar signs parses correctly`() {
      // "a$b$c" is an ordinary identifier, not the opening of a "$b$"-tagged dollar-quote.
      val sql = """
        WITH renamed AS (
          SELECT a${'$'}b${'$'}c AS msg FROM note
        ),
        counted AS (
          SELECT length(msg) AS len FROM renamed
        )
        SELECT len FROM counted
      """.trimIndent()
      val result = parseCteClause(sql)
      assertThat(result!!.definitions).hasSize(2)
      assertThat(result.definitions[0].name).isEqualTo("renamed")
      assertThat(result.definitions[1].name).isEqualTo("counted")
    }

    @Test
    fun `CTE name containing a non-ASCII character parses correctly`() {
      // PostgreSQL's lexer admits any byte >= 0x80 inside an unquoted identifier.
      val result = parseCteClause(
        "WITH data€x AS (SELECT 1 AS inner_name) SELECT inner_name AS outer_name FROM data€x",
      )
      assertThat(result).isNotNull()
      assertThat(result!!.definitions).hasSize(1)
      assertThat(result.definitions[0].name).isEqualTo("data€x")
    }

    @Test
    fun `a quoted name with an escaped embedded double quote keeps the whole token in rawName`() {
      // The name is `He"llo`, with one embedded quote.
      val result = parseCteClause("""WITH "He""llo" AS (SELECT 1) SELECT 1 FROM "He""llo"""")
      assertThat(result!!.definitions).hasSize(1)
      assertThat(result.definitions[0].rawName).isEqualTo("\"He\"\"llo\"")
      assertThat(result.definitions[0].name).isEqualTo("He\"llo")
    }

    @Test
    fun `a dollar-led CTE name is not recognized, since PostgreSQL itself rejects one`() {
      // PostgreSQL's grammar rejects `$` at the start of an identifier.
      val result = parseCteClause("WITH \$x AS (SELECT 1) SELECT a FROM x")
      assertThat(result).isNull()
    }

    @Test
    fun `a digit-led CTE name is not recognized, since PostgreSQL itself rejects one`() {
      // PostgreSQL's lexer rejects a digit followed by a letter as trailing junk after a numeric literal.
      val result = parseCteClause("WITH 1x AS (SELECT 1) SELECT a FROM x")
      assertThat(result).isNull()
    }

    @Test
    fun `an over-length unquoted CTE name is truncated to 63 bytes, while rawName keeps the full untruncated text`() {
      val overLongName = "c".repeat(70)
      val sql = "WITH $overLongName AS (SELECT 1) SELECT * FROM $overLongName"
      val result = parseCteClause(sql)
      assertThat(result!!.definitions[0].name).isEqualTo("c".repeat(63))
      assertThat(result.definitions[0].rawName).isEqualTo(overLongName)
    }

    @Test
    fun `an over-length quoted CTE name is truncated to 63 bytes, while rawName keeps the quotes and full text`() {
      val overLongName = "c".repeat(70)
      val sql = "WITH \"$overLongName\" AS (SELECT 1) SELECT * FROM \"$overLongName\""
      val result = parseCteClause(sql)
      assertThat(result!!.definitions[0].name).isEqualTo("c".repeat(63))
      assertThat(result.definitions[0].rawName).isEqualTo("\"$overLongName\"")
    }
  }
}
