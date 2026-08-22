package example

import java.util.UUID
import kotlin.Any
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
   * Rule: ROLLUP null-extends a grouping key that is NOT NULL in the schema — name must be
   * reported nullable even though department.name carries a NOT NULL constraint.
   *
   * ```sql
   * SELECT name, COUNT(*) AS headcount
   * FROM department
   * GROUP BY ROLLUP (name)
   * ```
   */
  public fun <T : Any> departmentHeadcountByRollup(mapper: (name: String?, headcount: Long) -> T): Many<T>

  /**
   * Rule: ROLLUP null-extends a grouping key that is NOT NULL in the schema — name must be
   * reported nullable even though department.name carries a NOT NULL constraint.
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
   * Rule: an expression grouping key (#236) is null-extended too — ROLLUP(lower(name)) nulls
   * out the re-evaluated lower(name) in the target list, not just a bare grouping Var.
   *
   * ```sql
   * SELECT lower(name) AS name_lower
   * FROM department
   * GROUP BY ROLLUP (lower(name))
   * ```
   */
  public fun <T> departmentNameLowerByRollup(mapper: (name_lower: String?) -> T): Many<T>

  /**
   * Rule: an expression grouping key (#236) is null-extended too — ROLLUP(lower(name)) nulls
   * out the re-evaluated lower(name) in the target list, not just a bare grouping Var.
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
   * Rule: a NOT NULL column from the optional side of a LEFT JOIN is nullable — full_name can
   * be null-extended when a department has no matching employee.
   *
   * ```sql
   * SELECT department.id, employee.full_name
   * FROM department
   * LEFT JOIN employee ON employee.department_id = department.id
   * ```
   */
  public fun <T : Any> employeesByDepartment(mapper: (id: Long, full_name: String?) -> T): Many<T>

  /**
   * Rule: a NOT NULL column from the optional side of a LEFT JOIN is nullable — full_name can
   * be null-extended when a department has no matching employee.
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
   * Rule: the outer-join nullability check runs first and Norm does not narrow it — full_name
   * is reported nullable even though the WHERE clause means no null-extended row can actually
   * survive. This pins deliberate analyzer conservatism, not a semantic necessity.
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
   * Rule: the outer-join nullability check runs first and Norm does not narrow it — full_name
   * is reported nullable even though the WHERE clause means no null-extended row can actually
   * survive. This pins deliberate analyzer conservatism, not a semantic necessity.
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
   * Rule: WHERE narrows through a strict function chain — lower(code) = :code proves code
   * non-null for any surviving row, even though widget.code carries no NOT NULL constraint.
   * (Selecting only `code`, rather than every widget column, avoids the single-table
   * "returns every column" projection that would reuse the table's schema-level type instead
   * of this query's narrowed one — see SqlStatement.isSingleTableStarProjection.)
   *
   * ```sql
   * SELECT code
   * FROM widget
   * WHERE lower(code) = ?
   * ```
   */
  public fun <T : Any> widgetByLowerCode(code: String, mapper: (code: String) -> T): Many<T>

  /**
   * Rule: WHERE narrows through a strict function chain — lower(code) = :code proves code
   * non-null for any surviving row, even though widget.code carries no NOT NULL constraint.
   * (Selecting only `code`, rather than every widget column, avoids the single-table
   * "returns every column" projection that would reuse the table's schema-level type instead
   * of this query's narrowed one — see SqlStatement.isSingleTableStarProjection.)
   *
   * ```sql
   * SELECT code
   * FROM widget
   * WHERE lower(code) = ?
   * ```
   */
  public fun widgetByLowerCode(code: String): Many<String> = widgetByLowerCode(code, ::inputValue)

  /**
   * Rule: a DML-to-SELECT conversion drops the SET clause but keeps WHERE — narrowing must
   * stay suppressed because WHERE's proof about `note` doesn't survive the SET that nulls it out.
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
   * Rule: a DML-to-SELECT conversion drops the SET clause but keeps WHERE — narrowing must
   * stay suppressed because WHERE's proof about `note` doesn't survive the SET that nulls it out.
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
}
