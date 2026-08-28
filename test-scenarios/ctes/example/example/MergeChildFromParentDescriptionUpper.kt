package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH desc_source AS (
 *   SELECT id, UPPER(description) AS description_upper FROM parent
 * )
 * MERGE INTO child USING desc_source ON child.parent_id = desc_source.id
 * WHEN MATCHED THEN UPDATE SET name = desc_source.description_upper
 * WHEN NOT MATCHED THEN INSERT (parent_id, name) VALUES (desc_source.id, desc_source.description_upper)
 * RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS merged_description
 * ```
 *
 * @property source_parent_id (`parent.id`)
 */
@JvmRecord
public data class MergeChildFromParentDescriptionUpper(
  public val source_parent_id: UUID?,
  public val merged_description: String?,
)
