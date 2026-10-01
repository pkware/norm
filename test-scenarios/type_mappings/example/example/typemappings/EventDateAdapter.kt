package example.typemappings

import java.time.LocalDate
import norm.ColumnAdapter

public class EventDateAdapter : ColumnAdapter<EventDate, LocalDate> {
  override fun decode(databaseValue: LocalDate): EventDate = EventDate(databaseValue)

  override fun encode(`value`: EventDate): LocalDate = value.value
}
