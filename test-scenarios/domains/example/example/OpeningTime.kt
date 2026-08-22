package example

import java.time.LocalTime
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class OpeningTime(
  public val `value`: LocalTime,
)
