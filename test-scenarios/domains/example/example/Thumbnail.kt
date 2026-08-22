package example

import kotlin.ByteArray
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class Thumbnail(
  public val `value`: ByteArray,
)
