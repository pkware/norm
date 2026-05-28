package example.crud

import java.time.Instant
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `audit_log` table.
 */
@JvmRecord
public data class AuditLog(
  public val message: String,
  public val logged_at: Instant,
)
