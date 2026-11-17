package norm.generator.libpgquery

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class LibPgQueryStackTest {

  @Test
  fun `parse rejects deeply nested SQL from a caller with a small stack`() {
    val sql = "SELECT " + "ARRAY[".repeat(3000) + "1" + "]".repeat(3000)
    var result: LibPgQueryResult<ParseResult>? = null
    var caught: Throwable? = null
    val smallStackThread = Thread(
      null,
      {
        try {
          result = LibPgQuery.parse(sql)
        } catch (throwable: Throwable) {
          caught = throwable
        }
      },
      "libpgquery-small-stack-test",
      256 * 1024L,
    )
    smallStackThread.start()
    smallStackThread.join()

    assertThat(caught).isNull()
    assertThat(result).isEqualTo(LibPgQueryResult.Rejected("stack depth limit exceeded", null))
  }
}
