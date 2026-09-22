package example

import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT xmin, xmax, ctid FROM type WHERE string_type = ?
 * ```
 *
 * @property xmin (`type.xmin`)
 * @property xmax (`type.xmax`)
 * @property ctid (`type.ctid`)
 */
@JvmRecord
public data class SelectSystemColumns(
  public val xmin: String,
  public val xmax: String,
  public val ctid: String,
)
