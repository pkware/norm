package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH shadow_cte AS (
 *   SELECT UPPER(name) AS ux FROM parent
 * ),
 * outer_consumer AS (
 *   WITH shadow_cte AS (
 *     SELECT LOWER(name) AS ux FROM parent
 *   )
 *   SELECT ux, 1 AS n FROM shadow_cte
 * )
 * SELECT ux, n FROM outer_consumer
 * ```
 *
 * @property n (`1`)
 */
@JvmRecord
public data class SelectNestedCteShadowingOuterName(
  public val ux: String,
  public val n: Int,
)
