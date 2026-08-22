package example

import java.time.LocalTime
import norm.ColumnAdapter

public class OpeningTimeAdapter : ColumnAdapter<OpeningTime, LocalTime> {
  override fun decode(databaseValue: LocalTime): OpeningTime = OpeningTime(databaseValue)

  override fun encode(`value`: OpeningTime): LocalTime = value.value
}
