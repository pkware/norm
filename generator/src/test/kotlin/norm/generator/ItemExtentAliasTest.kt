package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class ItemExtentAliasTest {

  private fun aliases(sql: String): List<Triple<String, String?, Boolean>> = ParsedSql(sql).outputItems.map {
    Triple(it.selectItem.expression, it.alias, it.isAliasExplicit)
  }

  @Test
  fun `the keyword AS can be an explicit alias in the last item and in an earlier one`() {
    assertThat(aliases("SELECT x AS as, y AS as FROM t"))
      .isEqualTo(listOf(Triple("x", "as", true), Triple("y", "as", true)))
  }

  @Test
  fun `a quoted alias spelled as AS is implicit when AS is not written`() {
    assertThat(aliases("SELECT x \"as\", y \"as\" FROM t"))
      .isEqualTo(listOf(Triple("x", "as", false), Triple("y", "as", false)))
  }

  @Test
  fun `an item without an alias is never explicit`() {
    assertThat(aliases("SELECT x, y FROM t")).isEqualTo(listOf(Triple("x", null, false), Triple("y", null, false)))
  }

  @Test
  fun `a final item whose alias is the last token has an extent`() {
    assertThat(aliases("UPDATE t SET a = 1 RETURNING id, x AS y"))
      .isEqualTo(listOf(Triple("id", null, false), Triple("x", "y", true)))
  }
}
