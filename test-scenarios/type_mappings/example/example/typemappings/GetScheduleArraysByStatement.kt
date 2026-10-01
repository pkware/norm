package example.typemappings

import com.example.CalendarDate
import kotlin.Array
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT event_dates, scores, notes, holidays FROM schedules WHERE id = ?
 * ```
 *
 * @property event_dates (`schedules.event_dates`)
 * @property scores (`schedules.scores`)
 * @property notes (`schedules.notes`)
 * @property holidays (`schedules.holidays`)
 */
@JvmRecord
public data class GetScheduleArraysByStatement(
  public val event_dates: Array<EventDate?>?,
  public val scores: Array<PositiveInteger?>?,
  public val notes: Array<NoteText?>?,
  public val holidays: Array<CalendarDate?>?,
)
