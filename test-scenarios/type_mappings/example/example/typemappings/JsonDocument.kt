package example.typemappings

import kotlin.String
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class JsonDocument(
  public val `value`: String,
)
