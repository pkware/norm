package com.example

import java.time.LocalDate
import norm.ColumnAdapter

class CalendarDateAdapter : ColumnAdapter<CalendarDate, LocalDate> {
  override fun decode(databaseValue: LocalDate): CalendarDate =
    CalendarDate(databaseValue)

  override fun encode(value: CalendarDate): LocalDate =
    value.date
}
