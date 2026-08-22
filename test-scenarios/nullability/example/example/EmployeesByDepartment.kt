package example

import kotlin.Long
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT department.id, employee.full_name
 * FROM department
 * LEFT JOIN employee ON employee.department_id = department.id
 * ```
 *
 * @property id (`department.id`)
 * @property full_name (`employee.full_name`)
 */
@JvmRecord
public data class EmployeesByDepartment(
  public val id: Long,
  public val full_name: String?,
)
