package example.typemappings

import java.time.LocalDate
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class EventDate(
  public val `value`: LocalDate,
)
