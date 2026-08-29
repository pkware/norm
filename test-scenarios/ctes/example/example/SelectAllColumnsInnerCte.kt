package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH all_cols AS (
 *   SELECT * FROM parent
 * )
 * SELECT id, UPPER(name) AS name_upper, description FROM all_cols
 * ```
 *
 * @property id (`parent.id`)
 * @property name_upper (`UPPER(name)`)
 * @property description (`parent.description`)
 */
@JvmRecord
public data class SelectAllColumnsInnerCte(
  public val id: UUID,
  public val name_upper: String,
  public val description: String?,
)
