package example

import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Aggregated statistics per string_type value.
 *
 * Maps to the `type_summary` table.
 */
@JvmRecord
public data class TypeSummary(
  public val string_type: String,
  public val row_count: Long?,
  public val average_value: Int?,
)
