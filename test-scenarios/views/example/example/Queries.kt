package example

import java.sql.SQLException
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.jvm.Throws
import norm.Many
import norm.Query

public interface Queries {
  /**
   * Query against a view.
   */
  public fun <T : Any> listActiveEmployees(mapper: (
    id: Int?,
    name: String?,
    department: String?,
    salary: Int?,
  ) -> T): Many<T>

  /**
   * Query against a view.
   */
  public fun listActiveEmployees(): Many<ActiveEmployee> = listActiveEmployees(::ActiveEmployee)

  public fun <T : Any> listActiveEmployeesDynamically(mapper: (
    id: Int?,
    name: String?,
    department: String?,
    salary: Int?,
  ) -> T): Query<T>

  public fun listActiveEmployeesDynamically(): Query<ActiveEmployee> = listActiveEmployeesDynamically(::ActiveEmployee)

  /**
   * Query against a materialized view.
   */
  @Throws(SQLException::class)
  public fun <T : Any> getDepartmentSummary(department: String, mapper: (
    department: String?,
    employee_count: Long?,
    average_salary: Int?,
  ) -> T): T

  /**
   * Query against a materialized view.
   */
  @Throws(SQLException::class)
  public fun getDepartmentSummary(department: String): DepartmentSummary = getDepartmentSummary(department, ::DepartmentSummary)

  /**
   * Query against the base table still works alongside views.
   */
  @Throws(SQLException::class)
  public fun <T : Any> getEmployeeById(id: Int, mapper: (
    id: Int,
    name: String,
    department: String,
    salary: Int,
    active: Boolean,
  ) -> T): T

  /**
   * Query against the base table still works alongside views.
   */
  @Throws(SQLException::class)
  public fun getEmployeeById(id: Int): Employee = getEmployeeById(id, ::Employee)
}
