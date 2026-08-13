package example

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import kotlin.Any
import kotlin.String
import kotlin.Unit
import norm.ConnectionProvider
import norm.Many
import norm.ManyProcessor
import norm.NormDriver
import norm.RealTransactable

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : RealTransactable(connectionProvider),
    Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  private fun <T : Any, Return> createParentWithChildAndAuditEntry(
    name: String,
    p2: String,
    mapper: (
      id: UUID,
      parent_id: UUID,
      name: String,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = """
        |WITH new_parent AS (
        |  INSERT INTO parent (name) VALUES (?) RETURNING id, name
        |),
        |new_child AS (
        |  INSERT INTO child (parent_id, name)
        |  SELECT id, ? FROM new_parent
        |  RETURNING id, parent_id, name
        |),
        |audit_entry AS (
        |  INSERT INTO child (parent_id, name)
        |  SELECT parent_id, name || '_audit' FROM new_child
        |  ON CONFLICT (parent_id, name) DO NOTHING
        |)
        |SELECT id, parent_id, name FROM new_child
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getObject(2, UUID::class.java),
        getString(3),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setString(1, name)
      setString(2, p2)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> createParentWithChildAndAuditEntry(
    name: String,
    p2: String,
    mapper: (
      id: UUID,
      parent_id: UUID,
      name: String,
    ) -> T,
  ): Many<T> = createParentWithChildAndAuditEntry(name, p2, mapper, driver::queryMany)
}
