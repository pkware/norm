package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Active employees only.
 *
 * Maps to the `active_employee` table.
 */
@JvmRecord
public data class ActiveEmployee(
  public val id: Int?,
  public val name: String?,
  public val department: String?,
  public val salary: Int?,
)
