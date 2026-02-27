package example

import kotlin.String
import norm.ColumnAdapter

public class UsPostalCodeAdapter : ColumnAdapter<UsPostalCode, String> {
  override fun decode(databaseValue: String): UsPostalCode = UsPostalCode(databaseValue)

  override fun encode(`value`: UsPostalCode): String = value.value
}
