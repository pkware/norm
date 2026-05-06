package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT d.id, d.name AS dept_name, e.name AS employee_name, e.nickname
 * FROM department d
 * LEFT JOIN employee e ON e.department_id = d.id
 * ```
 *
 * @property id (`department.id`)
 * @property dept_name (`department.name`)
 * @property employee_name (`employee.name`)
 * @property nickname (`employee.nickname`)
 */
@JvmRecord
public data class DepartmentEmployees(
  public val id: Int,
  public val dept_name: String,
  public val employee_name: String?,
  public val nickname: String?,
)
