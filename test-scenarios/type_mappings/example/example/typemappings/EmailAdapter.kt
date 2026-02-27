package example.typemappings

import kotlin.String
import norm.ColumnAdapter

public class EmailAdapter : ColumnAdapter<Email, String> {
  override fun decode(databaseValue: String): Email = Email(databaseValue)

  override fun encode(`value`: Email): String = value.value
}
