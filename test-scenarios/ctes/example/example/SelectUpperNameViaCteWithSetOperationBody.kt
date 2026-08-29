package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH combined_upper AS (
 *   SELECT id, UPPER(name) AS name_upper FROM parent
 *   UNION
 *   SELECT id, UPPER(name) FROM child
 * )
 * SELECT id, name_upper FROM combined_upper
 * ```
 */
@JvmRecord
public data class SelectUpperNameViaCteWithSetOperationBody(
  public val id: UUID,
  public val name_upper: String,
)
