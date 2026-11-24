package example.testqueries

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.Any
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Unit
import norm.ColumnAdapter
import norm.ConnectionProvider
import norm.Many
import norm.ManyProcessor
import norm.NormDriver
import norm.Query
import norm.RealTransactable

public class PostgresTestQueries(
  connectionProvider: ConnectionProvider,
  private val auditActionAdapter: ColumnAdapter<AuditAction, String> = AuditActionAdapter(),
) : RealTransactable(connectionProvider),
    TestQueries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  private fun <T : Any, Return> listAuthorsForAssertions(mapper: (id: Int, name: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT * FROM author ORDER BY id"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> listAuthorsForAssertions(mapper: (id: Int, name: String) -> T): Many<T> = listAuthorsForAssertions(mapper, driver::queryMany)

  override fun <T : Any> listAuthorsForAssertionsDynamically(mapper: (id: Int, name: String) -> T): Query<T> = listAuthorsForAssertions(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> listAuditLog(mapper: (
    id: Int,
    author_id: Int,
    action: AuditAction,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT * FROM audit_log ORDER BY id"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getInt(2),
        auditActionAdapter.decode(getString(3)),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> listAuditLog(mapper: (
    id: Int,
    author_id: Int,
    action: AuditAction,
  ) -> T): Many<T> = listAuditLog(mapper, driver::queryMany)

  override fun <T : Any> listAuditLogDynamically(mapper: (
    id: Int,
    author_id: Int,
    action: AuditAction,
  ) -> T): Query<T> = listAuditLog(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> listAuditLogByAction(
    action: AuditAction,
    mapper: (
      id: Int,
      author_id: Int,
      action: AuditAction,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM audit_log WHERE action = ? ORDER BY id"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getInt(2),
        auditActionAdapter.decode(getString(3)),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setObject(1, auditActionAdapter.encode(action), Types.OTHER)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> listAuditLogByAction(action: AuditAction, mapper: (
    id: Int,
    author_id: Int,
    action: AuditAction,
  ) -> T): Many<T> = listAuditLogByAction(action, mapper, driver::queryMany)

  private fun <T : Any, Return> countAuditLogByAuthor(mapper: (author_id: Int, entries: Long) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT author_id, count(*) AS entries FROM audit_log GROUP BY author_id ORDER BY author_id"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getLong(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> countAuditLogByAuthor(mapper: (author_id: Int, entries: Long) -> T): Many<T> = countAuditLogByAuthor(mapper, driver::queryMany)

  override fun <T : Any> countAuditLogByAuthorDynamically(mapper: (author_id: Int, entries: Long) -> T): Query<T> = countAuditLogByAuthor(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }
}
