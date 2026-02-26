package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT id, name FROM author ORDER BY name
 * ```
 *
 * @property id (`author.id`)
 * @property name (`author.name`)
 */
@JvmRecord
public data class FindAllAuthor(
  public val id: Int,
  public val name: String,
)
