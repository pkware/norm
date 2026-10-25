package norm.generator.libpgquery

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RegressStatementTest {

  @Nested
  inner class FirstLine {

    @Test
    fun `skips leading comment lines and blank lines`() {
      val statement = RegressStatement(1, "\n-- fail\n  -- more\n\n  SELECT FROM FROM\n", null)
      assertThat(statement.firstLine).isEqualTo("SELECT FROM FROM")
    }

    @Test
    fun `keeps a block comment line, which is not a line comment`() {
      assertThat(RegressStatement(1, "/* note */ SELECT 1", null).firstLine).isEqualTo("/* note */ SELECT 1")
    }

    @Test
    fun `falls back to the first line when every line is a comment`() {
      assertThat(RegressStatement(1, "-- one\n-- two", null).firstLine).isEqualTo("-- one")
    }
  }

  @Nested
  inner class Justification {

    private val output = listOf(
      "-- fail",
      "SELECT 1 +;",
      "ERROR:  syntax error at or near \";\"",
      "-- fail",
      "SELECT 2;",
      " ?column? ",
      "ERROR:  later",
    )

    private fun entry(firstLine: String, outputLine: Int) =
      RejectEntry("16", "s.sql", 1, firstLine, "s.out", outputLine)

    @Test
    fun `holds when the statement precedes the cited error with no other error between`() {
      assertThat(entry("SELECT 1 +;", 3).isJustified(output, null)).isTrue()
    }

    @Test
    fun `fails when another error lies between the statement and the cited error`() {
      assertThat(entry("SELECT 1 +;", 7).isJustified(output, null)).isFalse()
    }

    @Test
    fun `fails when the cited line is not an error`() {
      assertThat(entry("SELECT 1 +;", 2).isJustified(output, null)).isFalse()
    }

    @Test
    fun `fails when the anchor is a line comment`() {
      assertThat(entry("-- fail", 3).isJustified(output, null)).isFalse()
    }

    @Test
    fun `fails when the next statement is echoed before the cited error`() {
      val skipped = listOf(
        "\\if true",
        "  select 'okay';",
        "\\else",
        "  not okay;",
        "  still not okay",
        "\\endif",
        "do ${'$'}${'$'} begin raise exception 'bar'; end ${'$'}${'$'};",
        "ERROR:  bar",
      )
      assertThat(entry("not okay;", 8).isJustified(skipped, "still not okay")).isFalse()
      assertThat(entry("not okay;", 8).isJustified(skipped, null)).isTrue()
    }

    @Test
    fun `holds when the next statement is not echoed before the cited error`() {
      assertThat(entry("SELECT 1 +;", 3).isJustified(output, "SELECT 2;")).isTrue()
    }

    @Test
    fun `reads an anchor that holds a tab as singleLine writes it`() {
      val lines = listOf("a\tb", "ERROR:  x")
      assertThat(entry(singleLine("a\tb"), 2).isJustified(lines, null)).isTrue()
    }
  }
}
