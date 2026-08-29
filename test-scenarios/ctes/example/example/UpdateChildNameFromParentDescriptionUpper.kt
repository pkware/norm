package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH desc_source AS (
 *   SELECT id, UPPER(description) AS description_upper FROM parent
 * )
 * UPDATE child SET name = desc_source.description_upper
 * FROM desc_source
 * WHERE child.parent_id = desc_source.id
 * RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS updated_description
 * ```
 *
 * @property source_parent_id (`parent.id`)
 * @property updated_description (`UPPER(description)`)
 */
@JvmRecord
public data class UpdateChildNameFromParentDescriptionUpper(
  public val source_parent_id: UUID,
  public val updated_description: String?,
)
