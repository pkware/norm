package example

import java.util.UUID
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class ExternalReference(
  public val `value`: UUID,
)
