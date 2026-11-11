package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import norm.generator.libpgquery.KeywordKind
import norm.generator.libpgquery.TokenNumber
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ParsedSqlTest {

  @Nested
  inner class SignificantTokens {

    private val sql = "SELECT U&\"d!0061t\" UESCAPE '!' FROM t"

    private fun significantTexts(text: String): List<String> =
      ParsedSql(text).significantTokens.map { text.substring(it.start, it.end) }

    @Test
    fun `a Unicode identifier with its UESCAPE clause is one token`() {
      val parsed = ParsedSql(sql)
      val merged = parsed.significantTokens.single { it.token == TokenNumber.UNICODE_IDENTIFIER }

      assertThat(sql.substring(merged.start, merged.end)).isEqualTo("U&\"d!0061t\" UESCAPE '!'")
      assertThat(merged.keywordKind).isEqualTo(KeywordKind.NONE)
      assertThat(parsed.significantTokens.size).isEqualTo(4)
    }

    @Test
    fun `comments between the parts of the clause are dropped and the clause is one token`() {
      val text = "SELECT U&\"d!0061t\" /* a */ UESCAPE -- b\n '!' FROM t"

      assertThat(significantTexts(text))
        .isEqualTo(listOf("SELECT", "U&\"d!0061t\" /* a */ UESCAPE -- b\n '!'", "FROM", "t"))
    }

    @Test
    fun `a Unicode string with a UESCAPE clause is not merged`() {
      assertThat(significantTexts("SELECT U&'x' UESCAPE '!'")).isEqualTo(listOf("SELECT", "U&'x'", "UESCAPE", "'!'"))
    }

    @Test
    fun `tokens keeps the parts of the clause apart`() {
      assertThat(ParsedSql(sql).tokens.size).isEqualTo(6)
    }

    @Test
    fun `an identifier that is not Unicode-escaped keeps its tokens`() {
      assertThat(significantTexts("SELECT a UESCAPE '!'")).isEqualTo(listOf("SELECT", "a", "UESCAPE", "'!'"))
    }
  }
}
