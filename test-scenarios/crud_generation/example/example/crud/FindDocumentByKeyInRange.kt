package example.crud

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT id, title FROM document WHERE metadata ?? 'key' AND id >= ? AND id < ?
 * ```
 *
 * @property id (`document.id`)
 * @property title (`document.title`)
 */
@JvmRecord
public data class FindDocumentByKeyInRange(
  public val id: Int,
  public val title: String,
)
