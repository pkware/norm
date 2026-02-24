package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import org.junit.jupiter.api.Test

class SqlUtilsTest {

  @Test
  fun `splits simple comma-separated items`() {
    val result = splitAtTopLevel("a, b, c", ',')
    assertThat(result).containsExactly("a", " b", " c")
  }

  @Test
  fun `preserves content inside parentheses`() {
    val result = splitAtTopLevel("func(a, b), c", ',')
    assertThat(result).containsExactly("func(a, b)", " c")
  }

  @Test
  fun `handles nested parentheses`() {
    val result = splitAtTopLevel("outer(inner(a, b), c), d", ',')
    assertThat(result).containsExactly("outer(inner(a, b), c)", " d")
  }

  @Test
  fun `returns single item when no delimiter`() {
    val result = splitAtTopLevel("no delimiters here", ',')
    assertThat(result).containsExactly("no delimiters here")
  }

  @Test
  fun `returns single item for empty string`() {
    val result = splitAtTopLevel("", ',')
    assertThat(result).hasSize(1)
    assertThat(result).containsExactly("")
  }

  @Test
  fun `works with non-comma delimiters`() {
    val result = splitAtTopLevel("a;b;c", ';')
    assertThat(result).containsExactly("a", "b", "c")
  }

  @Test
  fun `handles multiple expressions with nested function calls`() {
    val result = splitAtTopLevel("EXISTS(SELECT 1 FROM t) AS valid, COUNT(*) AS total, name", ',')
    assertThat(result).containsExactly("EXISTS(SELECT 1 FROM t) AS valid", " COUNT(*) AS total", " name")
  }

  @Test
  fun `handles deeply nested parentheses`() {
    val result = splitAtTopLevel("encode(digest(hmac(a, b, c), d), e), f", ',')
    assertThat(result).containsExactly("encode(digest(hmac(a, b, c), d), e)", " f")
  }
}
