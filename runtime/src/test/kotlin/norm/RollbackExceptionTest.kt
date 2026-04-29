package norm

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNotInstanceOf
import org.junit.jupiter.api.Test

class RollbackExceptionTest {

  @Test
  fun `is not an Exception`() {
    val rollback = RollbackException()
    assertThat(rollback).isNotInstanceOf<Exception>()
  }

  @Test
  fun `not caught by catch Exception`() {
    var caughtByException = false
    try {
      throw RollbackException()
    } catch (_: Exception) {
      caughtByException = true
    } catch (_: Throwable) {
      // expected path
    }
    assertThat(caughtByException).isFalse()
  }
}
