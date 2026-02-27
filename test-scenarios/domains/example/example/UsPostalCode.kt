package example

import kotlin.String
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class UsPostalCode(
  public val `value`: String,
)
