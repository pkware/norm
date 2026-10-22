package norm.generator.libpgquery

import norm.generator.JsonValue

private const val STATEMENT_LENGTH_FIELD = "stmt_len"
private const val STATEMENT_LOCATION_FIELD = "stmt_location"

/**
 * Rewrites the UTF-8 byte offsets in a libpg_query parse tree to `String` indexes.
 *
 * An integer-valued field is a byte offset when its name is in [locationFields], whichever node holds it.
 *
 * @param locationFields names that have `c_type` `ParseLoc` on any struct in the pinned libpg_query's
 *   `srcdata/struct_defs.json`.
 */
internal class ParseTreeOffsets(private val locationFields: Set<String>) {

  /**
   * Returns a copy of [tree] in which every location field holds a `String` index into [Utf8Input.sql]. `stmt_len`
   * is rewritten so that `stmt_location + stmt_len` still marks the end of the statement. Values that are not
   * integers and negative values stay unchanged.
   */
  fun convert(tree: JsonValue.JsonObject, input: Utf8Input): JsonValue.JsonObject = JsonValue.JsonObject(
    tree.fields.mapValuesTo(linkedMapOf()) { (key, value) ->
      when (key) {
        STATEMENT_LENGTH_FIELD -> tree.convertStatementLength(value, input)
        in locationFields -> value.convertLocation(input)
        else -> value.withStringIndexes(input)
      }
    },
  )

  private fun JsonValue.withStringIndexes(input: Utf8Input): JsonValue = when (this) {
    is JsonValue.JsonObject -> convert(this, input)
    is JsonValue.JsonArray -> JsonValue.JsonArray(items.map { it.withStringIndexes(input) })
    else -> this
  }

  private fun JsonValue.convertLocation(input: Utf8Input): JsonValue {
    val byteOffset = (this as? JsonValue.JsonScalar)?.intValue() ?: return this
    return JsonValue.JsonScalar(input.indexOfByteOffset(byteOffset).toString())
  }

  private fun JsonValue.JsonObject.convertStatementLength(length: JsonValue, input: Utf8Input): JsonValue {
    val byteLength = (length as? JsonValue.JsonScalar)?.intValue() ?: return length
    val byteStart = intField(STATEMENT_LOCATION_FIELD) ?: 0
    if (byteStart < 0 || byteLength < 0) return length
    val start = input.indexOfByteOffset(byteStart)
    val end = input.indexOfByteOffset(byteStart + byteLength)
    return JsonValue.JsonScalar((end - start).toString())
  }
}
