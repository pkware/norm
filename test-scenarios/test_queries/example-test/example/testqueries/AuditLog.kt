package example.testqueries

import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * Maps to the `audit_log` table.
 */
@JvmRecord
public data class AuditLog(
  public val id: Int,
  public val author_id: Int,
  public val action: AuditAction,
)
