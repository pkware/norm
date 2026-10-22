package norm.generator.libpgquery

private const val SCAN_RESULT_TOKENS_FIELD = 2
private const val SCAN_TOKEN_START_FIELD = 1
private const val SCAN_TOKEN_END_FIELD = 2
private const val SCAN_TOKEN_TOKEN_FIELD = 4
private const val SCAN_TOKEN_KEYWORD_KIND_FIELD = 5

/** libpg_query's `KeywordKind` enum, in the order of its protobuf values. */
internal enum class KeywordKind {
  NONE,
  UNRESERVED,
  COLUMN_NAME,
  TYPE_FUNCTION_NAME,
  RESERVED,
}

/**
 * One token from [LibPgQuery.scan].
 *
 * @property start `String` index of the first character.
 * @property end `String` index after the last character.
 * @property token libpg_query's `Token` enum value, which is the Bison token number for keywords and operators.
 * @property keywordKind how PostgreSQL classifies the token as a keyword, [KeywordKind.NONE] for other tokens.
 */
internal data class ScanToken(val start: Int, val end: Int, val token: Int, val keywordKind: KeywordKind)

/**
 * Decodes libpg_query's protobuf `ScanResult` message.
 *
 * @param bytes the encoded message.
 * @param input the text that was scanned, which maps the byte offsets in [bytes] to `String` indexes.
 * @throws IllegalStateException if the message holds an unsupported wire type or an unknown keyword kind.
 */
internal fun decodeScanResult(bytes: ByteArray, input: Utf8Input): List<ScanToken> {
  val tokens = mutableListOf<ScanToken>()
  val reader = ProtobufReader(bytes)
  while (reader.hasRemaining()) {
    val tag = reader.readTag()
    if (tag.fieldNumber == SCAN_RESULT_TOKENS_FIELD) {
      tokens += decodeScanToken(reader.readLengthDelimited(), input)
    } else {
      reader.skipField(tag)
    }
  }
  return tokens
}

private fun decodeScanToken(bytes: ByteArray, input: Utf8Input): ScanToken {
  val reader = ProtobufReader(bytes)
  var startByte = 0
  var endByte = 0
  var token = 0
  var keywordKind = KeywordKind.NONE
  while (reader.hasRemaining()) {
    val tag = reader.readTag()
    when (tag.fieldNumber) {
      SCAN_TOKEN_START_FIELD -> startByte = reader.readVarint().toInt()
      SCAN_TOKEN_END_FIELD -> endByte = reader.readVarint().toInt()
      SCAN_TOKEN_TOKEN_FIELD -> token = reader.readVarint().toInt()
      SCAN_TOKEN_KEYWORD_KIND_FIELD -> keywordKind = decodeKeywordKind(reader.readVarint().toInt())
      else -> reader.skipField(tag)
    }
  }
  return ScanToken(input.indexOfByteOffset(startByte), input.indexOfByteOffset(endByte), token, keywordKind)
}

private fun decodeKeywordKind(number: Int): KeywordKind =
  checkNotNull(KeywordKind.entries.getOrNull(number)) { "Unknown libpg_query KeywordKind $number" }
