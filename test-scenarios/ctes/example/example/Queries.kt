package example

import java.util.UUID
import kotlin.Any
import kotlin.String
import norm.Many
import norm.Query
import norm.Transactable

public interface Queries : Transactable {
  /**
   * Reproduces #202: a chained data-modifying CTE ("new_child") references an earlier
   * data-modifying CTE ("new_parent"), and a trailing CTE ("audit_entry") is data-modifying
   * with no RETURNING clause at all.
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
   * Reproduces #202: a chained data-modifying CTE ("new_child") references an earlier
   * data-modifying CTE ("new_parent"), and a trailing CTE ("audit_entry") is data-modifying
   * with no RETURNING clause at all.
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
   * Reproduces #203: a data-modifying CTE ("new_parent") references a CTE declared LATER
   * in the same WITH RECURSIVE clause ("parent_name").
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
   * Reproduces #203: a data-modifying CTE ("new_parent") references a CTE declared LATER
   * in the same WITH RECURSIVE clause ("parent_name").
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
  public fun createParentFromLaterCte(): Many<Parent> = createParentFromLaterCte(::Parent)

  public fun <T : Any> createParentFromLaterCteDynamically(mapper: (id: UUID, name: String) -> T): Query<T>

  public fun createParentFromLaterCteDynamically(): Query<Parent> = createParentFromLaterCteDynamically(::Parent)

  /**
   * Reproduces #205: a CTE body ("parent_and_child") starts with its own nested WITH clause
   * ("helper") and contains a LEFT JOIN, alongside a sibling data-modifying CTE ("new_parent").
   * The nested WITH must not cause the LEFT JOIN's nullability to be lost — child_name is
   * nullable (child may not exist for a parent), parent_name stays NOT NULL.
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
   * Reproduces #205: a CTE body ("parent_and_child") starts with its own nested WITH clause
   * ("helper") and contains a LEFT JOIN, alongside a sibling data-modifying CTE ("new_parent").
   * The nested WITH must not cause the LEFT JOIN's nullability to be lost — child_name is
   * nullable (child may not exist for a parent), parent_name stays NOT NULL.
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
}
