package example.testqueries

import kotlin.Int
import kotlin.Long
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT author_id, count(*) AS entries FROM audit_log GROUP BY author_id ORDER BY author_id
 * ```
 *
 * @property author_id (`audit_log.author_id`)
 * @property entries (`count(*)`)
 */
@JvmRecord
public data class CountAuditLogByAuthor(
  public val author_id: Int,
  public val entries: Long,
)
