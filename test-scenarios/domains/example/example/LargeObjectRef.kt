package example

import java.sql.Blob
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class LargeObjectRef(
  public val `value`: Blob,
)
