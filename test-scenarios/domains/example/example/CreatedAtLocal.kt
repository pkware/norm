package example

import java.time.LocalDateTime
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class CreatedAtLocal(
  public val `value`: LocalDateTime,
)
