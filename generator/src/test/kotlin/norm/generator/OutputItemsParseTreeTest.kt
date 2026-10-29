package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OutputItemsParseTreeTest {

  private fun expressions(sql: String): List<String> = parseSelectItems(sql).map { it.expression }

  @Nested
  inner class LastItemEnd {

    @Test
    fun `FOR UPDATE after the select list is not part of the last item`() {
      assertThat(expressions("SELECT a, b FROM t FOR UPDATE")).containsExactly("a", "b")
    }

    @Test
    fun `a named window reference ends the last item and the WINDOW clause is excluded`() {
      val sql = "SELECT a, sum(b) OVER w FROM t WINDOW w AS (ORDER BY a)"
      assertThat(expressions(sql)).containsExactly("a", "sum(b) OVER w")
    }

    @Test
    fun `IS DISTINCT FROM stays inside the last item`() {
      assertThat(expressions("SELECT a, a IS DISTINCT FROM b FROM t")).containsExactly("a", "a IS DISTINCT FROM b")
    }

    @Test
    fun `a trailing bare word is the alias and not part of the expression`() {
      val item = parseOutputItemsWithAlias("SELECT a, b c FROM t").last()
      assertThat(item.selectItem.expression).isEqualTo("b")
      assertThat(item.alias).isEqualTo("c")
      assertThat(item.isAliasExplicit).isFalse()
    }

    @Test
    fun `a CASE expression ends at its END`() {
      val sql = "SELECT a, CASE WHEN a = 1 THEN 2 ELSE 3 END FROM t"
      assertThat(expressions(sql)).containsExactly("a", "CASE WHEN a = 1 THEN 2 ELSE 3 END")
    }

    @Test
    fun `a trailing block comment is not part of the last item`() {
      assertThat(expressions("SELECT a, b /* c */ FROM t")).containsExactly("a", "b")
    }

    @Test
    fun `a trailing line comment is not part of the last item`() {
      assertThat(expressions("SELECT a, b -- c\nFROM t")).containsExactly("a", "b")
    }

    @Test
    fun `a trailing comment before the comma is not part of an inner item`() {
      assertThat(expressions("SELECT a /* c */, b FROM t")).containsExactly("a", "b")
    }

    @Test
    fun `a subquery in the last item is kept whole`() {
      val sql = "SELECT a, (SELECT max(b) FROM t) FROM t"
      assertThat(expressions(sql)).containsExactly("a", "(SELECT max(b) FROM t)")
    }

    @Test
    fun `a parenthesized statement ends its last item inside the parenthesis`() {
      assertThat(expressions("(SELECT a, b FROM t)")).containsExactly("a", "b")
    }

    @Test
    fun `the last item of the first branch of a set operation ends before UNION`() {
      assertThat(expressions("SELECT a, b FROM t UNION SELECT c, d FROM t")).containsExactly("a", "b")
    }

    @Test
    fun `a RETURNING list ends at the semicolon`() {
      assertThat(expressions("UPDATE t SET x = 1 RETURNING id, name;")).containsExactly("id", "name")
    }

    @Test
    fun `the first statement that returns rows decides`() {
      assertThat(expressions("BEGIN; SELECT a, b FROM t")).containsExactly("a", "b")
      assertThat(expressions("SELECT a FROM t; SELECT b, c FROM t")).containsExactly("a")
    }

    @Test
    fun `a placeholder operator before the alias keeps its tokens`() {
      assertThat(expressions("SELECT x = ? AS eq, y FROM t")).containsExactly("x = ?", "y")
      assertThat(expressions("SELECT -? AS negated, y FROM t")).containsExactly("-?", "y")
    }

    @Test
    fun `an escaped question mark operator keeps its tokens`() {
      assertThat(expressions("SELECT a ?? b AS has_key, c FROM t")).containsExactly("a ?? b", "c")
      assertThat(expressions("SELECT a ??= b AS r, c FROM t")).containsExactly("a ??= b", "c")
    }

    @Test
    fun `a Unicode-escaped identifier keeps its UESCAPE clause as the last item`() {
      assertThat(expressions("SELECT a, U&\"b\" UESCAPE '!' FROM t")).containsExactly("a", "U&\"b\" UESCAPE '!'")
    }

    @Test
    fun `a placeholder as the whole last item is kept`() {
      assertThat(expressions("SELECT a, ? FROM t")).containsExactly("a", "?")
    }
  }

  @Nested
  inner class AliasRule {

    @Test
    fun `a reserved word after AS is the alias`() {
      val item = parseOutputItemsWithAlias("SELECT 1 AS as").single()
      assertThat(item).isEqualTo(OutputItemWithAlias(SelectItem("1", null, null), "as", isAliasExplicit = true))
    }

    @Test
    fun `a quoted alias keeps its quotes and its doubled quote`() {
      val item = parseOutputItemsWithAlias("SELECT x AS \"He\"\"llo\"").single()
      assertThat(item.alias).isEqualTo("\"He\"\"llo\"")
      assertThat(item.isAliasExplicit).isTrue()
    }

    @Test
    fun `a Unicode-escaped alias keeps its UESCAPE clause`() {
      val item = parseOutputItemsWithAlias("SELECT x AS U&\"y\" UESCAPE '!'").single()
      assertThat(item.selectItem.expression).isEqualTo("x")
      assertThat(item.alias).isEqualTo("U&\"y\" UESCAPE '!'")
      assertThat(item.isAliasExplicit).isTrue()
    }

    @Test
    fun `a quoted AS is not the keyword`() {
      val item = parseOutputItemsWithAlias("SELECT x \"AS\"").single()
      assertThat(item.selectItem.expression).isEqualTo("x")
      assertThat(item.alias).isEqualTo("\"AS\"")
      assertThat(item.isAliasExplicit).isFalse()
    }
  }

  @Nested
  inner class StarRule {

    @Test
    fun `a composite expansion of a function result is a star`() {
      assertThat(parseSelectItems("SELECT (f(x)).* FROM t").single().isStar).isTrue()
    }

    @Test
    fun `a star followed directly by an implicit alias is a star`() {
      val item = parseOutputItemsWithAlias("SELECT u.*whatever FROM users u").single()
      assertThat(item.selectItem.isStar).isTrue()
      assertThat(item.alias).isEqualTo("whatever")
    }

    @Test
    fun `a digit before the dot makes multiplication and not a star`() {
      val item = parseOutputItemsWithAlias("SELECT 2.*3 lbl FROM t").single()
      assertThat(item.selectItem.isStar).isFalse()
      assertThat(item.selectItem.expression).isEqualTo("2.*3")
      assertThat(item.alias).isEqualTo("lbl")
    }

    @Test
    fun `count star is not a star`() {
      assertThat(parseSelectItems("SELECT count(*) FROM t").single().isStar).isFalse()
    }
  }

  @Nested
  inner class RejectedInputs {

    @Test
    fun `text PostgreSQL rejects has no items`() {
      assertThat(parseSelectItems("SELECT col a b FROM t")).isEmpty()
      assertThat(parseSelectItems("SELECT 2€, email FROM users")).isEmpty()
      assertThat(parseSelectItems("UPDATE t SET x = 1 RETURNING id, ) (bogus AS alias")).isEmpty()
      assertThat(parseSelectItems("UPDATE t SET x = 1 RETURNING id, a[b AS c")).isEmpty()
    }

    @Test
    fun `EXPLAIN VALUES and TABLE have no items`() {
      assertThat(parseSelectItems("EXPLAIN SELECT a FROM t")).isEmpty()
      assertThat(parseSelectItems("VALUES (1, 2)")).isEmpty()
      assertThat(parseSelectItems("TABLE t")).isEmpty()
    }

    @Test
    fun `a NUL character is not swallowed`() {
      assertThrows<IllegalArgumentException> { parseSelectItems("SELECT 1\u0000") }
    }
  }
}
