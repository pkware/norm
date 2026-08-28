package example

import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH parent_upper AS (
 *   SELECT id, UPPER(name) AS name_upper FROM parent
 * ),
 * child_upper AS (
 *   SELECT id, UPPER(name) AS name_upper FROM child
 * )
 * SELECT parent_upper.name_upper AS parent_name_upper, child_upper.name_upper AS child_name_upper
 * FROM parent_upper, child_upper
 * ```
 *
 * @property parent_name_upper (`UPPER(name)`)
 * @property child_name_upper (`UPPER(name)`)
 */
@JvmRecord
public data class SelectUpperNamesFromTwoCtes(
  public val parent_name_upper: String,
  public val child_name_upper: String,
)
