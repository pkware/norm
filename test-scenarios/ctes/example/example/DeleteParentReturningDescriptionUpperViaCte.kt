package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH deleted_parent AS (
 *   DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
 * )
 * SELECT id, name, description_upper FROM deleted_parent
 * ```
 *
 * @property id (`parent.id`)
 * @property name (`parent.name`)
 * @property description_upper (`UPPER(description)`)
 */
@JvmRecord
public data class DeleteParentReturningDescriptionUpperViaCte(
  public val id: UUID,
  public val name: String,
  public val description_upper: String?,
)
