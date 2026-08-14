package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH RECURSIVE new_parent AS (
 *   INSERT INTO parent (name) SELECT label FROM parent_name RETURNING id, name
 * ),
 * parent_name AS (
 *   SELECT 'seeded'::TEXT AS label
 * )
 * SELECT id, name FROM new_parent
 * ```
 *
 * @property id (`parent.label`)
 * @property name (`parent.name`)
 */
@JvmRecord
public data class CreateParentFromLaterCte(
  public val id: UUID,
  public val name: String,
)
