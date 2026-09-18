package example.crud

import kotlin.Int
import kotlin.collections.List
import kotlin.jvm.JvmInline

/**
 * @property value The underlying database value.
 */
@JvmInline
public value class IntSet(
  public val `value`: List<Int?>,
)
