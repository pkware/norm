package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH desc_source AS (
 *   SELECT id, UPPER(description) AS description_upper FROM parent
 * )
 * INSERT INTO child (parent_id, name)
 * SELECT id, description_upper FROM desc_source
 * RETURNING parent_id AS inserted_parent_id, name AS inserted_name
 * ```
 *
 * @property inserted_parent_id (`child.parent_id`)
 * @property inserted_name (`child.name`)
 */
@JvmRecord
public data class InsertChildFromParentDescriptionUpper(
  public val inserted_parent_id: UUID,
  public val inserted_name: String?,
)
