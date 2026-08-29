package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH name_upper_first AS (
 *   SELECT UPPER(name) AS name_upper, id FROM parent
 * )
 * SELECT * FROM name_upper_first
 * ```
 *
 * @property name_upper (`UPPER(name)`)
 * @property id (`parent.id`)
 */
@JvmRecord
public data class SelectComputedColumnFirstViaOuterStar(
  public val name_upper: String,
  public val id: UUID,
)
