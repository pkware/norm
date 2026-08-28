package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH parent_label3 AS (
 *   SELECT id, UPPER(name) AS name_upper FROM parent
 * ),
 * parent_label3_relay AS (
 *   SELECT id, name_upper FROM parent_label3
 * )
 * SELECT id, name_upper FROM parent_label3_relay
 * ```
 *
 * @property id (`parent.id`)
 * @property name_upper (`UPPER(name)`)
 */
@JvmRecord
public data class SelectChainedCteProvenance(
  public val id: UUID,
  public val name_upper: String,
)
