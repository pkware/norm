package norm.generator

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class CommandTest {

  @Nested
  inner class SqlFormRoundTrip {

    @ParameterizedTest
    @EnumSource(Command::class)
    fun `every entry round-trips through its SQL form`(command: Command) {
      assertThat(Command.fromSql(command.toString())).isEqualTo(command)
    }
  }

  @Nested
  inner class UnrecognizedValues {

    @Test
    fun `an unknown value is rejected`() {
      val exception = assertFailure { Command.fromSql(":bogus") }
      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains(":bogus")
    }

    @Test
    fun `a wrong-case value is rejected`() {
      val exception = assertFailure { Command.fromSql(":ONE") }
      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains(":ONE")
    }
  }
}
