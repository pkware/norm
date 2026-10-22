package norm.generator

/** Every value nested beneath this one, parents before children and siblings in document order. */
internal fun JsonValue.descendants(): Sequence<JsonValue> = sequence {
  val children = when (this@descendants) {
    is JsonValue.JsonObject -> fields.values
    is JsonValue.JsonArray -> items
    else -> emptyList()
  }
  for (child in children) {
    yield(child)
    yieldAll(child.descendants())
  }
}
