package example

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import kotlin.Any
import kotlin.Int
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

  private fun <T : Any, Return> updateParentReturningDescriptionUpper(
    id: UUID,
    mapper: (
      id: UUID,
      name: String,
      description_upper: String?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "UPDATE parent SET description = 'UPDATED' WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper"
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

  override fun <T : Any> updateParentReturningDescriptionUpper(id: UUID, mapper: (
    id: UUID,
    name: String,
    description_upper: String?,
  ) -> T): Many<T> = updateParentReturningDescriptionUpper(id, mapper, driver::queryMany)

  private fun <T : Any, Return> deleteParentReturningQuotedDescriptionUpperViaCte(
    id: UUID,
    mapper: (
      id: UUID,
      name: String,
      descriptionUpper: String?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = """
        |WITH deleted_parent AS (
        |  DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS "descriptionUpper"
        |)
        |SELECT id, name, "descriptionUpper" FROM deleted_parent
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

  override fun <T : Any> deleteParentReturningQuotedDescriptionUpperViaCte(id: UUID, mapper: (
    id: UUID,
    name: String,
    descriptionUpper: String?,
  ) -> T): Many<T> = deleteParentReturningQuotedDescriptionUpperViaCte(id, mapper, driver::queryMany)

  private fun <T : Any, Return> selectUpperNamesFromTwoCtes(mapper: (parent_name_upper: String, child_name_upper: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_upper AS (
        |  SELECT id, UPPER(name) AS name_upper FROM parent
        |),
        |child_upper AS (
        |  SELECT id, UPPER(name) AS name_upper FROM child
        |)
        |SELECT parent_upper.name_upper AS parent_name_upper, child_upper.name_upper AS child_name_upper
        |FROM parent_upper, child_upper
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectUpperNamesFromTwoCtes(mapper: (parent_name_upper: String, child_name_upper: String) -> T): Many<T> = selectUpperNamesFromTwoCtes(mapper, driver::queryMany)

  override fun <T : Any> selectUpperNamesFromTwoCtesDynamically(mapper: (parent_name_upper: String, child_name_upper: String) -> T): Query<T> = selectUpperNamesFromTwoCtes(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectSiblingCtesWithSameOutputName(mapper: (parent_same: String, child_same: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_same AS (
        |  SELECT UPPER(name) AS same FROM parent
        |),
        |child_same AS (
        |  SELECT UPPER(name) AS same FROM child
        |)
        |SELECT parent_same.same AS parent_same, child_same.same AS child_same
        |FROM parent_same, child_same
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectSiblingCtesWithSameOutputName(mapper: (parent_same: String, child_same: String) -> T): Many<T> = selectSiblingCtesWithSameOutputName(mapper, driver::queryMany)

  override fun <T : Any> selectSiblingCtesWithSameOutputNameDynamically(mapper: (parent_same: String, child_same: String) -> T): Query<T> = selectSiblingCtesWithSameOutputName(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectCteViaExplicitFromAliasAs(mapper: (parent_id: UUID, aliased_name_upper: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_upper2 AS (
        |  SELECT id, UPPER(name) AS name_upper FROM parent
        |)
        |SELECT x.id AS parent_id, x.name_upper AS aliased_name_upper FROM parent_upper2 AS x
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectCteViaExplicitFromAliasAs(mapper: (parent_id: UUID, aliased_name_upper: String) -> T): Many<T> = selectCteViaExplicitFromAliasAs(mapper, driver::queryMany)

  override fun <T : Any> selectCteViaExplicitFromAliasAsDynamically(mapper: (parent_id: UUID, aliased_name_upper: String) -> T): Query<T> = selectCteViaExplicitFromAliasAs(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectCteViaImplicitFromAlias(mapper: (parent_id: UUID, aliased_name_upper: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_upper3 AS (
        |  SELECT id, UPPER(name) AS name_upper FROM parent
        |)
        |SELECT x.id AS parent_id, x.name_upper AS aliased_name_upper FROM parent_upper3 x
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectCteViaImplicitFromAlias(mapper: (parent_id: UUID, aliased_name_upper: String) -> T): Many<T> = selectCteViaImplicitFromAlias(mapper, driver::queryMany)

  override fun <T : Any> selectCteViaImplicitFromAliasDynamically(mapper: (parent_id: UUID, aliased_name_upper: String) -> T): Query<T> = selectCteViaImplicitFromAlias(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectCtesJoinedOnPredicate(mapper: (parent_name_upper: String, child_name_upper: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_upper4 AS (
        |  SELECT id, UPPER(name) AS name_upper FROM parent
        |),
        |child_upper4 AS (
        |  SELECT parent_id, UPPER(name) AS name_upper FROM child
        |)
        |SELECT parent_upper4.name_upper AS parent_name_upper, child_upper4.name_upper AS child_name_upper
        |FROM parent_upper4 JOIN child_upper4 ON parent_upper4.id = child_upper4.parent_id
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectCtesJoinedOnPredicate(mapper: (parent_name_upper: String, child_name_upper: String) -> T): Many<T> = selectCtesJoinedOnPredicate(mapper, driver::queryMany)

  override fun <T : Any> selectCtesJoinedOnPredicateDynamically(mapper: (parent_name_upper: String, child_name_upper: String) -> T): Query<T> = selectCtesJoinedOnPredicate(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectCtesInnerJoinUsingMergedColumn(mapper: (shared_label: String, child_own_name: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_label AS (
        |  SELECT UPPER(name) AS shared_label FROM parent
        |),
        |child_label AS (
        |  SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
        |)
        |SELECT shared_label, child_own_name FROM parent_label JOIN child_label USING (shared_label)
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectCtesInnerJoinUsingMergedColumn(mapper: (shared_label: String, child_own_name: String) -> T): Many<T> = selectCtesInnerJoinUsingMergedColumn(mapper, driver::queryMany)

  override fun <T : Any> selectCtesInnerJoinUsingMergedColumnDynamically(mapper: (shared_label: String, child_own_name: String) -> T): Query<T> = selectCtesInnerJoinUsingMergedColumn(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectCtesFullJoinUsingMergedColumn(mapper: (shared_label: String?, child_own_name: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_label AS (
        |  SELECT UPPER(name) AS shared_label FROM parent
        |),
        |child_label AS (
        |  SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
        |)
        |SELECT shared_label, child_own_name FROM parent_label FULL JOIN child_label USING (shared_label)
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectCtesFullJoinUsingMergedColumn(mapper: (shared_label: String?, child_own_name: String?) -> T): Many<T> = selectCtesFullJoinUsingMergedColumn(mapper, driver::queryMany)

  override fun <T : Any> selectCtesFullJoinUsingMergedColumnDynamically(mapper: (shared_label: String?, child_own_name: String?) -> T): Query<T> = selectCtesFullJoinUsingMergedColumn(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectCtesNaturalJoin(mapper: (shared_label: String, child_own_name: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_label AS (
        |  SELECT UPPER(name) AS shared_label FROM parent
        |),
        |child_label AS (
        |  SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
        |)
        |SELECT shared_label, child_own_name FROM parent_label NATURAL JOIN child_label
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectCtesNaturalJoin(mapper: (shared_label: String, child_own_name: String) -> T): Many<T> = selectCtesNaturalJoin(mapper, driver::queryMany)

  override fun <T : Any> selectCtesNaturalJoinDynamically(mapper: (shared_label: String, child_own_name: String) -> T): Query<T> = selectCtesNaturalJoin(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectCtesNaturalFullJoin(mapper: (shared_label: String?, child_own_name: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_label AS (
        |  SELECT UPPER(name) AS shared_label FROM parent
        |),
        |child_label AS (
        |  SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
        |)
        |SELECT shared_label, child_own_name FROM parent_label NATURAL FULL JOIN child_label
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectCtesNaturalFullJoin(mapper: (shared_label: String?, child_own_name: String?) -> T): Many<T> = selectCtesNaturalFullJoin(mapper, driver::queryMany)

  override fun <T : Any> selectCtesNaturalFullJoinDynamically(mapper: (shared_label: String?, child_own_name: String?) -> T): Query<T> = selectCtesNaturalFullJoin(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectMixedFromSourcesCommaSeparated(mapper: (
    parent_name_upper: String,
    child_name: String,
    summary_name: String,
    constant_label: String,
    generated_number: Int?,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_label2 AS (
        |  SELECT id, UPPER(name) AS name_upper FROM parent
        |)
        |SELECT
        |  parent_label2.name_upper AS parent_name_upper,
        |  child.name AS child_name,
        |  child_summary.name AS summary_name,
        |  derived.constant_label,
        |  generated.generated_number
        |FROM parent_label2, child, child_summary, (SELECT 'literal'::TEXT AS constant_label) derived,
        |  generate_series(1, 3) AS generated(generated_number)
        |WHERE child.parent_id = parent_label2.id AND child_summary.id = child.id
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
        getString(3),
        getString(4),
        getInt(5).takeUnless { wasNull() },
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectMixedFromSourcesCommaSeparated(mapper: (
    parent_name_upper: String,
    child_name: String,
    summary_name: String,
    constant_label: String,
    generated_number: Int?,
  ) -> T): Many<T> = selectMixedFromSourcesCommaSeparated(mapper, driver::queryMany)

  override fun <T : Any> selectMixedFromSourcesCommaSeparatedDynamically(mapper: (
    parent_name_upper: String,
    child_name: String,
    summary_name: String,
    constant_label: String,
    generated_number: Int?,
  ) -> T): Query<T> = selectMixedFromSourcesCommaSeparated(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectChainedCteProvenance(mapper: (id: UUID, name_upper: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_label3 AS (
        |  SELECT id, UPPER(name) AS name_upper FROM parent
        |),
        |parent_label3_relay AS (
        |  SELECT id, name_upper FROM parent_label3
        |)
        |SELECT id, name_upper FROM parent_label3_relay
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectChainedCteProvenance(mapper: (id: UUID, name_upper: String) -> T): Many<T> = selectChainedCteProvenance(mapper, driver::queryMany)

  override fun <T : Any> selectChainedCteProvenanceDynamically(mapper: (id: UUID, name_upper: String) -> T): Query<T> = selectChainedCteProvenance(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectNestedCteShadowingOuterName(mapper: (ux: String, n: Int) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH shadow_cte AS (
        |  SELECT UPPER(name) AS ux FROM parent
        |),
        |outer_consumer AS (
        |  WITH shadow_cte AS (
        |    SELECT LOWER(name) AS ux FROM parent
        |  )
        |  SELECT ux, 1 AS n FROM shadow_cte
        |)
        |SELECT ux, n FROM outer_consumer
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getInt(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectNestedCteShadowingOuterName(mapper: (ux: String, n: Int) -> T): Many<T> = selectNestedCteShadowingOuterName(mapper, driver::queryMany)

  override fun <T : Any> selectNestedCteShadowingOuterNameDynamically(mapper: (ux: String, n: Int) -> T): Query<T> = selectNestedCteShadowingOuterName(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> updateChildNameFromParentDescriptionUpper(mapper: (source_parent_id: UUID, updated_description: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH desc_source AS (
        |  SELECT id, UPPER(description) AS description_upper FROM parent
        |)
        |UPDATE child SET name = desc_source.description_upper
        |FROM desc_source
        |WHERE child.parent_id = desc_source.id
        |RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS updated_description
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> updateChildNameFromParentDescriptionUpper(mapper: (source_parent_id: UUID, updated_description: String?) -> T): Many<T> = updateChildNameFromParentDescriptionUpper(mapper, driver::queryMany)

  override fun <T : Any> updateChildNameFromParentDescriptionUpperDynamically(mapper: (source_parent_id: UUID, updated_description: String?) -> T): Query<T> = updateChildNameFromParentDescriptionUpper(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> deleteChildUsingParentDescriptionUpper(mapper: (source_parent_id: UUID, deleted_description: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH desc_source AS (
        |  SELECT id, UPPER(description) AS description_upper FROM parent
        |)
        |DELETE FROM child USING desc_source
        |WHERE child.parent_id = desc_source.id
        |RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS deleted_description
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> deleteChildUsingParentDescriptionUpper(mapper: (source_parent_id: UUID, deleted_description: String?) -> T): Many<T> = deleteChildUsingParentDescriptionUpper(mapper, driver::queryMany)

  override fun <T : Any> deleteChildUsingParentDescriptionUpperDynamically(mapper: (source_parent_id: UUID, deleted_description: String?) -> T): Query<T> = deleteChildUsingParentDescriptionUpper(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> insertChildFromParentDescriptionUpper(mapper: (inserted_parent_id: UUID, inserted_name: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH desc_source AS (
        |  SELECT id, UPPER(description) AS description_upper FROM parent
        |)
        |INSERT INTO child (parent_id, name)
        |SELECT id, description_upper FROM desc_source
        |RETURNING parent_id AS inserted_parent_id, name AS inserted_name
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> insertChildFromParentDescriptionUpper(mapper: (inserted_parent_id: UUID, inserted_name: String?) -> T): Many<T> = insertChildFromParentDescriptionUpper(mapper, driver::queryMany)

  override fun <T : Any> insertChildFromParentDescriptionUpperDynamically(mapper: (inserted_parent_id: UUID, inserted_name: String?) -> T): Query<T> = insertChildFromParentDescriptionUpper(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> mergeChildFromParentDescriptionUpper(mapper: (source_parent_id: UUID, merged_description: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH desc_source AS (
        |  SELECT id, UPPER(description) AS description_upper FROM parent
        |)
        |MERGE INTO child USING desc_source ON child.parent_id = desc_source.id
        |WHEN MATCHED THEN UPDATE SET name = desc_source.description_upper
        |WHEN NOT MATCHED THEN INSERT (parent_id, name) VALUES (desc_source.id, desc_source.description_upper)
        |RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS merged_description
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> mergeChildFromParentDescriptionUpper(mapper: (source_parent_id: UUID, merged_description: String?) -> T): Many<T> = mergeChildFromParentDescriptionUpper(mapper, driver::queryMany)

  override fun <T : Any> mergeChildFromParentDescriptionUpperDynamically(mapper: (source_parent_id: UUID, merged_description: String?) -> T): Query<T> = mergeChildFromParentDescriptionUpper(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectParentViaCteWithExplicitColumnList(mapper: (parent_label: String, parent_id: UUID) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH renamed_parent(parent_label, parent_id) AS (
        |  SELECT UPPER(name), id FROM parent
        |)
        |SELECT parent_label, parent_id FROM renamed_parent
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getObject(2, UUID::class.java),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectParentViaCteWithExplicitColumnList(mapper: (parent_label: String, parent_id: UUID) -> T): Many<T> = selectParentViaCteWithExplicitColumnList(mapper, driver::queryMany)

  override fun <T : Any> selectParentViaCteWithExplicitColumnListDynamically(mapper: (parent_label: String, parent_id: UUID) -> T): Query<T> = selectParentViaCteWithExplicitColumnList(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> countChildGenerationsRecursive(mapper: (
    id: UUID,
    name: String,
    depth: Int,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH RECURSIVE parent_chain AS (
        |  SELECT id, name, 0 AS depth FROM parent
        |  UNION ALL
        |  SELECT p.id, p.name, parent_chain.depth + 1
        |  FROM parent p
        |  JOIN parent_chain ON p.id = parent_chain.id AND parent_chain.depth < 3
        |)
        |SELECT id, name, depth FROM parent_chain
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
        getInt(3),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> countChildGenerationsRecursive(mapper: (
    id: UUID,
    name: String,
    depth: Int,
  ) -> T): Many<T> = countChildGenerationsRecursive(mapper, driver::queryMany)

  override fun <T : Any> countChildGenerationsRecursiveDynamically(mapper: (
    id: UUID,
    name: String,
    depth: Int,
  ) -> T): Query<T> = countChildGenerationsRecursive(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectUpperNameViaCteWithSetOperationBody(mapper: (id: UUID, name_upper: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH combined_upper AS (
        |  SELECT id, UPPER(name) AS name_upper FROM parent
        |  UNION
        |  SELECT id, UPPER(name) FROM child
        |)
        |SELECT id, name_upper FROM combined_upper
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectUpperNameViaCteWithSetOperationBody(mapper: (id: UUID, name_upper: String) -> T): Many<T> = selectUpperNameViaCteWithSetOperationBody(mapper, driver::queryMany)

  override fun <T : Any> selectUpperNameViaCteWithSetOperationBodyDynamically(mapper: (id: UUID, name_upper: String) -> T): Query<T> = selectUpperNameViaCteWithSetOperationBody(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectParentViaCteTable(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH all_parents AS (
        |  TABLE parent
        |)
        |SELECT id, name, description FROM all_parents
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

  override fun <T : Any> selectParentViaCteTable(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Many<T> = selectParentViaCteTable(mapper, driver::queryMany)

  override fun <T : Any> selectParentViaCteTableDynamically(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Query<T> = selectParentViaCteTable(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectViaCteValues(mapper: (column1: Int?, column2: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH constant_rows AS (
        |  VALUES (1, 'a'), (2, 'b')
        |)
        |SELECT column1, column2 FROM constant_rows
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1).takeUnless { wasNull() },
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectViaCteValues(mapper: (column1: Int?, column2: String?) -> T): Many<T> = selectViaCteValues(mapper, driver::queryMany)

  override fun <T : Any> selectViaCteValuesDynamically(mapper: (column1: Int?, column2: String?) -> T): Query<T> = selectViaCteValues(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectParentUpperNameViaParenthesizedCte(mapper: (id: UUID, name_upper: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH upper_name AS (
        |  (SELECT id, UPPER(name) AS name_upper FROM parent)
        |)
        |SELECT id, name_upper FROM upper_name
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectParentUpperNameViaParenthesizedCte(mapper: (id: UUID, name_upper: String) -> T): Many<T> = selectParentUpperNameViaParenthesizedCte(mapper, driver::queryMany)

  override fun <T : Any> selectParentUpperNameViaParenthesizedCteDynamically(mapper: (id: UUID, name_upper: String) -> T): Query<T> = selectParentUpperNameViaParenthesizedCte(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectUpperNameUnionAcrossTables(mapper: (id: UUID?, name_upper: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH parent_names AS (
        |  SELECT id, UPPER(name) AS name_upper FROM parent
        |)
        |SELECT id, name_upper FROM parent_names
        |UNION
        |SELECT id, UPPER(name) FROM child
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectUpperNameUnionAcrossTables(mapper: (id: UUID?, name_upper: String?) -> T): Many<T> = selectUpperNameUnionAcrossTables(mapper, driver::queryMany)

  override fun <T : Any> selectUpperNameUnionAcrossTablesDynamically(mapper: (id: UUID?, name_upper: String?) -> T): Query<T> = selectUpperNameUnionAcrossTables(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectAllColumnsOuterFromCte(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH all_cols AS (
        |  SELECT id, name, description FROM parent
        |)
        |SELECT * FROM all_cols
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

  override fun <T : Any> selectAllColumnsOuterFromCte(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Many<T> = selectAllColumnsOuterFromCte(mapper, driver::queryMany)

  override fun <T : Any> selectAllColumnsOuterFromCteDynamically(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Query<T> = selectAllColumnsOuterFromCte(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectAllColumnsInnerCte(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH all_cols AS (
        |  SELECT * FROM parent
        |)
        |SELECT id, name, description FROM all_cols
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

  override fun <T : Any> selectAllColumnsInnerCte(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Many<T> = selectAllColumnsInnerCte(mapper, driver::queryMany)

  override fun <T : Any> selectAllColumnsInnerCteDynamically(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Query<T> = selectAllColumnsInnerCte(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectParentUpperNameImplicitAlias(mapper: (id: UUID, y: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH upper_name AS (
        |  SELECT id, UPPER(name) y FROM parent
        |)
        |SELECT id, y FROM upper_name
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectParentUpperNameImplicitAlias(mapper: (id: UUID, y: String) -> T): Many<T> = selectParentUpperNameImplicitAlias(mapper, driver::queryMany)

  override fun <T : Any> selectParentUpperNameImplicitAliasDynamically(mapper: (id: UUID, y: String) -> T): Query<T> = selectParentUpperNameImplicitAlias(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T : Any, Return> selectViaQuotedCteNameWithEmbeddedQuotes(mapper: (id: UUID, `My Col`: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |WITH "He""llo" AS (
        |  SELECT id, UPPER(name) AS "My Col" FROM parent
        |)
        |SELECT id, "My Col" FROM "He""llo"
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> selectViaQuotedCteNameWithEmbeddedQuotes(mapper: (id: UUID, `My Col`: String) -> T): Many<T> = selectViaQuotedCteNameWithEmbeddedQuotes(mapper, driver::queryMany)

  override fun <T : Any> selectViaQuotedCteNameWithEmbeddedQuotesDynamically(mapper: (id: UUID, `My Col`: String) -> T): Query<T> = selectViaQuotedCteNameWithEmbeddedQuotes(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }
}
