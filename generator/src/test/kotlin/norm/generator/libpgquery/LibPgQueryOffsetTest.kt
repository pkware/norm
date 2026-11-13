package norm.generator.libpgquery

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LibPgQueryOffsetTest {
  private val grinning = "😀"

  @Nested
  inner class ScanOffsets {

    @Test
    fun `token bounds are String indexes after a non-BMP literal`() {
      val sql = "SELECT '$grinning' AS emoji, next"
      val texts = LibPgQuery.scan(sql).accepted().map { sql.substring(it.start, it.end) }
      assertThat(texts).isEqualTo(listOf("SELECT", "'$grinning'", "AS", "emoji", ",", "next"))
    }

    @Test
    fun `token bounds are String indexes after a BMP character outside ASCII`() {
      val sql = "SELECT 'café', next"
      val texts = LibPgQuery.scan(sql).accepted().map { sql.substring(it.start, it.end) }
      assertThat(texts).isEqualTo(listOf("SELECT", "'café'", ",", "next"))
    }
  }

  @Nested
  inner class ErrorCursor {

    @Test
    fun `parse error cursor is a String index after a non-BMP literal`() {
      val sql = "SELECT '$grinning$grinning' ) FROM t"
      assertThat(LibPgQuery.parse(sql).rejected().cursorIndex).isEqualTo(sql.indexOf(')'))
    }

    @Test
    fun `scan error cursor is a String index after a non-BMP literal`() {
      val sql = "SELECT '$grinning$grinning', \"unterminated"
      assertThat(LibPgQuery.scan(sql).rejected().cursorIndex).isEqualTo(sql.indexOf('"'))
    }

    @Test
    fun `cursor is null when PostgreSQL reports no position`() {
      val sql = "SELECT " + "ARRAY[".repeat(3000) + "1" + "]".repeat(3000)
      assertThat(LibPgQuery.parse(sql)).isEqualTo(LibPgQueryResult.Rejected("stack depth limit exceeded", null))
    }
  }
}
