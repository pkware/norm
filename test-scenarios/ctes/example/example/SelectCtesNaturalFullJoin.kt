package example

import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH parent_label AS (
 *   SELECT UPPER(name) AS shared_label FROM parent
 * ),
 * child_label AS (
 *   SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
 * )
 * SELECT shared_label, child_own_name FROM parent_label NATURAL FULL JOIN child_label
 * ```
 *
 * @property child_own_name (`child.name`)
 */
@JvmRecord
public data class SelectCtesNaturalFullJoin(
  public val shared_label: String?,
  public val child_own_name: String?,
)
