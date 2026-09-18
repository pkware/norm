package example

import kotlin.String
import norm.ColumnAdapter

public class WorkEmailAdapter : ColumnAdapter<WorkEmail, String> {
  override fun decode(databaseValue: String): WorkEmail = WorkEmail(databaseValue)

  override fun encode(`value`: WorkEmail): String = value.value
}
