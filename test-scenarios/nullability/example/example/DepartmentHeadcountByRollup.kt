package example

import kotlin.Long
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT name, COUNT(*) AS headcount
 * FROM department
 * GROUP BY ROLLUP (name)
 * ```
 *
 * @property name (`department.name`)
 * @property headcount (`COUNT(*)`)
 */
@JvmRecord
public data class DepartmentHeadcountByRollup(
  public val name: String?,
  public val headcount: Long,
)
