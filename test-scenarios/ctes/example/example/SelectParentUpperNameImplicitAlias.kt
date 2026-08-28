package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH upper_name AS (
 *   SELECT id, UPPER(name) y FROM parent
 * )
 * SELECT id, y FROM upper_name
 * ```
 *
 * @property id (`parent.id`)
 */
@JvmRecord
public data class SelectParentUpperNameImplicitAlias(
  public val id: UUID,
  public val y: String,
)
