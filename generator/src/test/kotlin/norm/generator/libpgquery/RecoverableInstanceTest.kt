package norm.generator.libpgquery

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RecoverableInstanceTest {
  private val created = AtomicInteger()
  private val recoverable = RecoverableInstance { Any().also { created.incrementAndGet() } }

  @Test
  fun `creates the instance on first use`() {
    assertThat(created.get()).isEqualTo(0)
    recoverable.withInstance { }
    assertThat(created.get()).isEqualTo(1)
  }

  @Nested
  inner class AfterABlockReturns {

    @Test
    fun `returns the value of the block`() {
      assertThat(recoverable.withInstance { 42 }).isEqualTo(42)
    }

    @Test
    fun `the next call reuses the instance`() {
      val first = recoverable.withInstance { it }
      val second = recoverable.withInstance { it }
      assertThat(second).isSameInstanceAs(first)
    }
  }

  @Nested
  inner class WhenTheBlockThrowsALibPgQueryException {

    @Test
    fun `rethrows the exception`() {
      assertFailure { recoverable.withInstance { throw LibPgQueryException("syntax error", null) } }
        .isInstanceOf(LibPgQueryException::class)
        .hasMessage("syntax error")
    }

    @Test
    fun `the next call reuses the instance`() {
      val first = recoverable.withInstance { it }
      assertFailure { recoverable.withInstance { throw LibPgQueryException("syntax error", null) } }
        .isInstanceOf(LibPgQueryException::class)
      assertThat(recoverable.withInstance { it }).isSameInstanceAs(first)
    }
  }

  @Nested
  inner class WhenTheBlockThrowsAnyOtherThrowable {

    @Test
    fun `rethrows the failure`() {
      assertFailure { recoverable.withInstance { error("trap") } }
        .isInstanceOf(IllegalStateException::class)
        .hasMessage("trap")
    }

    @Test
    fun `the next call gets a new instance`() {
      val trapped = recoverable.withInstance { it }
      assertFailure { recoverable.withInstance { error("trap") } }.isInstanceOf(IllegalStateException::class)
      assertThat(recoverable.withInstance { it }).isNotSameInstanceAs(trapped)
      assertThat(created.get()).isEqualTo(2)
    }

    @Test
    fun `an error that is not an exception also discards the instance`() {
      val trapped = recoverable.withInstance { it }
      assertFailure { recoverable.withInstance { throw StackOverflowError("trap") } }
        .isInstanceOf(StackOverflowError::class)
      assertThat(recoverable.withInstance { it }).isNotSameInstanceAs(trapped)
    }

    @Test
    fun `a later success keeps its replacement`() {
      recoverable.withInstance { }
      assertFailure { recoverable.withInstance { error("trap") } }.isInstanceOf(IllegalStateException::class)
      val replacement = recoverable.withInstance { it }
      assertThat(recoverable.withInstance { it }).isSameInstanceAs(replacement)
    }
  }

  @Nested
  inner class WithReplacementFactory {

    @Test
    fun `the block runs on an instance from the replacement`() {
      val replacement = Any()
      val used = recoverable.withReplacementFactory({ replacement }) { recoverable.withInstance { it } }
      assertThat(used).isSameInstanceAs(replacement)
    }

    @Test
    fun `the original instance is back after the block`() {
      val original = recoverable.withInstance { it }
      recoverable.withReplacementFactory({ Any() }) { recoverable.withInstance { } }
      assertThat(recoverable.withInstance { it }).isSameInstanceAs(original)
    }

    @Test
    fun `the original factory is back after the block throws`() {
      assertFailure { recoverable.withReplacementFactory({ Any() }) { error("failed") } }
        .isInstanceOf(IllegalStateException::class)
        .hasMessage("failed")
      val instance = recoverable.withInstance { it }
      assertThat(recoverable.withInstance { it }).isSameInstanceAs(instance)
      assertThat(created.get()).isEqualTo(1)
    }

    @Test
    fun `returns the value of the block`() {
      assertThat(recoverable.withReplacementFactory({ Any() }) { 42 }).isEqualTo(42)
    }
  }

  @Nested
  inner class Concurrency {

    @Test
    fun `callers run one at a time on the same instance`() {
      val active = AtomicInteger()
      val highestActive = AtomicInteger()
      val pool = Executors.newFixedThreadPool(8)
      try {
        val calls = (1..400).map {
          pool.submit<Any> {
            recoverable.withInstance { instance ->
              highestActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
              Thread.yield()
              active.decrementAndGet()
              instance
            }
          }
        }
        val used = calls.map { it.get(1, TimeUnit.MINUTES) }.toSet()
        assertThat(highestActive.get()).isEqualTo(1)
        assertThat(used.size).isEqualTo(1)
      } finally {
        pool.shutdownNow()
      }
    }
  }
}
