package example.crud

import java.time.Instant
import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * INSERT INTO author (name, bio) VALUES (?, ?) RETURNING id, created_at
 * ```
 *
 * @property id (`author.id`)
 * @property created_at (`author.created_at`)
 */
@JvmRecord
public data class InsertAuthor(
  public val id: Int,
  public val created_at: Instant,
)
