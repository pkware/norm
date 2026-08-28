package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH constant_rows AS (
 *   VALUES (1, 'a'), (2, 'b')
 * )
 * SELECT column1, column2 FROM constant_rows
 * ```
 */
@JvmRecord
public data class SelectViaCteValues(
  public val column1: Int?,
  public val column2: String?,
)
