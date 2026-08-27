package example

import java.util.UUID
import kotlin.Any
import kotlin.Boolean
import kotlin.Long
import kotlin.String
import norm.Many
import norm.Query
import norm.Transactable
import norm.inputValue

public interface Queries : Transactable {
  /**
   * ```sql
   * SELECT DISTINCT claimed_by_workflow_id, claimed_by_run_id
   * FROM prefix_enumeration_backlog
   * WHERE deployment_id = ?
   *   AND claimed
   *   AND completed_at IS NULL
   *   AND claimed_by_workflow_id IS NOT NULL
   *   AND claimed_by_run_id IS NOT NULL
   * LIMIT ?
   * ```
   */
  public fun <T : Any> findClaimingRuns(
    deploymentId: UUID,
    runLimit: Long,
    mapper: (claimed_by_workflow_id: String, claimed_by_run_id: String) -> T,
  ): Many<T>

  /**
   * ```sql
   * SELECT DISTINCT claimed_by_workflow_id, claimed_by_run_id
   * FROM prefix_enumeration_backlog
   * WHERE deployment_id = ?
   *   AND claimed
   *   AND completed_at IS NULL
   *   AND claimed_by_workflow_id IS NOT NULL
   *   AND claimed_by_run_id IS NOT NULL
   * LIMIT ?
   * ```
   */
  public fun findClaimingRuns(deploymentId: UUID, runLimit: Long): Many<FindClaimingRuns> = findClaimingRuns(deploymentId, runLimit, ::FindClaimingRuns)

  /**
   * Returns headcount per department name; name is `null` on the ROLLUP subtotal row.
   *
   * ```sql
   * SELECT name, COUNT(*) AS headcount
   * FROM department
   * GROUP BY ROLLUP (name)
   * ```
   */
  public fun <T : Any> departmentHeadcountByRollup(mapper: (name: String?, headcount: Long) -> T): Many<T>

  /**
   * Returns headcount per department name; name is `null` on the ROLLUP subtotal row.
   *
   * ```sql
   * SELECT name, COUNT(*) AS headcount
   * FROM department
   * GROUP BY ROLLUP (name)
   * ```
   */
  public fun departmentHeadcountByRollup(): Many<DepartmentHeadcountByRollup> = departmentHeadcountByRollup(::DepartmentHeadcountByRollup)

  public fun <T : Any> departmentHeadcountByRollupDynamically(mapper: (name: String?, headcount: Long) -> T): Query<T>

  public fun departmentHeadcountByRollupDynamically(): Query<DepartmentHeadcountByRollup> = departmentHeadcountByRollupDynamically(::DepartmentHeadcountByRollup)

  /**
   * Returns lowercased department names; name_lower is `null` on the ROLLUP subtotal row.
   *
   * ```sql
   * SELECT lower(name) AS name_lower
   * FROM department
   * GROUP BY ROLLUP (lower(name))
   * ```
   */
  public fun <T> departmentNameLowerByRollup(mapper: (name_lower: String?) -> T): Many<T>

  /**
   * Returns lowercased department names; name_lower is `null` on the ROLLUP subtotal row.
   *
   * ```sql
   * SELECT lower(name) AS name_lower
   * FROM department
   * GROUP BY ROLLUP (lower(name))
   * ```
   */
  public fun departmentNameLowerByRollup(): Many<String?> = departmentNameLowerByRollup(::inputValue)

  public fun <T> departmentNameLowerByRollupDynamically(mapper: (name_lower: String?) -> T): Query<T>

  public fun departmentNameLowerByRollupDynamically(): Query<String?> = departmentNameLowerByRollupDynamically(::inputValue)

  /**
   * Lists employees by department; full_name is `null` when a department has no employees.
   *
   * ```sql
   * SELECT department.id, employee.full_name
   * FROM department
   * LEFT JOIN employee ON employee.department_id = department.id
   * ```
   */
  public fun <T : Any> employeesByDepartment(mapper: (id: Long, full_name: String?) -> T): Many<T>

  /**
   * Lists employees by department; full_name is `null` when a department has no employees.
   *
   * ```sql
   * SELECT department.id, employee.full_name
   * FROM department
   * LEFT JOIN employee ON employee.department_id = department.id
   * ```
   */
  public fun employeesByDepartment(): Many<EmployeesByDepartment> = employeesByDepartment(::EmployeesByDepartment)

  public fun <T : Any> employeesByDepartmentDynamically(mapper: (id: Long, full_name: String?) -> T): Query<T>

  public fun employeesByDepartmentDynamically(): Query<EmployeesByDepartment> = employeesByDepartmentDynamically(::EmployeesByDepartment)

  /**
   * Lists employees whose full_name is not `null`; the type stays nullable, since Norm doesn't narrow across outer joins.
   *
   * ```sql
   * SELECT employee.full_name
   * FROM department
   * LEFT JOIN employee ON employee.department_id = department.id
   * WHERE employee.full_name IS NOT NULL
   * ```
   */
  public fun <T> employeesByDepartmentFiltered(mapper: (full_name: String?) -> T): Many<T>

