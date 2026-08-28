package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH upper_name AS (
 *   (SELECT id, UPPER(name) AS name_upper FROM parent)
 * )
 * SELECT id, name_upper FROM upper_name
 * ```
 *
 * @property id (`parent.id`)
 * @property name_upper (`UPPER(name)`)
 */
@JvmRecord
public data class SelectParentUpperNameViaParenthesizedCte(
  public val id: UUID,
  public val name_upper: String,
)
