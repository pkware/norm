package norm.generator.libpgquery

/**
 * SQL text encoded once as UTF-8 for libpg_query, with the mapping from the UTF-8 byte offsets it reports back to
 * `String` (UTF-16) indexes of [sql].
 *
 * @property sql the original text.
 * @property bytes [sql] encoded as UTF-8, without a terminating NUL.
 * @throws IllegalArgumentException if [sql] contains a NUL character, which would end the C string early.
 */
internal class Utf8Input(val sql: String) {
  init {
    require('\u0000' !in sql) { "SQL text must not contain NUL characters" }
  }

  val bytes: ByteArray = sql.toByteArray(Charsets.UTF_8)

  /** Maps each byte offset to the index of the character it belongs to. `null` when [sql] is ASCII. */
  private val characterIndexByByteOffset: IntArray? = if (bytes.size == sql.length) null else buildIndex()

  /**
   * The `String` index of the character containing [byteOffset]. Negative offsets are returned unchanged and
   * offsets past the end map to `sql.length`.
   */
  fun indexOfByteOffset(byteOffset: Int): Int = when {
    byteOffset < 0 -> byteOffset
    byteOffset >= bytes.size -> sql.length
    characterIndexByByteOffset == null -> byteOffset
    else -> characterIndexByByteOffset[byteOffset]
  }

  /**
   * The `String` index of the code point at [codePointOffset], counted from the start of [sql]. Offsets past the
   * end map to `sql.length`.
   */
  fun indexOfCodePointOffset(codePointOffset: Int): Int =
    if (codePointOffset >= sql.codePointCount(0, sql.length)) sql.length else sql.offsetByCodePoints(0, codePointOffset)

  private fun buildIndex(): IntArray {
    val index = IntArray(bytes.size + 1)
    var characterIndex = 0
    var byteOffset = 0
    while (byteOffset < bytes.size) {
      val lead = bytes[byteOffset].toInt() and 0xff
      val width = when {
        lead < 0x80 -> 1
        lead < 0xe0 -> 2
        lead < 0xf0 -> 3
        else -> 4
      }
      repeat(width) { index[byteOffset + it] = characterIndex }
      characterIndex += if (width == 4) 2 else 1
      byteOffset += width
    }
    index[bytes.size] = characterIndex
    return index
  }
}
