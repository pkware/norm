package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * UPDATE parent SET description = 'UPDATED' WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
 * ```
 *
 * @property id (`parent.id`)
 * @property name (`parent.name`)
 * @property description_upper (`UPPER(description)`)
 */
@JvmRecord
public data class UpdateParentReturningDescriptionUpper(
  public val id: UUID,
  public val name: String,
  public val description_upper: String?,
)
