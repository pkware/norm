package example

import java.util.UUID
import kotlin.Any
import kotlin.String
import norm.Many
import norm.Query
import norm.Transactable

public interface Queries : Transactable {
  /**
   * Creates a parent, a child of that parent, and an audit entry for the child.
   *
   * ```sql
   * WITH new_parent AS (
   *   INSERT INTO parent (name) VALUES (?) RETURNING id, name
   * ),
   * new_child AS (
   *   INSERT INTO child (parent_id, name)
   *   SELECT id, ? FROM new_parent
   *   RETURNING id, parent_id, name
   * ),
   * audit_entry AS (
   *   INSERT INTO child (parent_id, name)
   *   SELECT parent_id, name || '_audit' FROM new_child
   *   ON CONFLICT (parent_id, name) DO NOTHING
   * )
   * SELECT id, parent_id, name FROM new_child
   * ```
   */
  public fun <T : Any> createParentWithChildAndAuditEntry(
    name: String,
    p2: String,
    mapper: (
      id: UUID,
      parent_id: UUID,
      name: String,
    ) -> T,
  ): Many<T>

  /**
   * Creates a parent, a child of that parent, and an audit entry for the child.
   *
   * ```sql
   * WITH new_parent AS (
   *   INSERT INTO parent (name) VALUES (?) RETURNING id, name
   * ),
   * new_child AS (
   *   INSERT INTO child (parent_id, name)
   *   SELECT id, ? FROM new_parent
   *   RETURNING id, parent_id, name
   * ),
   * audit_entry AS (
   *   INSERT INTO child (parent_id, name)
   *   SELECT parent_id, name || '_audit' FROM new_child
   *   ON CONFLICT (parent_id, name) DO NOTHING
   * )
   * SELECT id, parent_id, name FROM new_child
   * ```
   */
  public fun createParentWithChildAndAuditEntry(name: String, p2: String): Many<Child> = createParentWithChildAndAuditEntry(name, p2, ::Child)

  /**
   * Creates a parent whose name is seeded from another CTE in the same statement.
   *
   * ```sql
   * WITH RECURSIVE new_parent AS (
   *   INSERT INTO parent (name) SELECT label FROM parent_name RETURNING id, name
   * ),
   * parent_name AS (
   *   SELECT 'seeded'::TEXT AS label
   * )
   * SELECT id, name FROM new_parent
   * ```
   */
  public fun <T : Any> createParentFromLaterCte(mapper: (id: UUID, name: String) -> T): Many<T>

  /**
   * Creates a parent whose name is seeded from another CTE in the same statement.
   *
   * ```sql
   * WITH RECURSIVE new_parent AS (
   *   INSERT INTO parent (name) SELECT label FROM parent_name RETURNING id, name
   * ),
   * parent_name AS (
   *   SELECT 'seeded'::TEXT AS label
   * )
   * SELECT id, name FROM new_parent
   * ```
   */
  public fun createParentFromLaterCte(): Many<CreateParentFromLaterCte> = createParentFromLaterCte(::CreateParentFromLaterCte)

  public fun <T : Any> createParentFromLaterCteDynamically(mapper: (id: UUID, name: String) -> T): Query<T>

  public fun createParentFromLaterCteDynamically(): Query<CreateParentFromLaterCte> = createParentFromLaterCteDynamically(::CreateParentFromLaterCte)

  /**
   * Lists parents with their child's name, if any; child_name is `null` when a parent has no child.
   *
   * ```sql
   * WITH parent_and_child AS (
   *   WITH helper AS (SELECT 1)
   *   SELECT parent.id AS parent_id, parent.name AS parent_name, child.name AS child_name
   *   FROM parent LEFT JOIN child ON child.parent_id = parent.id
   * ),
   * new_parent AS (
   *   INSERT INTO parent (name) VALUES ('placeholder') RETURNING id
   * )
   * SELECT parent_id, parent_name, child_name FROM parent_and_child
   * ```
   */
  public fun <T : Any> listParentsWithOptionalChildAlongsideInsert(mapper: (
    parent_id: UUID,
    parent_name: String,
    child_name: String?,
  ) -> T): Many<T>

  /**
   * Lists parents with their child's name, if any; child_name is `null` when a parent has no child.
   *
   * ```sql
   * WITH parent_and_child AS (
   *   WITH helper AS (SELECT 1)
   *   SELECT parent.id AS parent_id, parent.name AS parent_name, child.name AS child_name
   *   FROM parent LEFT JOIN child ON child.parent_id = parent.id
   * ),
   * new_parent AS (
   *   INSERT INTO parent (name) VALUES ('placeholder') RETURNING id
   * )
   * SELECT parent_id, parent_name, child_name FROM parent_and_child
   * ```
   */
  public fun listParentsWithOptionalChildAlongsideInsert(): Many<ListParentsWithOptionalChildAlongsideInsert> = listParentsWithOptionalChildAlongsideInsert(::ListParentsWithOptionalChildAlongsideInsert)

