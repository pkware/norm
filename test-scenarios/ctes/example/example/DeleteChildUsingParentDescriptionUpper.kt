package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH desc_source AS (
 *   SELECT id, UPPER(description) AS description_upper FROM parent
 * )
 * DELETE FROM child USING desc_source
 * WHERE child.parent_id = desc_source.id
 * RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS deleted_description
 * ```
 *
 * @property source_parent_id (`parent.id`)
 * @property deleted_description (`UPPER(description)`)
 */
@JvmRecord
public data class DeleteChildUsingParentDescriptionUpper(
  public val source_parent_id: UUID,
  public val deleted_description: String?,
)
