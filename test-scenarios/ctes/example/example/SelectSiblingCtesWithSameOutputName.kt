package example

import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH parent_same AS (
 *   SELECT UPPER(name) AS same FROM parent
 * ),
 * child_same AS (
 *   SELECT UPPER(name) AS same FROM child
 * )
 * SELECT parent_same.same AS parent_same, child_same.same AS child_same
 * FROM parent_same, child_same
 * ```
 *
 * @property parent_same (`UPPER(name)`)
 * @property child_same (`UPPER(name)`)
 */
@JvmRecord
public data class SelectSiblingCtesWithSameOutputName(
  public val parent_same: String,
  public val child_same: String,
)
