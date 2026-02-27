package example.typemappings

import kotlin.Int
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class PositiveInteger(
  public val `value`: Int,
)
