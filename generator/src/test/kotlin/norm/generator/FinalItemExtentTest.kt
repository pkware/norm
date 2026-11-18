package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import norm.generator.libpgquery.PgRegress
import norm.generator.libpgquery.RecordingMachine
import norm.generator.libpgquery.isKeyword
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FinalItemExtentTest {

  @Nested
  inner class RegressCorpus {

    @Test
    fun `a final item with an alias has the alias tokens after its expression`() {
      val failures = mutableListOf<String>()
      var coveredCount = 0
      val statements = PgRegress.files("17").flatMap { it.statements }.filterNot { it.isTokenizerRejected }
      for (statement in statements) {
        val parsed = ParsedSql(statement.text)
        val last = lastTokenOfAliasedFinalItem(parsed) ?: continue
        coveredCount++
        val tokens = parsed.significantTokens
        val hasAliasToken = last + 1 < tokens.size &&
          (!isKeyword(parsed.sql, tokens[last + 1], "AS") || last + 2 < tokens.size)
        if (!hasAliasToken) failures += "${statement.ordinal}: ${statement.text}"
      }

      assertThat(failures).isEmpty()
      assertThat(coveredCount).isGreaterThan(0)
    }

    private fun lastTokenOfAliasedFinalItem(parsed: ParsedSql): Int? {
      val output = parsed.tree?.outputStatement
      val target = output?.targets?.lastOrNull()
      val location = target?.location
      if (output == null || target?.alias == null || location == null) return null
      val firstToken = parsed.significantTokens.binarySearchBy(location) { it.start }
      return if (firstToken < 0) null else extentOfFinalItem(parsed, output, firstToken)
    }
  }

  @Test
  fun `the end of a final item with a multi-token cast is found by probing each candidate end`() {
    val sql = "SELECT id, name::timestamp without time zone FROM author"
    lateinit var items: List<OutputItemWithAlias>

    val calls = RecordingMachine.record { items = ParsedSql(sql).outputItems }

    assertThat(calls.parseTexts).isEqualTo(
      listOf(
        sql,
        "SELECT id,  1  without time zone FROM author",
        "SELECT id,  1  time zone FROM author",
        "SELECT id,  1  zone FROM author",
        "SELECT id,  1  FROM author",
      ),
    )
    assertThat(items.map { it.selectItem.expression }).isEqualTo(listOf("id", "name::timestamp without time zone"))
  }
}
