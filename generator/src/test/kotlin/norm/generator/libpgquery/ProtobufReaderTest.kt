package norm.generator.libpgquery

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class ProtobufReaderTest {

  @Test
  fun `reads the field number and wire type of a tag`() {
    val tag = ProtobufReader(byteArrayOf(0x2a)).readTag()
    assertThat(tag.fieldNumber).isEqualTo(5)
    assertThat(tag.wireType).isEqualTo(2)
  }

  @Test
  fun `skips values of every supported wire type`() {
    val bytes = byteArrayOf(
      0x08, 0x96.toByte(), 0x01,
      0x11, 1, 2, 3, 4, 5, 6, 7, 8,
      0x1a, 0x02, 9, 9,
      0x25, 1, 2, 3, 4,
      0x38, 0x07,
    )
    val reader = ProtobufReader(bytes)
    repeat(4) { reader.skipField(reader.readTag()) }
    val tag = reader.readTag()
    assertThat(tag.fieldNumber).isEqualTo(7)
    assertThat(reader.readVarint()).isEqualTo(7L)
    assertThat(reader.hasRemaining()).isFalse()
  }

  @Test
  fun `rejects a wire type that libpg_query does not emit`() {
    val reader = ProtobufReader(byteArrayOf(0x0b))
    val tag = reader.readTag()
    assertFailure { reader.skipField(tag) }
      .isInstanceOf(IllegalStateException::class)
      .hasMessage("Unsupported protobuf wire type 3")
  }
}
