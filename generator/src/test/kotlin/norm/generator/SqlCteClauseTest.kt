package norm.generator

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlCteClauseTest {

  private fun definitionsOf(parsed: ParsedSql): List<CteDefinition> =
    parsed.tree?.let(::firstStatement)?.let(::cteDefinitions).orEmpty()

  private fun definitions(sql: String): List<CteDefinition> = definitionsOf(ParsedSql(sql))

  private fun names(sql: String): List<String> = definitions(sql).map { it.name }

  /** The expression and alias of each output item of each CTE body, in declaration order. */
  private fun bodyItems(sql: String): List<List<Pair<String, String?>>> {
    val parsed = ParsedSql(sql)
    return definitionsOf(parsed).map { definition ->
      parsed.outputItemsOf(definition.ctequery).map { it.selectItem.expression to it.alias }
    }
  }

  @Nested
  inner class CteDefinitionsTest {

    @Test
    fun `two definitions report their names and the output items of their bodies`() {
      val sql = "WITH x AS (SELECT 1 AS a), y AS (SELECT 2 AS b) SELECT a FROM x"
      assertThat(names(sql)).isEqualTo(listOf("x", "y"))
      assertThat(bodyItems(sql)).isEqualTo(listOf(listOf("1" to "a"), listOf("2" to "b")))
    }

    @Test
    fun `a nested WITH in a body is absent from the outer clause and found on the body node`() {
      val sql = "WITH o AS (WITH i AS (SELECT 1 AS a) SELECT a FROM i) SELECT a FROM o"
      val parsed = ParsedSql(sql)
      val outer = definitionsOf(parsed)
      assertThat(outer.map { it.name }).isEqualTo(listOf("o"))

      val inner = cteDefinitions(outer[0].ctequery)
      assertThat(inner.map { it.name }).isEqualTo(listOf("i"))
      assertThat(parsed.outputItemsOf(inner[0].ctequery).map { it.selectItem.expression to it.alias })
        .isEqualTo(listOf("1" to "a"))
      assertThat(parsed.outputItemsOf(outer[0].ctequery).map { it.selectItem.expression }).isEqualTo(listOf("a"))
    }

    @Test
    fun `output items of a body node are computed once per node`() {
      val parsed = ParsedSql("WITH x AS (SELECT 1 AS a) SELECT a FROM x")
      val body = definitionsOf(parsed).single().ctequery
      assertThat(parsed.outputItemsOf(body)).isSameInstanceAs(parsed.outputItemsOf(body))
    }

    @Test
    fun `WITH RECURSIVE reports the definition and the items of the leftmost branch`() {
      val sql =
        "WITH RECURSIVE recursive_cte AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM recursive_cte WHERE n < 3) " +
          "SELECT n FROM recursive_cte"
      assertThat(names(sql)).isEqualTo(listOf("recursive_cte"))
      assertThat(bodyItems(sql)).isEqualTo(listOf(listOf("1" to "n")))
    }

    @Test
    fun `SEARCH and CYCLE clauses of a recursive CTE do not hide its definition`() {
      val sql = "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < 3) " +
        "SEARCH DEPTH FIRST BY n SET ordercol CYCLE n SET is_cycle USING path SELECT n FROM t"
      assertThat(names(sql)).isEqualTo(listOf("t"))
    }

    @Test
    fun `data-modifying bodies report their RETURNING items`() {
      val sql = "WITH d AS (DELETE FROM t WHERE id = 1 RETURNING id), " +
        "i AS (INSERT INTO t (id) VALUES (2) RETURNING id), " +
        "u AS (UPDATE t SET id = 3 RETURNING id) SELECT id FROM d"
      assertThat(names(sql)).isEqualTo(listOf("d", "i", "u"))
      assertThat(bodyItems(sql)).isEqualTo(List(3) { listOf("id" to null) })
    }

    @Test
    fun `a Unicode-escaped name is reported as its decoded value`() {
      assertThat(names("WITH U&\"d\\0061t\" AS (SELECT 1) SELECT 1")).isEqualTo(listOf("dat"))
    }

    @Test
    fun `a column list does not hide the body`() {
      val sql = "WITH x(a, b) AS (SELECT 1, 2) SELECT a FROM x"
      assertThat(names(sql)).isEqualTo(listOf("x"))
      assertThat(bodyItems(sql)).isEqualTo(listOf(listOf("1" to null, "2" to null)))
    }

    @Test
    fun `MATERIALIZED and NOT MATERIALIZED hints do not hide the bodies`() {
      val sql = "WITH m AS MATERIALIZED (SELECT 1), n AS NOT MATERIALIZED (SELECT 2) SELECT 1"
      assertThat(names(sql)).isEqualTo(listOf("m", "n"))
      assertThat(bodyItems(sql)).isEqualTo(listOf(listOf("1" to null), listOf("2" to null)))
    }

    @Test
    fun `comments between every token do not change the definitions`() {
      val sql = "WITH /* ( */ x /* ) */ ( /* ( */ c ) /* ( */ AS /* ) */ NOT /* ( */ MATERIALIZED /* ) */ " +
        "( /* ( */ SELECT 1 -- )\n ) /* ( */ , -- (\n y /* ) */ AS /* ( */ ( SELECT 2 ) /* ) */ -- (\n SELECT 1"
      assertThat(names(sql)).isEqualTo(listOf("x", "y"))
      assertThat(bodyItems(sql)).isEqualTo(listOf(listOf("1" to null), listOf("2" to null)))
    }

    @Test
    fun `question mark placeholders in a body are read`() {
      val sql = "WITH x AS (SELECT ? AS a, 'b?' AS b, '{}'::jsonb ?? 'k' AS c) SELECT a FROM x WHERE a = ?"
      assertThat(names(sql)).isEqualTo(listOf("x"))
      assertThat(bodyItems(sql)).isEqualTo(
        listOf(listOf("?" to "a", "'b?'" to "b", "'{}'::jsonb ?? 'k'" to "c")),
      )
    }

    @Test
    fun `a set operation main query still has its definitions`() {
      assertThat(names("WITH x AS (SELECT 1 AS a) SELECT a FROM x UNION SELECT 2")).isEqualTo(listOf("x"))
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
        assertThat(names(sql)).isEqualTo(listOf("x"))
      }
    }

    @Test
    fun `a parenthesized statement yields its definitions`() {
      assertThat(names("(WITH x AS (SELECT 1) SELECT 1 FROM x)")).isEqualTo(listOf("x"))
    }

    @Test
    fun `text that is not a WITH statement yields no definitions`() {
      assertThat(definitions("SELECT 1")).isEmpty()
      assertThat(definitions("SELECT * FROM (WITH x AS (SELECT 1) SELECT 1) AS s")).isEmpty()
      assertThat(definitions("INSERT INTO t WITH x AS (SELECT 1) SELECT * FROM x")).isEmpty()
      assertThat(definitions("")).isEmpty()
    }

    @Test
    fun `text that PostgreSQL rejects yields no definitions`() {
      assertThat(definitions("WITH x AS (SELECT 1), y AS SELECT 2")).isEmpty()
      assertThat(definitions("WITH x AS (SELECT 1")).isEmpty()
      assertThat(definitions("WITH \"x AS (SELECT 1) SELECT 1")).isEmpty()
    }

    @Test
    fun `a 70 byte ASCII name is truncated to 63 bytes`() {
      val name = "c".repeat(70)
      assertThat(names("WITH $name AS (SELECT 1) SELECT 1 FROM $name")).isEqualTo(listOf("c".repeat(63)))
    }

    @Test
    fun `a 32 character multi-byte name is truncated to whole characters within 63 bytes`() {
      val name = "é".repeat(32)
      assertThat(names("WITH $name AS (SELECT 1) SELECT 1 FROM $name")).isEqualTo(listOf("é".repeat(31)))
    }

    @Test
    fun `a plain unquoted name is reported as written`() {
      assertThat(names("WITH c AS (SELECT 1) SELECT * FROM c")).isEqualTo(listOf("c"))
    }

    @Test
    fun `a quoted name is reported without quotes`() {
      assertThat(names("""WITH "MyCte" AS (SELECT 1) SELECT * FROM "MyCte"""")).isEqualTo(listOf("MyCte"))
    }

    @Test
    fun `a mixed-case unquoted name is folded to lower case`() {
      assertThat(names("WITH MyCte AS (SELECT 1) SELECT * FROM MyCte")).isEqualTo(listOf("mycte"))
    }

    @Test
    fun `a closing parenthesis inside a string literal in a body does not end the body`() {
      val sql = """
        WITH note AS (
          SELECT 'closing )'::TEXT AS msg
        ),
        counted AS (
          SELECT length(msg) AS len FROM note
        )
        SELECT len FROM counted
      """.trimIndent()
      assertThat(definitions(sql)).hasSize(2)
      assertThat(names(sql)).isEqualTo(listOf("note", "counted"))
      assertThat(bodyItems(sql)).isEqualTo(
        listOf(listOf("'closing )'::TEXT" to "msg"), listOf("length(msg)" to "len")),
      )
    }

    @Test
    fun `a column named with two dollar signs does not start a dollar quote`() {
      val sql = """
        WITH renamed AS (
          SELECT a${'$'}b${'$'}c AS msg FROM note
        ),
        counted AS (
          SELECT length(msg) AS len FROM renamed
        )
        SELECT len FROM counted
      """.trimIndent()
      assertThat(names(sql)).isEqualTo(listOf("renamed", "counted"))
    }

    @Test
    fun `a CTE name containing a non-ASCII character is read`() {
      val sql = "WITH data€x AS (SELECT 1 AS inner_name) SELECT inner_name AS outer_name FROM data€x"
      assertThat(names(sql)).isEqualTo(listOf("data€x"))
    }

    @Test
    fun `a quoted name with an escaped embedded double quote is read`() {
      assertThat(names("""WITH "He""llo" AS (SELECT 1) SELECT 1 FROM "He""llo"""")).isEqualTo(listOf("He\"llo"))
    }

    @Test
    fun `a dollar-led CTE name yields no definitions since PostgreSQL itself rejects one`() {
      assertThat(definitions("WITH \$x AS (SELECT 1) SELECT a FROM x")).isEmpty()
    }

    @Test
    fun `a digit-led CTE name yields no definitions since PostgreSQL itself rejects one`() {
      assertThat(definitions("WITH 1x AS (SELECT 1) SELECT a FROM x")).isEmpty()
    }

    @Test
    fun `an over-length quoted CTE name is truncated to 63 bytes`() {
      val overLongName = "c".repeat(70)
      val sql = "WITH \"$overLongName\" AS (SELECT 1) SELECT * FROM \"$overLongName\""
      assertThat(names(sql)).isEqualTo(listOf("c".repeat(63)))
    }
  }
}
