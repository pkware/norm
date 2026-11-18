package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Test

class ParsedSqlOutputItemsTest {

  @Test
  fun `outputItems and outputItemsOf the output statement share one computation`() {
    val parsed = ParsedSql("SELECT a, b AS c FROM t")
    val statement = parsed.statements().single()
    val output = checkNotNull(statement.outputStatement)

    assertThat(parsed.outputItems).isSameInstanceAs(parsed.outputItemsOf(output))
    assertThat(parsed.outputItems).isSameInstanceAs(parsed.outputItemsOf(statement))
  }

  @Test
  fun `a statement that returns no result set has no output items`() {
    val parsed = ParsedSql("DELETE FROM t")

    assertThat(parsed.outputItems).isEmpty()
    assertThat(parsed.outputItemsOf(parsed.statements().single())).isEmpty()
  }

  private fun ParsedSql.statements() = checkNotNull(tree).statements
}
