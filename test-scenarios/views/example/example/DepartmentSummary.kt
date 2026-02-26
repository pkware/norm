package example

import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Summary statistics per department.
 *
 * Maps to the `department_summary` table.
 */
@JvmRecord
public data class DepartmentSummary(
  public val department: String?,
  public val employee_count: Long?,
  public val average_salary: Int?,
)
