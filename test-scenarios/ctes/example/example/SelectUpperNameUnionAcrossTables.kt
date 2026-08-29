package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH parent_names AS (
 *   SELECT id, UPPER(name) AS name_upper FROM parent
 * )
 * SELECT id, name_upper FROM parent_names
 * UNION
 * SELECT id, UPPER(name) FROM child
 * ```
 */
@JvmRecord
public data class SelectUpperNameUnionAcrossTables(
  public val id: UUID?,
  public val name_upper: String?,
)
