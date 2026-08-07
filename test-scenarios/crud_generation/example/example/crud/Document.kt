package example.crud

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `document` table.
 */
@JvmRecord
public data class Document(
  public val id: Int,
  public val title: String,
  public val metadata: String?,
)