  /**
   * Lists employees whose full_name is not `null`; the type stays nullable, since Norm doesn't narrow across outer joins.
   *
   * ```sql
   * SELECT employee.full_name
   * FROM department
   * LEFT JOIN employee ON employee.department_id = department.id
   * WHERE employee.full_name IS NOT NULL
   * ```
   */
  public fun employeesByDepartmentFiltered(): Many<String?> = employeesByDepartmentFiltered(::inputValue)

  public fun <T> employeesByDepartmentFilteredDynamically(mapper: (full_name: String?) -> T): Query<T>

  public fun employeesByDepartmentFilteredDynamically(): Query<String?> = employeesByDepartmentFilteredDynamically(::inputValue)

  /**
   * Returns non-`null` widget codes; the WHERE clause proves code non-null even though the column allows `null`.
   *
   * ```sql
   * SELECT code
   * FROM widget
   * WHERE lower(code) = ?
   * ```
   */
  public fun <T : Any> widgetByLowerCode(code: String, mapper: (code: String) -> T): Many<T>

  /**
   * Returns non-`null` widget codes; the WHERE clause proves code non-null even though the column allows `null`.
   *
   * ```sql
   * SELECT code
   * FROM widget
   * WHERE lower(code) = ?
   * ```
   */
  public fun widgetByLowerCode(code: String): Many<String> = widgetByLowerCode(code, ::inputValue)

  /**
   * Clears an account's note; the returned note is always `null`, reflecting the value just set.
   *
   * ```sql
   * UPDATE account
   * SET note = NULL
   * WHERE note IS NOT NULL
   * RETURNING note
   * ```
   */
  public fun <T> clearAccountNote(mapper: (note: String?) -> T): Many<T>

  /**
   * Clears an account's note; the returned note is always `null`, reflecting the value just set.
   *
   * ```sql
   * UPDATE account
   * SET note = NULL
   * WHERE note IS NOT NULL
   * RETURNING note
   * ```
   */
  public fun clearAccountNote(): Many<String?> = clearAccountNote(::inputValue)

  public fun <T> clearAccountNoteDynamically(mapper: (note: String?) -> T): Query<T>

  public fun clearAccountNoteDynamically(): Query<String?> = clearAccountNoteDynamically(::inputValue)

  /**
   * Reports whether a department's name matches any employee's full name, read through a derived
   * table rather than directly from employee; non-`null` because full_name is NOT NULL.
   *
   * ```sql
   * SELECT department.name = ANY (SELECT names.full_name FROM (SELECT full_name FROM employee) names) AS name_matches
   * FROM department
   * ```
   */
  public fun <T : Any> departmentNameMatchesAnyEmployeeViaDerivedTable(mapper: (name_matches: Boolean) -> T): Many<T>

  /**
   * Reports whether a department's name matches any employee's full name, read through a derived
   * table rather than directly from employee; non-`null` because full_name is NOT NULL.
   *
   * ```sql
   * SELECT department.name = ANY (SELECT names.full_name FROM (SELECT full_name FROM employee) names) AS name_matches
   * FROM department
   * ```
   */
  public fun departmentNameMatchesAnyEmployeeViaDerivedTable(): Many<Boolean> = departmentNameMatchesAnyEmployeeViaDerivedTable(::inputValue)

  public fun <T : Any> departmentNameMatchesAnyEmployeeViaDerivedTableDynamically(mapper: (name_matches: Boolean) -> T): Query<T>

  public fun departmentNameMatchesAnyEmployeeViaDerivedTableDynamically(): Query<Boolean> = departmentNameMatchesAnyEmployeeViaDerivedTableDynamically(::inputValue)

  /**
   * Reports whether a department's name matches any employee's full name, read through an
   * enclosing CTE rather than directly from employee; non-`null` because full_name is NOT NULL.
   *
   * ```sql
   * WITH employee_names AS (SELECT full_name FROM employee)
   * SELECT department.name = ANY (SELECT full_name FROM employee_names) AS name_matches
   * FROM department
   * ```
   */
  public fun <T : Any> departmentNameMatchesAnyEmployeeViaCte(mapper: (name_matches: Boolean) -> T): Many<T>

  /**
   * Reports whether a department's name matches any employee's full name, read through an
   * enclosing CTE rather than directly from employee; non-`null` because full_name is NOT NULL.
   *
   * ```sql
   * WITH employee_names AS (SELECT full_name FROM employee)
   * SELECT department.name = ANY (SELECT full_name FROM employee_names) AS name_matches
   * FROM department
   * ```
   */
  public fun departmentNameMatchesAnyEmployeeViaCte(): Many<Boolean> = departmentNameMatchesAnyEmployeeViaCte(::inputValue)

  public fun <T : Any> departmentNameMatchesAnyEmployeeViaCteDynamically(mapper: (name_matches: Boolean) -> T): Query<T>

  public fun departmentNameMatchesAnyEmployeeViaCteDynamically(): Query<Boolean> = departmentNameMatchesAnyEmployeeViaCteDynamically(::inputValue)
}
