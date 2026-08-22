package example

import java.time.OffsetTime
import norm.ColumnAdapter

public class MeetingTimeTzAdapter : ColumnAdapter<MeetingTimeTz, OffsetTime> {
  override fun decode(databaseValue: OffsetTime): MeetingTimeTz = MeetingTimeTz(databaseValue)

  override fun encode(`value`: MeetingTimeTz): OffsetTime = value.value
}
