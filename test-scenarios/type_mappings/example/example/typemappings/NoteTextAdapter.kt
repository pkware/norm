package example.typemappings

import kotlin.String
import norm.ColumnAdapter

public class NoteTextAdapter : ColumnAdapter<NoteText, String> {
  override fun decode(databaseValue: String): NoteText = NoteText(databaseValue)

  override fun encode(`value`: NoteText): String = value.value
}
