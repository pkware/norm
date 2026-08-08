package example.typemappings

import kotlin.String
import norm.ColumnAdapter

public class JsonDocumentAdapter : ColumnAdapter<JsonDocument, String> {
  override fun decode(databaseValue: String): JsonDocument = JsonDocument(databaseValue)

  override fun encode(`value`: JsonDocument): String = value.value
}
