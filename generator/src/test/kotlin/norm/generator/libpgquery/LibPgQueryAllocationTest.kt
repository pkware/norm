package norm.generator.libpgquery

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class LibPgQueryAllocationTest {

  @Test
  fun `allocate returns a non-zero address`() {
    val instance = newInstance()
    val address = instance.allocate(16)
    assertThat(address).isGreaterThan(0)
  }

  @Test
  fun `allocate fails when malloc returns zero`() {
    val instance = newInstance()
    assertFailure { instance.allocate(0xFFFF_FFFFL) }
      .isInstanceOf(IllegalStateException::class)
      .hasMessage("malloc failed to allocate 4294967295 bytes")
  }
}
