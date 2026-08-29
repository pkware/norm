package example

import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH parent_upper4 AS (
 *   SELECT id, UPPER(name) AS name_upper FROM parent
 * ),
 * child_upper4 AS (
 *   SELECT parent_id, UPPER(name) AS name_upper FROM child
 * )
 * SELECT parent_upper4.name_upper AS parent_name_upper, child_upper4.name_upper AS child_name_upper
 * FROM parent_upper4 JOIN child_upper4 ON parent_upper4.id = child_upper4.parent_id
 * ```
 *
 * @property parent_name_upper (`UPPER(name)`)
 * @property child_name_upper (`UPPER(name)`)
 */
@JvmRecord
public data class SelectCtesJoinedOnPredicate(
  public val parent_name_upper: String,
  public val child_name_upper: String,
)
