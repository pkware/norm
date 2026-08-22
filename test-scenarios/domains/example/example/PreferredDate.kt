package example

import java.time.LocalDate
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class PreferredDate(
  public val `value`: LocalDate,
)
