package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH parent_and_child AS (
 *   WITH helper AS (SELECT 1)
 *   SELECT parent.id AS parent_id, parent.name AS parent_name, child.name AS child_name
 *   FROM parent LEFT JOIN child ON child.parent_id = parent.id
 * ),
 * new_parent AS (
 *   INSERT INTO parent (name) VALUES ('placeholder') RETURNING id
 * )
 * SELECT parent_id, parent_name, child_name FROM parent_and_child
 * ```
 *
 * @property parent_id (`parent.parent_id`)
 * @property parent_name (`parent.parent_name`)
 * @property child_name (`child.child_name`)
 */
@JvmRecord
public data class ListParentsWithOptionalChildAlongsideInsert(
  public val parent_id: UUID,
  public val parent_name: String,
  public val child_name: String?,
)
