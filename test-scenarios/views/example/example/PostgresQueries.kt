package example

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.jvm.Throws
import norm.ConnectionProvider
import norm.Many
import norm.NormDriver
import norm.Query

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  private fun <T : Any, R> listActiveEmployees(mapper: (
    id: Int?,
    name: String?,
    department: String?,
    salary: Int?,
  ) -> T, block: (String, ResultSet.() -> T) -> R): R {
    val sql = "SELECT * FROM active_employee ORDER BY name"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1).takeUnless { wasNull() },
        getString(2),
        getString(3),
        getInt(4).takeUnless { wasNull() },
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> listActiveEmployees(mapper: (
    id: Int?,
    name: String?,
    department: String?,
    salary: Int?,
  ) -> T): Many<T> = listActiveEmployees(mapper, driver::queryMany)

  override fun <T : Any> listActiveEmployeesDynamically(mapper: (
    id: Int?,
    name: String?,
    department: String?,
    salary: Int?,
  ) -> T): Query<T> = listActiveEmployees(mapper, driver::dynamic)

  @Throws(SQLException::class)
  override fun <T : Any> getDepartmentSummary(department: String, mapper: (
    department: String?,
    employee_count: Long?,
    average_salary: Int?,
  ) -> T): T {
    val sql = "SELECT * FROM department_summary WHERE department = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getLong(2).takeUnless { wasNull() },
        getInt(3).takeUnless { wasNull() },
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, department)
    }
  }

  @Throws(SQLException::class)
  override fun <T : Any> getEmployeeById(id: Int, mapper: (
    id: Int,
    name: String,
    department: String,
    salary: Int,
    active: Boolean,
  ) -> T): T {
    val sql = "SELECT * FROM employee WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
        getInt(4),
        getBoolean(5),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }
}
