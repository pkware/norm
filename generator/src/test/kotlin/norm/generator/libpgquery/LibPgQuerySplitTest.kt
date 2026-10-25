package norm.generator.libpgquery

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LibPgQuerySplitTest {
  private val grinning = "😀"

  private fun texts(sql: String): List<String> = LibPgQuery.split(sql).map { sql.substring(it.start, it.end) }

  @Nested
  inner class Splitting {

    @Test
    fun `splits two statements at the semicolon`() {
      assertThat(texts("SELECT 1; SELECT 2")).isEqualTo(listOf("SELECT 1", " SELECT 2"))
    }

    @Test
    fun `keeps a semicolon inside a string literal`() {
      assertThat(texts("SELECT ';' AS a; SELECT 2")).isEqualTo(listOf("SELECT ';' AS a", " SELECT 2"))
    }

    @Test
    fun `keeps a semicolon inside parentheses`() {
      assertThat(texts("CREATE RULE r AS ON INSERT TO t DO ALSO (SELECT 1; SELECT 2); SELECT 3"))
        .isEqualTo(listOf("CREATE RULE r AS ON INSERT TO t DO ALSO (SELECT 1; SELECT 2)", " SELECT 3"))
    }

    @Test
    fun `returns no statements for empty text`() {
      assertThat(LibPgQuery.split("")).isEmpty()
    }

    @Test
    fun `returns no statements for semicolons and whitespace`() {
      assertThat(LibPgQuery.split(" ; ;\n")).isEmpty()
    }

    @Test
    fun `splits text that does not parse`() {
      assertThat(texts("SELECT FROM FROM; SELECT 2")).isEqualTo(listOf("SELECT FROM FROM", " SELECT 2"))
    }
  }

  @Nested
  inner class Offsets {

    @Test
    fun `bounds are String indexes after non-BMP characters`() {
      val sql = "SELECT '$grinning'; SELECT '$grinning$grinning'; SELECT 3"
      assertThat(texts(sql)).isEqualTo(
        listOf("SELECT '$grinning'", " SELECT '$grinning$grinning'", " SELECT 3"),
      )
    }

    @Test
    fun `bounds are String indexes after a BMP character outside ASCII`() {
      val sql = "SELECT 'café'; SELECT 2"
      assertThat(texts(sql)).isEqualTo(listOf("SELECT 'café'", " SELECT 2"))
    }
  }

  @Nested
  inner class Failures {

    @Test
    fun `reports a lexer error and keeps serving calls`() {
      assertFailure { LibPgQuery.split("SELECT \"unterminated; SELECT 2") }
        .isInstanceOf(LibPgQueryException::class)
        .hasMessage("unterminated quoted identifier at or near \"\"unterminated; SELECT 2\"")
      assertThat(texts("SELECT 1; SELECT 2")).isEqualTo(listOf("SELECT 1", " SELECT 2"))
    }

    @Test
    fun `rejects text containing a NUL character`() {
      assertFailure { LibPgQuery.split("SELECT 1\u0000; SELECT 2") }.isInstanceOf(IllegalArgumentException::class)
    }
  }

  @Nested
  inner class SplittingWithTheParser {

    private fun parserTexts(sql: String): List<String> =
      LibPgQuery.splitWithParser(sql).map { sql.substring(it.start, it.end) }

    @Test
    fun `splits two statements at the semicolon`() {
      assertThat(parserTexts("SELECT 1; SELECT 2")).isEqualTo(listOf("SELECT 1", "SELECT 2"))
    }

    @Test
    fun `keeps a BEGIN ATOMIC body with its semicolons as one statement`() {
      val function = "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; SELECT 2; END"
      assertThat(parserTexts("$function; SELECT 3")).isEqualTo(listOf(function, "SELECT 3"))
      assertThat(texts("$function; SELECT 3").size).isEqualTo(4)
    }

    @Test
    fun `bounds are String indexes after a non-BMP character`() {
      assertThat(parserTexts("SELECT '$grinning'; SELECT '$grinning$grinning'"))
        .isEqualTo(listOf("SELECT '$grinning'", "SELECT '$grinning$grinning'"))
    }

    @Test
    fun `reports a syntax error and keeps serving calls`() {
      assertFailure { LibPgQuery.splitWithParser("SELECT FROM FROM; SELECT 2") }
        .isInstanceOf(LibPgQueryException::class)
        .hasMessage("syntax error at or near \"FROM\"")
      assertThat(parserTexts("SELECT 1")).isEqualTo(listOf("SELECT 1"))
    }

    @Test
    fun `rejects text containing a NUL character`() {
      assertFailure { LibPgQuery.splitWithParser("SELECT 1\u0000") }.isInstanceOf(IllegalArgumentException::class)
    }
  }

  @Nested
  inner class BackslashToken {

    @Test
    fun `scan returns a backslash as a token`() {
      val sql = "SELECT 1 \\; SELECT 2"
      val texts = LibPgQuery.scan(sql).map { sql.substring(it.start, it.end) }
      assertThat(texts).isEqualTo(listOf("SELECT", "1", "\\", ";", "SELECT", "2"))
    }
  }
}
