package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class StringsTest {

  @Test
  fun `simple lowercase word uppercases`() {
    assertThat("happy".toUpperSnakeCase()).isEqualTo("HAPPY")
  }

  @Test
  fun `snake_case converts to UPPER_SNAKE_CASE`() {
    assertThat("very_happy".toUpperSnakeCase()).isEqualTo("VERY_HAPPY")
  }

  @Test
  fun `camelCase inserts underscores`() {
    assertThat("inProgress".toUpperSnakeCase()).isEqualTo("IN_PROGRESS")
  }

  @Test
  fun `already UPPER_SNAKE_CASE stays unchanged`() {
    assertThat("ALREADY_UPPER".toUpperSnakeCase()).isEqualTo("ALREADY_UPPER")
  }

  @Test
  fun `mixed case with numbers`() {
    assertThat("level2Boss".toUpperSnakeCase()).isEqualTo("LEVEL2_BOSS")
  }

  @Test
  fun `consecutive uppercase treated as acronym`() {
    assertThat("HTMLParser".toUpperSnakeCase()).isEqualTo("HTML_PARSER")
  }

  @Test
  fun `single character`() {
    assertThat("a".toUpperSnakeCase()).isEqualTo("A")
  }

  @Test
  fun `hyphenated converts to underscores`() {
    assertThat("not-started".toUpperSnakeCase()).isEqualTo("NOT_STARTED")
  }
}
