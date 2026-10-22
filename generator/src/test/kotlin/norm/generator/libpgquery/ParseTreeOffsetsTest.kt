package norm.generator.libpgquery

import assertk.assertThat
import assertk.assertions.isEqualTo
import norm.generator.JsonValue
import org.junit.jupiter.api.Test

class ParseTreeOffsetsTest {
  private val grinning = "😀"

  @Test
  fun `stmt_len converts as a length when the location fields list it`() {
    val sql = "SELECT '$grinning'; SELECT 2"
    val input = Utf8Input(sql)
    val statement = JsonValue.JsonObject(
      mapOf(
        "stmt_location" to JsonValue.JsonScalar("0"),
        "stmt_len" to JsonValue.JsonScalar(input.bytes.indexOf(';'.code.toByte()).toString()),
      ),
    )
    val converted = ParseTreeOffsets(setOf("stmt_location", "stmt_len")).convert(statement, input)
    assertThat(converted.intField("stmt_len")).isEqualTo(sql.indexOf(';'))
  }
}
