package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import norm.generator.libpgquery.RecordingMachine
import org.junit.jupiter.api.Test

class FinalItemExtentTest {

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
