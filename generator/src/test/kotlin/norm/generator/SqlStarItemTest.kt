package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlStarItemTest {

  @Nested
  inner class ImplicitAlias {

    private fun item(selectList: String): OutputItemWithAlias =
      parseOutputItemsWithAlias("SELECT $selectList FROM t").single()

    @Test
    fun `a bare column with an implicit alias splits into the column and its own alias`() {
      val result = item("description dx")
      assertThat(result.selectItem.expression).isEqualTo("description")
      assertThat(result.selectItem.columnName).isEqualTo("description")
      assertThat(result.alias).isEqualTo("dx")
      assertThat(result.isAliasExplicit).isFalse()
    }

    @Test
    fun `a computed expression with an implicit alias splits into the expression and its alias`() {
      val result = item("UPPER(a) y")
      assertThat(result.selectItem.expression).isEqualTo("UPPER(a)")
      assertThat(result.alias).isEqualTo("y")
      assertThat(result.isAliasExplicit).isFalse()
    }

    @Test
    fun `a bare column with no alias at all has no alias`() {
      val result = item("description")
      assertThat(result.alias).isNull()
      assertThat(result.isAliasExplicit).isFalse()
    }

    @Test
    fun `an identifier with a dollar sign is one token, not an alias boundary`() {
      val result = item("a\$b dx")
      assertThat(result.selectItem.expression).isEqualTo("a\$b")
      assertThat(result.alias).isEqualTo("dx")
    }

    @Test
    fun `a doubled-quote escape inside an implicit quoted alias is not mistaken for its closing quote`() {
      val result = item("description \"He\"\"llo\"")
      assertThat(result.selectItem.expression).isEqualTo("description")
      assertThat(result.alias).isEqualTo("\"He\"\"llo\"")
    }

    @Test
    fun `two bare words after a column are rejected by PostgreSQL, so there are no items`() {
      assertThat(parseOutputItemsWithAlias("SELECT col a b FROM t")).isEmpty()
    }

    @Test
    fun `two quoted identifiers after a column are rejected by PostgreSQL, so there are no items`() {
      assertThat(parseOutputItemsWithAlias("SELECT description \"a\" \"b\" FROM t")).isEmpty()
    }

    @Test
    fun `a Unicode-escape identifier as an implicit alias is the alias`() {
      val result = item("description U&\"x\"")
      assertThat(result.selectItem.expression).isEqualTo("description")
      assertThat(result.alias).isEqualTo("U&\"x\"")
    }
  }
}
