package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH all_parents AS (
 *   TABLE parent
 * )
 * SELECT id, UPPER(name) AS name_upper, description FROM all_parents
 * ```
 *
 * @property id (`parent.id`)
 * @property name_upper (`UPPER(name)`)
 * @property description (`parent.description`)
 */
@JvmRecord
public data class SelectParentViaCteTable(
  public val id: UUID,
  public val name_upper: String,
  public val description: String?,
)
