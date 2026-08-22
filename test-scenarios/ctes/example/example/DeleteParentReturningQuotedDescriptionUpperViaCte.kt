package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH deleted_parent AS (
 *   DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS "descriptionUpper"
 * )
 * SELECT id, name, "descriptionUpper" FROM deleted_parent
 * ```
 *
 * @property id (`parent.id`)
 * @property name (`parent.name`)
 * @property descriptionUpper (`UPPER(description)`)
 */
@JvmRecord
public data class DeleteParentReturningQuotedDescriptionUpperViaCte(
  public val id: UUID,
  public val name: String,
  public val descriptionUpper: String?,
)
