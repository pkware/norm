package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `child` table.
 */
@JvmRecord
public data class Child(
  public val id: UUID,
  public val parent_id: UUID,
  public val name: String,
)
