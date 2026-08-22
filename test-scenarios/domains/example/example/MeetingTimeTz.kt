package example

import java.time.OffsetTime
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class MeetingTimeTz(
  public val `value`: OffsetTime,
)
