package norm.generator

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlIdentifiersTest {

  @Nested
  inner class TruncateIdentifierBoundarySweep {

    @Test
    fun `a 62-byte ASCII identifier is returned unchanged`() {
      val name = "a".repeat(62)
      assertThat(truncateIdentifier(name)).isEqualTo(name)
    }

    @Test
    fun `an exactly 63-byte ASCII identifier is returned unchanged`() {
      val name = "a".repeat(63)
      assertThat(truncateIdentifier(name)).isEqualTo(name)
    }

    @Test
    fun `a 64-byte ASCII identifier is truncated to 63 bytes`() {
      val name = "a".repeat(64)

      val result = truncateIdentifier(name)

      assertThat(result).isEqualTo("a".repeat(63))
      assertThat(result.toByteArray(Charsets.UTF_8)).hasSize(63)
    }

    @Test
    fun `32 two-byte characters -- 64 bytes -- truncate to 31 characters, 62 bytes, the mid-character crossing`() {
      // Each "é" is 2 UTF-8 bytes -- 32 of them is 64 bytes, one byte over budget. The 32nd "é"
      // cannot be split in half, so it is dropped whole, landing on 62 bytes rather than the full
      // 63-byte budget (PostgreSQL's `NAMEDATALEN - 1` limit).
      val name = "é".repeat(32)

      val result = truncateIdentifier(name)

      assertThat(result).isEqualTo("é".repeat(31))
      assertThat(result.toByteArray(Charsets.UTF_8)).hasSize(62)
    }

    @Test
    fun `a 4-byte supplementary-plane character straddling byte 63 is dropped whole, never as a lone surrogate`() {
      // U+1F600 GRINNING FACE, written as a UTF-16 surrogate pair -- 4 bytes in UTF-8. 60 ASCII
      // bytes (0-59) leave only 3 of the emoji's 4 bytes inside the 63-byte budget (indices
      // 60-62), so it cannot fit at all and is dropped in its entirety, rather than emitting a
      // lone, invalid surrogate for half of it.
      val emoji = "😀"
      val name = "a".repeat(60) + emoji

      val result = truncateIdentifier(name)

      assertThat(result).isEqualTo("a".repeat(60))
      assertThat(result.none { it.isSurrogate() }).isTrue()
    }

    @Test
    fun `an empty identifier is returned unchanged`() {
      assertThat(truncateIdentifier("")).isEqualTo("")
    }
  }
}
