package example

import java.time.LocalDateTime
import norm.ColumnAdapter

public class CreatedAtLocalAdapter : ColumnAdapter<CreatedAtLocal, LocalDateTime> {
  override fun decode(databaseValue: LocalDateTime): CreatedAtLocal = CreatedAtLocal(databaseValue)

  override fun encode(`value`: CreatedAtLocal): LocalDateTime = value.value
}
