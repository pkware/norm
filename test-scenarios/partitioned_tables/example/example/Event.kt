package example

import java.time.OffsetDateTime
import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Partitioned event log.
 *
 * Maps to the `event` table.
 *
 * @property id Unique identifier for the event.
 * @property created_at When the event occurred. Used as partition key.
 * @property category Event category.
 * @property payload Event payload. Null when the event carries no extra data.
 */
@JvmRecord
public data class Event(
  public val id: UUID,
  public val created_at: OffsetDateTime,
  public val category: String,
  public val payload: String?,
)
