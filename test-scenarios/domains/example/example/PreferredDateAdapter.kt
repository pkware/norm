package example

import java.time.LocalDate
import norm.ColumnAdapter

public class PreferredDateAdapter : ColumnAdapter<PreferredDate, LocalDate> {
  override fun decode(databaseValue: LocalDate): PreferredDate = PreferredDate(databaseValue)

  override fun encode(`value`: PreferredDate): LocalDate = value.value
}