  public fun <T : Any> listParentsWithOptionalChildAlongsideInsertDynamically(mapper: (
    parent_id: UUID,
    parent_name: String,
    child_name: String?,
  ) -> T): Query<T>

  public fun listParentsWithOptionalChildAlongsideInsertDynamically(): Query<ListParentsWithOptionalChildAlongsideInsert> = listParentsWithOptionalChildAlongsideInsertDynamically(::ListParentsWithOptionalChildAlongsideInsert)

  /**
   * Creates a parent, returning its id and description under quoted, mixed-case aliases.
   *
   * ```sql
   * WITH new_parent AS (
   *   INSERT INTO parent (name) VALUES (?) RETURNING id AS "parentId", description AS "parentDescription"
   * )
   * SELECT new_parent."parentId", new_parent."parentDescription" FROM new_parent
   * ```
   */
  public fun <T : Any> createParentReturningQuotedAlias(name: String, mapper: (parentId: UUID, parentDescription: String?) -> T): Many<T>

  /**
   * Creates a parent, returning its id and description under quoted, mixed-case aliases.
   *
   * ```sql
   * WITH new_parent AS (
   *   INSERT INTO parent (name) VALUES (?) RETURNING id AS "parentId", description AS "parentDescription"
   * )
   * SELECT new_parent."parentId", new_parent."parentDescription" FROM new_parent
   * ```
   */
  public fun createParentReturningQuotedAlias(name: String): Many<CreateParentReturningQuotedAlias> = createParentReturningQuotedAlias(name, ::CreateParentReturningQuotedAlias)

  /**
   * Deletes a parent, returning its name and uppercased description.
   *
   * ```sql
   * DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
   * ```
   */
  public fun <T : Any> deleteParentReturningDescriptionUpper(id: UUID, mapper: (
    id: UUID,
    name: String,
    description_upper: String?,
  ) -> T): Many<T>

  /**
   * Deletes a parent, returning its name and uppercased description.
   *
   * ```sql
   * DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
   * ```
   */
  public fun deleteParentReturningDescriptionUpper(id: UUID): Many<DeleteParentReturningDescriptionUpper> = deleteParentReturningDescriptionUpper(id, ::DeleteParentReturningDescriptionUpper)

  /**
   * CTE-wrapped form of deleteParentReturningDescriptionUpper above.
   *
   * ```sql
   * WITH deleted_parent AS (
   *   DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
   * )
   * SELECT id, name, description_upper FROM deleted_parent
   * ```
   */
  public fun <T : Any> deleteParentReturningDescriptionUpperViaCte(id: UUID, mapper: (
    id: UUID,
    name: String,
    description_upper: String?,
  ) -> T): Many<T>

  /**
   * CTE-wrapped form of deleteParentReturningDescriptionUpper above.
   *
   * ```sql
   * WITH deleted_parent AS (
   *   DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
   * )
   * SELECT id, name, description_upper FROM deleted_parent
   * ```
   */
  public fun deleteParentReturningDescriptionUpperViaCte(id: UUID): Many<DeleteParentReturningDescriptionUpperViaCte> = deleteParentReturningDescriptionUpperViaCte(id, ::DeleteParentReturningDescriptionUpperViaCte)

  /**
   * Updates a parent's description to a fixed value, returning its name and uppercased description.
   *
   * ```sql
   * UPDATE parent SET description = 'UPDATED' WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
   * ```
   */
  public fun <T : Any> updateParentReturningDescriptionUpper(id: UUID, mapper: (
    id: UUID,
    name: String,
    description_upper: String?,
  ) -> T): Many<T>

  /**
   * Updates a parent's description to a fixed value, returning its name and uppercased description.
   *
   * ```sql
   * UPDATE parent SET description = 'UPDATED' WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
   * ```
   */
  public fun updateParentReturningDescriptionUpper(id: UUID): Many<UpdateParentReturningDescriptionUpper> = updateParentReturningDescriptionUpper(id, ::UpdateParentReturningDescriptionUpper)

  /**
   * Quoted-alias variant of deleteParentReturningDescriptionUpperViaCte above.
   *
   * ```sql
   * WITH deleted_parent AS (
   *   DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS "descriptionUpper"
   * )
   * SELECT id, name, "descriptionUpper" FROM deleted_parent
   * ```
   */
  public fun <T : Any> deleteParentReturningQuotedDescriptionUpperViaCte(id: UUID, mapper: (
    id: UUID,
    name: String,
    descriptionUpper: String?,
  ) -> T): Many<T>

  /**
   * Quoted-alias variant of deleteParentReturningDescriptionUpperViaCte above.
   *
   * ```sql
   * WITH deleted_parent AS (
   *   DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS "descriptionUpper"
   * )
   * SELECT id, name, "descriptionUpper" FROM deleted_parent
   * ```
   */
  public fun deleteParentReturningQuotedDescriptionUpperViaCte(id: UUID): Many<DeleteParentReturningQuotedDescriptionUpperViaCte> = deleteParentReturningQuotedDescriptionUpperViaCte(id, ::DeleteParentReturningQuotedDescriptionUpperViaCte)
}
