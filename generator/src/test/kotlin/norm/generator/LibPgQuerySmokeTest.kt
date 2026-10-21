package norm.generator

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class LibPgQuerySmokeTest {
  @Test
  fun `parses SELECT 1`() {
    val json = LibPgQuery.parseJsonText("SELECT 1")
    println("SMOKE parse SELECT 1 => $json")
    assertThat(LibPgQuery.parseJson("SELECT 1")["stmts"].asJsonArray.size()).isEqualTo(1)
  }

  @Test
  fun `reports a syntax error through the setjmp error path and keeps serving calls`() {
    assertFailure { LibPgQuery.parseJson("SELEC 1") }
      .isInstanceOf(LibPgQueryError::class)
      .hasMessage("syntax error at or near \"SELEC\"")
    assertThat(LibPgQuery.parseJson("SELECT 2")["stmts"].asJsonArray.size()).isEqualTo(1)
  }
}
