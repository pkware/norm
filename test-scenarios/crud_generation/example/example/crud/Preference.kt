package example.crud

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `preference` table.
 */
@JvmRecord
public data class Preference(
  public val id: Int,
  public val theme: String,
  public val note: String?,
)
