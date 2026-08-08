package example.typemappings

import com.example.JsonData
import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * Maps to the `documents` table.
 */
@JvmRecord
public data class Documents(
  public val id: Int,
  public val payload: JsonData,
  public val doc: JsonDocument?,
)
