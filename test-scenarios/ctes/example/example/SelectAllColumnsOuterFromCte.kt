package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH all_cols AS (
 *   SELECT id, name, description, UPPER(name) AS name_upper FROM parent
 * )
 * SELECT * FROM all_cols
 * ```
 *
 * @property id (`parent.id`)
 * @property name (`parent.name`)
 * @property description (`parent.description`)
 * @property name_upper (`UPPER(name)`)
 */
@JvmRecord
public data class SelectAllColumnsOuterFromCte(
  public val id: UUID,
  public val name: String,
  public val description: String?,
  public val name_upper: String,
)
