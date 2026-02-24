package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `publisher` table.
 */
@JvmRecord
public data class Publisher(
  public val id: Int,
  public val company_name: String,
)
