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
import norm.Query
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

  private fun <T : Any, Return> createParentFromLaterCte(mapper: (id: UUID, name: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH RECURSIVE new_parent AS (
        |  INSERT INTO parent (name) SELECT label FROM parent_name RETURNING id, name
        |),
        |parent_name AS (
        |  SELECT 'seeded'::TEXT AS label
        |)
        |SELECT id, name FROM new_parent
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> createParentFromLaterCte(mapper: (id: UUID, name: String) -> T): Many<T> = createParentFromLaterCte(mapper, driver::queryMany)

  override fun <T : Any> createParentFromLaterCteDynamically(mapper: (id: UUID, name: String) -> T): Query<T> = createParentFromLaterCte(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> listParentsWithOptionalChildAlongsideInsert(mapper: (
    parent_id: UUID,
    parent_name: String,
    child_name: String?,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_and_child AS (
        |  WITH helper AS (SELECT 1)
        |  SELECT parent.id AS parent_id, parent.name AS parent_name, child.name AS child_name
        |  FROM parent LEFT JOIN child ON child.parent_id = parent.id
        |),
        |new_parent AS (
        |  INSERT INTO parent (name) VALUES ('placeholder') RETURNING id
        |)
        |SELECT parent_id, parent_name, child_name FROM parent_and_child
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
        getString(3),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> listParentsWithOptionalChildAlongsideInsert(mapper: (
    parent_id: UUID,
    parent_name: String,
    child_name: String?,
  ) -> T): Many<T> = listParentsWithOptionalChildAlongsideInsert(mapper, driver::queryMany)

  override fun <T : Any> listParentsWithOptionalChildAlongsideInsertDynamically(mapper: (
    parent_id: UUID,
    parent_name: String,
    child_name: String?,
  ) -> T): Query<T> = listParentsWithOptionalChildAlongsideInsert(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> createParentReturningQuotedAlias(
    name: String,
    mapper: (parentId: UUID, parentDescription: String?) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = """
        |WITH new_parent AS (
        |  INSERT INTO parent (name) VALUES (?) RETURNING id AS "parentId", description AS "parentDescription"
        |)
        |SELECT new_parent."parentId", new_parent."parentDescription" FROM new_parent
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setString(1, name)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> createParentReturningQuotedAlias(name: String, mapper: (parentId: UUID, parentDescription: String?) -> T): Many<T> = createParentReturningQuotedAlias(name, mapper, driver::queryMany)

  private fun <T : Any, Return> deleteParentReturningDescriptionUpper(
    id: UUID,
    mapper: (
      id: UUID,
      name: String,
      description_upper: String?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
        getString(3),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setObject(1, id)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> deleteParentReturningDescriptionUpper(id: UUID, mapper: (
    id: UUID,
    name: String,
    description_upper: String?,
  ) -> T): Many<T> = deleteParentReturningDescriptionUpper(id, mapper, driver::queryMany)

  private fun <T : Any, Return> deleteParentReturningDescriptionUpperViaCte(
    id: UUID,
    mapper: (
      id: UUID,
      name: String,
      description_upper: String?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = """
        |WITH deleted_parent AS (
        |  DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
        |)
        |SELECT id, name, description_upper FROM deleted_parent
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
        getString(3),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setObject(1, id)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> deleteParentReturningDescriptionUpperViaCte(id: UUID, mapper: (
    id: UUID,
    name: String,
    description_upper: String?,
  ) -> T): Many<T> = deleteParentReturningDescriptionUpperViaCte(id, mapper, driver::queryMany)
}
