package example

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import kotlin.Any
import kotlin.Long
import kotlin.String
import kotlin.Unit
import norm.ConnectionProvider
import norm.Many
import norm.ManyProcessor
import norm.NormDriver
import norm.Query
import norm.RealTransactable

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : RealTransactable(connectionProvider),
    Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  private fun <T : Any, Return> findClaimingRuns(
    deploymentId: UUID,
    runLimit: Long,
    mapper: (claimed_by_workflow_id: String, claimed_by_run_id: String) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = """
        |SELECT DISTINCT claimed_by_workflow_id, claimed_by_run_id
        |FROM prefix_enumeration_backlog
        |WHERE deployment_id = ?
        |  AND claimed
        |  AND completed_at IS NULL
        |  AND claimed_by_workflow_id IS NOT NULL
        |  AND claimed_by_run_id IS NOT NULL
        |LIMIT ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setObject(1, deploymentId)
      setLong(2, runLimit)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> findClaimingRuns(
    deploymentId: UUID,
    runLimit: Long,
    mapper: (claimed_by_workflow_id: String, claimed_by_run_id: String) -> T,
  ): Many<T> = findClaimingRuns(deploymentId, runLimit, mapper, driver::queryMany)

  private fun <T : Any, Return> departmentHeadcountByRollup(mapper: (name: String?, headcount: Long) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |SELECT name, COUNT(*) AS headcount
        |FROM department
        |GROUP BY ROLLUP (name)
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getLong(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> departmentHeadcountByRollup(mapper: (name: String?, headcount: Long) -> T): Many<T> = departmentHeadcountByRollup(mapper, driver::queryMany)

  override fun <T : Any> departmentHeadcountByRollupDynamically(mapper: (name: String?, headcount: Long) -> T): Query<T> = departmentHeadcountByRollup(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T, Return> departmentNameLowerByRollup(mapper: (name_lower: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |SELECT lower(name) AS name_lower
        |FROM department
        |GROUP BY ROLLUP (lower(name))
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T> departmentNameLowerByRollup(mapper: (name_lower: String?) -> T): Many<T> = departmentNameLowerByRollup(mapper, driver::queryMany)

  override fun <T> departmentNameLowerByRollupDynamically(mapper: (name_lower: String?) -> T): Query<T> = departmentNameLowerByRollup(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> employeesByDepartment(mapper: (id: Long, full_name: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |SELECT department.id, employee.full_name
        |FROM department
        |LEFT JOIN employee ON employee.department_id = department.id
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getLong(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> employeesByDepartment(mapper: (id: Long, full_name: String?) -> T): Many<T> = employeesByDepartment(mapper, driver::queryMany)

  override fun <T : Any> employeesByDepartmentDynamically(mapper: (id: Long, full_name: String?) -> T): Query<T> = employeesByDepartment(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T, Return> employeesByDepartmentFiltered(mapper: (full_name: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |SELECT employee.full_name
        |FROM department
        |LEFT JOIN employee ON employee.department_id = department.id
        |WHERE employee.full_name IS NOT NULL
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T> employeesByDepartmentFiltered(mapper: (full_name: String?) -> T): Many<T> = employeesByDepartmentFiltered(mapper, driver::queryMany)

  override fun <T> employeesByDepartmentFilteredDynamically(mapper: (full_name: String?) -> T): Query<T> = employeesByDepartmentFiltered(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> widgetByLowerCode(
    code: String,
    mapper: (code: String) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = """
        |SELECT code
        |FROM widget
        |WHERE lower(code) = ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setString(1, code)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> widgetByLowerCode(code: String, mapper: (code: String) -> T): Many<T> = widgetByLowerCode(code, mapper, driver::queryMany)

  private fun <T, Return> clearAccountNote(mapper: (note: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |UPDATE account
        |SET note = NULL
        |WHERE note IS NOT NULL
        |RETURNING note
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T> clearAccountNote(mapper: (note: String?) -> T): Many<T> = clearAccountNote(mapper, driver::queryMany)

  override fun <T> clearAccountNoteDynamically(mapper: (note: String?) -> T): Query<T> = clearAccountNote(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }
}
