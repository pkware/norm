package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `parent` table.
 */
@JvmRecord
public data class Parent(
  public val id: UUID,
  public val name: String,
  public val description: String?,
)
