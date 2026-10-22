package norm.generator.libpgquery

private const val VARINT_WIRE_TYPE = 0
private const val FIXED_64_WIRE_TYPE = 1
private const val LENGTH_DELIMITED_WIRE_TYPE = 2
private const val FIXED_32_WIRE_TYPE = 5
private const val WIRE_TYPE_MASK = 7
private const val WIRE_TYPE_BITS = 3

/**
 * The key that starts a protobuf field.
 *
 * @property fieldNumber number of the field in its message.
 * @property wireType encoding of the value that follows the tag.
 */
@JvmInline
internal value class ProtobufTag(private val encoded: Int) {
  val fieldNumber: Int get() = encoded ushr WIRE_TYPE_BITS
  val wireType: Int get() = encoded and WIRE_TYPE_MASK
}

/** Reads the protobuf wire format from [bytes], covering the wire types that libpg_query emits. */
internal class ProtobufReader(private val bytes: ByteArray) {
  private var position = 0

  fun hasRemaining(): Boolean = position < bytes.size

  fun readVarint(): Long {
    var result = 0L
    var shift = 0
    while (true) {
      val next = bytes[position++].toInt()
      result = result or ((next and 0x7f).toLong() shl shift)
      if (next and 0x80 == 0) return result
      shift += 7
    }
  }

  fun readLengthDelimited(): ByteArray {
    val length = readVarint().toInt()
    return bytes.copyOfRange(position, position + length).also { position += length }
  }

  /** Reads the tag that starts the next field. A `read` function or [skipField] consumes the value that follows. */
  fun readTag(): ProtobufTag = ProtobufTag(readVarint().toInt())

  /**
   * Skips the value of the field that [tag] started.
   *
   * @throws IllegalStateException if the field's wire type is not a varint, 64-bit, length-delimited or 32-bit value.
   */
  fun skipField(tag: ProtobufTag) {
    when (tag.wireType) {
      VARINT_WIRE_TYPE -> readVarint()
      FIXED_64_WIRE_TYPE -> position += 8
      LENGTH_DELIMITED_WIRE_TYPE -> readLengthDelimited()
      FIXED_32_WIRE_TYPE -> position += 4
      else -> error("Unsupported protobuf wire type ${tag.wireType}")
    }
  }
}
