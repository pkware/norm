package example.typemappings

import com.example.CalendarDate
import kotlin.Array
import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * Maps to the `schedules` table.
 */
@JvmRecord
public data class Schedules(
  public val id: Int,
  public val event_dates: Array<EventDate?>?,
  public val scores: Array<PositiveInteger?>?,
  public val notes: Array<NoteText?>?,
  public val holidays: Array<CalendarDate?>?,
)
