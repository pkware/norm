package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT id, "My Col" FROM tq WHERE id = ?
 * ```
 *
 * @property id (`tq.id`)
 * @property `My Col` Column name containing a space. (`tq.My Col`)
 */
@JvmRecord
public data class GetQuotedColumnWithSpace(
  public val id: Int,
  public val `My Col`: String?,
)
