package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH renamed_parent(parent_label, parent_id) AS (
 *   SELECT UPPER(name), id FROM parent
 * )
 * SELECT parent_label, parent_id FROM renamed_parent
 * ```
 *
 * @property parent_id (`parent.id`)
 */
@JvmRecord
public data class SelectParentViaCteWithExplicitColumnList(
  public val parent_label: String,
  public val parent_id: UUID,
)
