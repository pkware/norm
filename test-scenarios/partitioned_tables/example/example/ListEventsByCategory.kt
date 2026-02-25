package example

import java.time.OffsetDateTime
import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT id, created_at, category FROM event WHERE category = ? ORDER BY created_at DESC
 * ```
 *
 * @property id Unique identifier for the event. (`event.id`)
 * @property created_at When the event occurred. Used as partition key. (`event.created_at`)
 * @property category Event category. (`event.category`)
 */
@JvmRecord
public data class ListEventsByCategory(
  public val id: UUID,
  public val created_at: OffsetDateTime,
  public val category: String,
)
