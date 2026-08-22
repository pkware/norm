package example

import java.time.Instant
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class OrderPlacedAt(
  public val `value`: Instant,
)
