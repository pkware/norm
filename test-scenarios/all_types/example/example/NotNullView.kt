package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Non-nullable columns from the type table.
 *
 * Maps to the `not_null_view` table.
 */
@JvmRecord
public data class NotNullView(
  public val serial_type: Int,
  public val string_type: String,
  public val int4_type: Int,
)
