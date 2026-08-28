package example

import java.util.UUID
import kotlin.Any
import kotlin.Int
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

  /**
   * #238: two independently declared CTEs, each referenced by its own real name.
   *
   * ```sql
   * WITH parent_upper AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * ),
   * child_upper AS (
   *   SELECT id, UPPER(name) AS name_upper FROM child
   * )
   * SELECT parent_upper.name_upper AS parent_name_upper, child_upper.name_upper AS child_name_upper
   * FROM parent_upper, child_upper
   * ```
   */
  public fun <T : Any> selectUpperNamesFromTwoCtes(mapper: (parent_name_upper: String, child_name_upper: String) -> T): Many<T>

  /**
   * #238: two independently declared CTEs, each referenced by its own real name.
   *
   * ```sql
   * WITH parent_upper AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * ),
   * child_upper AS (
   *   SELECT id, UPPER(name) AS name_upper FROM child
   * )
   * SELECT parent_upper.name_upper AS parent_name_upper, child_upper.name_upper AS child_name_upper
   * FROM parent_upper, child_upper
   * ```
   */
  public fun selectUpperNamesFromTwoCtes(): Many<SelectUpperNamesFromTwoCtes> = selectUpperNamesFromTwoCtes(::SelectUpperNamesFromTwoCtes)

  public fun <T : Any> selectUpperNamesFromTwoCtesDynamically(mapper: (parent_name_upper: String, child_name_upper: String) -> T): Query<T>

  public fun selectUpperNamesFromTwoCtesDynamically(): Query<SelectUpperNamesFromTwoCtes> = selectUpperNamesFromTwoCtesDynamically(::SelectUpperNamesFromTwoCtes)

  /**
   * #238: sibling CTEs that both name their output column the same thing must still resolve
   * independently, not to whichever sibling a text scan happens to see first.
   *
   * ```sql
   * WITH parent_same AS (
   *   SELECT UPPER(name) AS same FROM parent
   * ),
   * child_same AS (
   *   SELECT UPPER(name) AS same FROM child
   * )
   * SELECT parent_same.same AS parent_same, child_same.same AS child_same
   * FROM parent_same, child_same
   * ```
   */
  public fun <T : Any> selectSiblingCtesWithSameOutputName(mapper: (parent_same: String, child_same: String) -> T): Many<T>

  /**
   * #238: sibling CTEs that both name their output column the same thing must still resolve
   * independently, not to whichever sibling a text scan happens to see first.
   *
   * ```sql
   * WITH parent_same AS (
   *   SELECT UPPER(name) AS same FROM parent
   * ),
   * child_same AS (
   *   SELECT UPPER(name) AS same FROM child
   * )
   * SELECT parent_same.same AS parent_same, child_same.same AS child_same
   * FROM parent_same, child_same
   * ```
   */
  public fun selectSiblingCtesWithSameOutputName(): Many<SelectSiblingCtesWithSameOutputName> = selectSiblingCtesWithSameOutputName(::SelectSiblingCtesWithSameOutputName)

  public fun <T : Any> selectSiblingCtesWithSameOutputNameDynamically(mapper: (parent_same: String, child_same: String) -> T): Query<T>

  public fun selectSiblingCtesWithSameOutputNameDynamically(): Query<SelectSiblingCtesWithSameOutputName> = selectSiblingCtesWithSameOutputNameDynamically(::SelectSiblingCtesWithSameOutputName)

  /**
   * #238: a CTE addressed through an explicit `AS x` FROM alias, referenced by the alias.
   *
   * ```sql
   * WITH parent_upper2 AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * )
   * SELECT x.id AS parent_id, x.name_upper AS aliased_name_upper FROM parent_upper2 AS x
   * ```
   */
  public fun <T : Any> selectCteViaExplicitFromAliasAs(mapper: (parent_id: UUID, aliased_name_upper: String) -> T): Many<T>

  /**
   * #238: a CTE addressed through an explicit `AS x` FROM alias, referenced by the alias.
   *
   * ```sql
   * WITH parent_upper2 AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * )
   * SELECT x.id AS parent_id, x.name_upper AS aliased_name_upper FROM parent_upper2 AS x
   * ```
   */
  public fun selectCteViaExplicitFromAliasAs(): Many<SelectCteViaExplicitFromAliasAs> = selectCteViaExplicitFromAliasAs(::SelectCteViaExplicitFromAliasAs)

  public fun <T : Any> selectCteViaExplicitFromAliasAsDynamically(mapper: (parent_id: UUID, aliased_name_upper: String) -> T): Query<T>

  public fun selectCteViaExplicitFromAliasAsDynamically(): Query<SelectCteViaExplicitFromAliasAs> = selectCteViaExplicitFromAliasAsDynamically(::SelectCteViaExplicitFromAliasAs)

  /**
   * #238: a CTE addressed through an implicit (no `AS`) FROM alias, referenced by the alias.
   *
   * ```sql
   * WITH parent_upper3 AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * )
   * SELECT x.id AS parent_id, x.name_upper AS aliased_name_upper FROM parent_upper3 x
   * ```
   */
  public fun <T : Any> selectCteViaImplicitFromAlias(mapper: (parent_id: UUID, aliased_name_upper: String) -> T): Many<T>

  /**
   * #238: a CTE addressed through an implicit (no `AS`) FROM alias, referenced by the alias.
   *
   * ```sql
   * WITH parent_upper3 AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * )
   * SELECT x.id AS parent_id, x.name_upper AS aliased_name_upper FROM parent_upper3 x
   * ```
   */
  public fun selectCteViaImplicitFromAlias(): Many<SelectCteViaImplicitFromAlias> = selectCteViaImplicitFromAlias(::SelectCteViaImplicitFromAlias)

  public fun <T : Any> selectCteViaImplicitFromAliasDynamically(mapper: (parent_id: UUID, aliased_name_upper: String) -> T): Query<T>

  public fun selectCteViaImplicitFromAliasDynamically(): Query<SelectCteViaImplicitFromAlias> = selectCteViaImplicitFromAliasDynamically(::SelectCteViaImplicitFromAlias)

  /**
   * #238: two CTEs joined with an explicit ON predicate.
   *
   * ```sql
   * WITH parent_upper4 AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * ),
   * child_upper4 AS (
   *   SELECT parent_id, UPPER(name) AS name_upper FROM child
   * )
   * SELECT parent_upper4.name_upper AS parent_name_upper, child_upper4.name_upper AS child_name_upper
   * FROM parent_upper4 JOIN child_upper4 ON parent_upper4.id = child_upper4.parent_id
   * ```
   */
  public fun <T : Any> selectCtesJoinedOnPredicate(mapper: (parent_name_upper: String, child_name_upper: String) -> T): Many<T>

  /**
   * #238: two CTEs joined with an explicit ON predicate.
   *
   * ```sql
   * WITH parent_upper4 AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * ),
   * child_upper4 AS (
   *   SELECT parent_id, UPPER(name) AS name_upper FROM child
   * )
   * SELECT parent_upper4.name_upper AS parent_name_upper, child_upper4.name_upper AS child_name_upper
   * FROM parent_upper4 JOIN child_upper4 ON parent_upper4.id = child_upper4.parent_id
   * ```
   */
  public fun selectCtesJoinedOnPredicate(): Many<SelectCtesJoinedOnPredicate> = selectCtesJoinedOnPredicate(::SelectCtesJoinedOnPredicate)

  public fun <T : Any> selectCtesJoinedOnPredicateDynamically(mapper: (parent_name_upper: String, child_name_upper: String) -> T): Query<T>

  public fun selectCtesJoinedOnPredicateDynamically(): Query<SelectCtesJoinedOnPredicate> = selectCtesJoinedOnPredicateDynamically(::SelectCtesJoinedOnPredicate)

  /**
   * #238: an INNER JOIN USING merged column between two CTEs; PostgreSQL aliases it directly to the
   * left side, so this DOES resolve. child_own_name is a bare, non-merged column from the right side,
   * included only to force a generated data class to inspect.
   *
   * ```sql
   * WITH parent_label AS (
   *   SELECT UPPER(name) AS shared_label FROM parent
   * ),
   * child_label AS (
   *   SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
   * )
   * SELECT shared_label, child_own_name FROM parent_label JOIN child_label USING (shared_label)
   * ```
   */
  public fun <T : Any> selectCtesInnerJoinUsingMergedColumn(mapper: (shared_label: String, child_own_name: String) -> T): Many<T>

  /**
   * #238: an INNER JOIN USING merged column between two CTEs; PostgreSQL aliases it directly to the
   * left side, so this DOES resolve. child_own_name is a bare, non-merged column from the right side,
   * included only to force a generated data class to inspect.
   *
   * ```sql
   * WITH parent_label AS (
   *   SELECT UPPER(name) AS shared_label FROM parent
   * ),
   * child_label AS (
   *   SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
   * )
   * SELECT shared_label, child_own_name FROM parent_label JOIN child_label USING (shared_label)
   * ```
   */
  public fun selectCtesInnerJoinUsingMergedColumn(): Many<SelectCtesInnerJoinUsingMergedColumn> = selectCtesInnerJoinUsingMergedColumn(::SelectCtesInnerJoinUsingMergedColumn)

  public fun <T : Any> selectCtesInnerJoinUsingMergedColumnDynamically(mapper: (shared_label: String, child_own_name: String) -> T): Query<T>

  public fun selectCtesInnerJoinUsingMergedColumnDynamically(): Query<SelectCtesInnerJoinUsingMergedColumn> = selectCtesInnerJoinUsingMergedColumnDynamically(::SelectCtesInnerJoinUsingMergedColumn)

  /**
   * #238: a FULL JOIN USING merged column between two CTEs is `COALESCE(left, right)`, so it must
   * resolve to nothing rather than attributing it to either side.
   *
   * ```sql
   * WITH parent_label AS (
   *   SELECT UPPER(name) AS shared_label FROM parent
   * ),
   * child_label AS (
   *   SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
   * )
   * SELECT shared_label, child_own_name FROM parent_label FULL JOIN child_label USING (shared_label)
   * ```
   */
  public fun <T : Any> selectCtesFullJoinUsingMergedColumn(mapper: (shared_label: String?, child_own_name: String?) -> T): Many<T>

  /**
   * #238: a FULL JOIN USING merged column between two CTEs is `COALESCE(left, right)`, so it must
   * resolve to nothing rather than attributing it to either side.
   *
   * ```sql
   * WITH parent_label AS (
   *   SELECT UPPER(name) AS shared_label FROM parent
   * ),
   * child_label AS (
   *   SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
   * )
   * SELECT shared_label, child_own_name FROM parent_label FULL JOIN child_label USING (shared_label)
   * ```
   */
  public fun selectCtesFullJoinUsingMergedColumn(): Many<SelectCtesFullJoinUsingMergedColumn> = selectCtesFullJoinUsingMergedColumn(::SelectCtesFullJoinUsingMergedColumn)

  public fun <T : Any> selectCtesFullJoinUsingMergedColumnDynamically(mapper: (shared_label: String?, child_own_name: String?) -> T): Query<T>

  public fun selectCtesFullJoinUsingMergedColumnDynamically(): Query<SelectCtesFullJoinUsingMergedColumn> = selectCtesFullJoinUsingMergedColumnDynamically(::SelectCtesFullJoinUsingMergedColumn)

  /**
   * #238: a plain (inner) NATURAL JOIN merged column between two CTEs; like INNER JOIN USING, this
   * DOES resolve.
   *
   * ```sql
   * WITH parent_label AS (
   *   SELECT UPPER(name) AS shared_label FROM parent
   * ),
   * child_label AS (
   *   SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
   * )
   * SELECT shared_label, child_own_name FROM parent_label NATURAL JOIN child_label
   * ```
   */
  public fun <T : Any> selectCtesNaturalJoin(mapper: (shared_label: String, child_own_name: String) -> T): Many<T>

  /**
   * #238: a plain (inner) NATURAL JOIN merged column between two CTEs; like INNER JOIN USING, this
   * DOES resolve.
   *
   * ```sql
   * WITH parent_label AS (
   *   SELECT UPPER(name) AS shared_label FROM parent
   * ),
   * child_label AS (
   *   SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
   * )
   * SELECT shared_label, child_own_name FROM parent_label NATURAL JOIN child_label
   * ```
   */
  public fun selectCtesNaturalJoin(): Many<SelectCtesNaturalJoin> = selectCtesNaturalJoin(::SelectCtesNaturalJoin)

  public fun <T : Any> selectCtesNaturalJoinDynamically(mapper: (shared_label: String, child_own_name: String) -> T): Query<T>

  public fun selectCtesNaturalJoinDynamically(): Query<SelectCtesNaturalJoin> = selectCtesNaturalJoinDynamically(::SelectCtesNaturalJoin)

  /**
   * #238: a NATURAL FULL JOIN merged column between two CTEs is the same COALESCE case as
   * selectCtesFullJoinUsingMergedColumn above, so it must resolve to nothing.
   *
   * ```sql
   * WITH parent_label AS (
   *   SELECT UPPER(name) AS shared_label FROM parent
   * ),
   * child_label AS (
   *   SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
   * )
   * SELECT shared_label, child_own_name FROM parent_label NATURAL FULL JOIN child_label
   * ```
   */
  public fun <T : Any> selectCtesNaturalFullJoin(mapper: (shared_label: String?, child_own_name: String?) -> T): Many<T>

  /**
   * #238: a NATURAL FULL JOIN merged column between two CTEs is the same COALESCE case as
   * selectCtesFullJoinUsingMergedColumn above, so it must resolve to nothing.
   *
   * ```sql
   * WITH parent_label AS (
   *   SELECT UPPER(name) AS shared_label FROM parent
   * ),
   * child_label AS (
   *   SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
   * )
   * SELECT shared_label, child_own_name FROM parent_label NATURAL FULL JOIN child_label
   * ```
   */
  public fun selectCtesNaturalFullJoin(): Many<SelectCtesNaturalFullJoin> = selectCtesNaturalFullJoin(::SelectCtesNaturalFullJoin)

  public fun <T : Any> selectCtesNaturalFullJoinDynamically(mapper: (shared_label: String?, child_own_name: String?) -> T): Query<T>

  public fun selectCtesNaturalFullJoinDynamically(): Query<SelectCtesNaturalFullJoin> = selectCtesNaturalFullJoinDynamically(::SelectCtesNaturalFullJoin)

  /**
   * #238: a comma-separated FROM list mixing a CTE, an ordinary table, a view, a derived table, and
   * a set-returning function; only the CTE-derived column should document its expression.
   *
   * ```sql
   * WITH parent_label2 AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * )
   * SELECT
   *   parent_label2.name_upper AS parent_name_upper,
   *   child.name AS child_name,
   *   child_summary.name AS summary_name,
   *   derived.constant_label,
   *   generated.generated_number
   * FROM parent_label2, child, child_summary, (SELECT 'literal'::TEXT AS constant_label) derived,
   *   generate_series(1, 3) AS generated(generated_number)
   * WHERE child.parent_id = parent_label2.id AND child_summary.id = child.id
   * ```
   */
  public fun <T : Any> selectMixedFromSourcesCommaSeparated(mapper: (
    parent_name_upper: String,
    child_name: String,
    summary_name: String,
    constant_label: String,
    generated_number: Int?,
  ) -> T): Many<T>

  /**
   * #238: a comma-separated FROM list mixing a CTE, an ordinary table, a view, a derived table, and
   * a set-returning function; only the CTE-derived column should document its expression.
   *
   * ```sql
   * WITH parent_label2 AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * )
   * SELECT
   *   parent_label2.name_upper AS parent_name_upper,
   *   child.name AS child_name,
   *   child_summary.name AS summary_name,
   *   derived.constant_label,
   *   generated.generated_number
   * FROM parent_label2, child, child_summary, (SELECT 'literal'::TEXT AS constant_label) derived,
   *   generate_series(1, 3) AS generated(generated_number)
   * WHERE child.parent_id = parent_label2.id AND child_summary.id = child.id
   * ```
   */
  public fun selectMixedFromSourcesCommaSeparated(): Many<SelectMixedFromSourcesCommaSeparated> = selectMixedFromSourcesCommaSeparated(::SelectMixedFromSourcesCommaSeparated)

  public fun <T : Any> selectMixedFromSourcesCommaSeparatedDynamically(mapper: (
    parent_name_upper: String,
    child_name: String,
    summary_name: String,
    constant_label: String,
    generated_number: Int?,
  ) -> T): Query<T>

  public fun selectMixedFromSourcesCommaSeparatedDynamically(): Query<SelectMixedFromSourcesCommaSeparated> = selectMixedFromSourcesCommaSeparatedDynamically(::SelectMixedFromSourcesCommaSeparated)

  /**
   * #238: a CTE selecting from another CTE; provenance must chase the bare column reference through
   * to the CTE that actually computed it.
   *
   * ```sql
   * WITH parent_label3 AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * ),
   * parent_label3_relay AS (
   *   SELECT id, name_upper FROM parent_label3
   * )
   * SELECT id, name_upper FROM parent_label3_relay
   * ```
   */
  public fun <T : Any> selectChainedCteProvenance(mapper: (id: UUID, name_upper: String) -> T): Many<T>

  /**
   * #238: a CTE selecting from another CTE; provenance must chase the bare column reference through
   * to the CTE that actually computed it.
   *
   * ```sql
   * WITH parent_label3 AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * ),
   * parent_label3_relay AS (
   *   SELECT id, name_upper FROM parent_label3
   * )
   * SELECT id, name_upper FROM parent_label3_relay
   * ```
   */
  public fun selectChainedCteProvenance(): Many<SelectChainedCteProvenance> = selectChainedCteProvenance(::SelectChainedCteProvenance)

  public fun <T : Any> selectChainedCteProvenanceDynamically(mapper: (id: UUID, name_upper: String) -> T): Query<T>

  public fun selectChainedCteProvenanceDynamically(): Query<SelectChainedCteProvenance> = selectChainedCteProvenanceDynamically(::SelectChainedCteProvenance)

  /**
   * #238: a nested WITH inside a CTE body shadows an outer CTE of the same name; provenance must
   * resolve against the inner, correctly scoped body, not the outer one. The literal `1 AS n` proves
   * the OUTER cte's own body position is also correctly attributed, independent of either "shadow_cte".
   *
   * ```sql
   * WITH shadow_cte AS (
   *   SELECT UPPER(name) AS ux FROM parent
   * ),
   * outer_consumer AS (
   *   WITH shadow_cte AS (
   *     SELECT LOWER(name) AS ux FROM parent
   *   )
   *   SELECT ux, 1 AS n FROM shadow_cte
   * )
   * SELECT ux, n FROM outer_consumer
   * ```
   */
  public fun <T : Any> selectNestedCteShadowingOuterName(mapper: (ux: String, n: Int) -> T): Many<T>

  /**
   * #238: a nested WITH inside a CTE body shadows an outer CTE of the same name; provenance must
   * resolve against the inner, correctly scoped body, not the outer one. The literal `1 AS n` proves
   * the OUTER cte's own body position is also correctly attributed, independent of either "shadow_cte".
   *
   * ```sql
   * WITH shadow_cte AS (
   *   SELECT UPPER(name) AS ux FROM parent
   * ),
   * outer_consumer AS (
   *   WITH shadow_cte AS (
   *     SELECT LOWER(name) AS ux FROM parent
   *   )
   *   SELECT ux, 1 AS n FROM shadow_cte
   * )
   * SELECT ux, n FROM outer_consumer
   * ```
   */
  public fun selectNestedCteShadowingOuterName(): Many<SelectNestedCteShadowingOuterName> = selectNestedCteShadowingOuterName(::SelectNestedCteShadowingOuterName)

  public fun <T : Any> selectNestedCteShadowingOuterNameDynamically(mapper: (ux: String, n: Int) -> T): Query<T>

  public fun selectNestedCteShadowingOuterNameDynamically(): Query<SelectNestedCteShadowingOuterName> = selectNestedCteShadowingOuterNameDynamically(::SelectNestedCteShadowingOuterName)

  /**
   * #238: UPDATE ... FROM cte ... RETURNING resolves the returned CTE column to its body position.
   *
   * ```sql
   * WITH desc_source AS (
   *   SELECT id, UPPER(description) AS description_upper FROM parent
   * )
   * UPDATE child SET name = desc_source.description_upper
   * FROM desc_source
   * WHERE child.parent_id = desc_source.id
   * RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS updated_description
   * ```
   */
  public fun <T : Any> updateChildNameFromParentDescriptionUpper(mapper: (source_parent_id: UUID, updated_description: String?) -> T): Many<T>

  /**
   * #238: UPDATE ... FROM cte ... RETURNING resolves the returned CTE column to its body position.
   *
   * ```sql
   * WITH desc_source AS (
   *   SELECT id, UPPER(description) AS description_upper FROM parent
   * )
   * UPDATE child SET name = desc_source.description_upper
   * FROM desc_source
   * WHERE child.parent_id = desc_source.id
   * RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS updated_description
   * ```
   */
  public fun updateChildNameFromParentDescriptionUpper(): Many<UpdateChildNameFromParentDescriptionUpper> = updateChildNameFromParentDescriptionUpper(::UpdateChildNameFromParentDescriptionUpper)

  public fun <T : Any> updateChildNameFromParentDescriptionUpperDynamically(mapper: (source_parent_id: UUID, updated_description: String?) -> T): Query<T>

  public fun updateChildNameFromParentDescriptionUpperDynamically(): Query<UpdateChildNameFromParentDescriptionUpper> = updateChildNameFromParentDescriptionUpperDynamically(::UpdateChildNameFromParentDescriptionUpper)

  /**
   * #238: DELETE ... USING cte ... RETURNING resolves the returned CTE column to its body position.
   *
   * ```sql
   * WITH desc_source AS (
   *   SELECT id, UPPER(description) AS description_upper FROM parent
   * )
   * DELETE FROM child USING desc_source
   * WHERE child.parent_id = desc_source.id
   * RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS deleted_description
   * ```
   */
  public fun <T : Any> deleteChildUsingParentDescriptionUpper(mapper: (source_parent_id: UUID, deleted_description: String?) -> T): Many<T>

  /**
   * #238: DELETE ... USING cte ... RETURNING resolves the returned CTE column to its body position.
   *
   * ```sql
   * WITH desc_source AS (
   *   SELECT id, UPPER(description) AS description_upper FROM parent
   * )
   * DELETE FROM child USING desc_source
   * WHERE child.parent_id = desc_source.id
   * RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS deleted_description
   * ```
   */
  public fun deleteChildUsingParentDescriptionUpper(): Many<DeleteChildUsingParentDescriptionUpper> = deleteChildUsingParentDescriptionUpper(::DeleteChildUsingParentDescriptionUpper)

  public fun <T : Any> deleteChildUsingParentDescriptionUpperDynamically(mapper: (source_parent_id: UUID, deleted_description: String?) -> T): Query<T>

  public fun deleteChildUsingParentDescriptionUpperDynamically(): Query<DeleteChildUsingParentDescriptionUpper> = deleteChildUsingParentDescriptionUpperDynamically(::DeleteChildUsingParentDescriptionUpper)

  /**
   * #238: INSERT ... SELECT ... FROM cte RETURNING; the RETURNING list resolves against the INSERT
   * target relation, never the feeding CTE, so this must document the ordinary columns, not the CTE.
   *
   * ```sql
   * WITH desc_source AS (
   *   SELECT id, UPPER(description) AS description_upper FROM parent
   * )
   * INSERT INTO child (parent_id, name)
   * SELECT id, description_upper FROM desc_source
   * RETURNING parent_id AS inserted_parent_id, name AS inserted_name
   * ```
   */
  public fun <T : Any> insertChildFromParentDescriptionUpper(mapper: (inserted_parent_id: UUID, inserted_name: String?) -> T): Many<T>

  /**
   * #238: INSERT ... SELECT ... FROM cte RETURNING; the RETURNING list resolves against the INSERT
   * target relation, never the feeding CTE, so this must document the ordinary columns, not the CTE.
   *
   * ```sql
   * WITH desc_source AS (
   *   SELECT id, UPPER(description) AS description_upper FROM parent
   * )
   * INSERT INTO child (parent_id, name)
   * SELECT id, description_upper FROM desc_source
   * RETURNING parent_id AS inserted_parent_id, name AS inserted_name
   * ```
   */
  public fun insertChildFromParentDescriptionUpper(): Many<InsertChildFromParentDescriptionUpper> = insertChildFromParentDescriptionUpper(::InsertChildFromParentDescriptionUpper)

  public fun <T : Any> insertChildFromParentDescriptionUpperDynamically(mapper: (inserted_parent_id: UUID, inserted_name: String?) -> T): Query<T>

  public fun insertChildFromParentDescriptionUpperDynamically(): Query<InsertChildFromParentDescriptionUpper> = insertChildFromParentDescriptionUpperDynamically(::InsertChildFromParentDescriptionUpper)

  /**
   * #238: MERGE ... RETURNING resolves the returned CTE column to its body position.
   *
   * ```sql
   * WITH desc_source AS (
   *   SELECT id, UPPER(description) AS description_upper FROM parent
   * )
   * MERGE INTO child USING desc_source ON child.parent_id = desc_source.id
   * WHEN MATCHED THEN UPDATE SET name = desc_source.description_upper
   * WHEN NOT MATCHED THEN INSERT (parent_id, name) VALUES (desc_source.id, desc_source.description_upper)
   * RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS merged_description
   * ```
   */
  public fun <T : Any> mergeChildFromParentDescriptionUpper(mapper: (source_parent_id: UUID?, merged_description: String?) -> T): Many<T>

  /**
   * #238: MERGE ... RETURNING resolves the returned CTE column to its body position.
   *
   * ```sql
   * WITH desc_source AS (
   *   SELECT id, UPPER(description) AS description_upper FROM parent
   * )
   * MERGE INTO child USING desc_source ON child.parent_id = desc_source.id
   * WHEN MATCHED THEN UPDATE SET name = desc_source.description_upper
   * WHEN NOT MATCHED THEN INSERT (parent_id, name) VALUES (desc_source.id, desc_source.description_upper)
   * RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS merged_description
   * ```
   */
  public fun mergeChildFromParentDescriptionUpper(): Many<MergeChildFromParentDescriptionUpper> = mergeChildFromParentDescriptionUpper(::MergeChildFromParentDescriptionUpper)

  public fun <T : Any> mergeChildFromParentDescriptionUpperDynamically(mapper: (source_parent_id: UUID?, merged_description: String?) -> T): Query<T>

  public fun mergeChildFromParentDescriptionUpperDynamically(): Query<MergeChildFromParentDescriptionUpper> = mergeChildFromParentDescriptionUpperDynamically(::MergeChildFromParentDescriptionUpper)

  /**
   * #238: a CTE with an explicit column list resolves positionally, independent of the renamed
   * column name.
   *
   * ```sql
   * WITH renamed_parent(parent_label, parent_id) AS (
   *   SELECT UPPER(name), id FROM parent
   * )
   * SELECT parent_label, parent_id FROM renamed_parent
   * ```
   */
  public fun <T : Any> selectParentViaCteWithExplicitColumnList(mapper: (parent_label: String, parent_id: UUID) -> T): Many<T>

  /**
   * #238: a CTE with an explicit column list resolves positionally, independent of the renamed
   * column name.
   *
   * ```sql
   * WITH renamed_parent(parent_label, parent_id) AS (
   *   SELECT UPPER(name), id FROM parent
   * )
   * SELECT parent_label, parent_id FROM renamed_parent
   * ```
   */
  public fun selectParentViaCteWithExplicitColumnList(): Many<SelectParentViaCteWithExplicitColumnList> = selectParentViaCteWithExplicitColumnList(::SelectParentViaCteWithExplicitColumnList)

  public fun <T : Any> selectParentViaCteWithExplicitColumnListDynamically(mapper: (parent_label: String, parent_id: UUID) -> T): Query<T>

  public fun selectParentViaCteWithExplicitColumnListDynamically(): Query<SelectParentViaCteWithExplicitColumnList> = selectParentViaCteWithExplicitColumnListDynamically(::SelectParentViaCteWithExplicitColumnList)

  /**
   * #238: WITH RECURSIVE resolves to nothing, since no single body position feeds every iteration.
   *
   * ```sql
   * WITH RECURSIVE parent_chain AS (
   *   SELECT id, name, 0 AS depth FROM parent
   *   UNION ALL
   *   SELECT p.id, p.name, parent_chain.depth + 1
   *   FROM parent p
   *   JOIN parent_chain ON p.id = parent_chain.id AND parent_chain.depth < 3
   * )
   * SELECT id, name, depth FROM parent_chain
   * ```
   */
  public fun <T : Any> countChildGenerationsRecursive(mapper: (
    id: UUID,
    name: String,
    depth: Int,
  ) -> T): Many<T>

  /**
   * #238: WITH RECURSIVE resolves to nothing, since no single body position feeds every iteration.
   *
   * ```sql
   * WITH RECURSIVE parent_chain AS (
   *   SELECT id, name, 0 AS depth FROM parent
   *   UNION ALL
   *   SELECT p.id, p.name, parent_chain.depth + 1
   *   FROM parent p
   *   JOIN parent_chain ON p.id = parent_chain.id AND parent_chain.depth < 3
   * )
   * SELECT id, name, depth FROM parent_chain
   * ```
   */
  public fun countChildGenerationsRecursive(): Many<CountChildGenerationsRecursive> = countChildGenerationsRecursive(::CountChildGenerationsRecursive)

  public fun <T : Any> countChildGenerationsRecursiveDynamically(mapper: (
    id: UUID,
    name: String,
    depth: Int,
  ) -> T): Query<T>

  public fun countChildGenerationsRecursiveDynamically(): Query<CountChildGenerationsRecursive> = countChildGenerationsRecursiveDynamically(::CountChildGenerationsRecursive)

  /**
   * #238: a CTE body with a top-level set operation resolves to nothing.
   *
   * ```sql
   * WITH combined_upper AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   *   UNION
   *   SELECT id, UPPER(name) FROM child
   * )
   * SELECT id, name_upper FROM combined_upper
   * ```
   */
  public fun <T : Any> selectUpperNameViaCteWithSetOperationBody(mapper: (id: UUID, name_upper: String) -> T): Many<T>

  /**
   * #238: a CTE body with a top-level set operation resolves to nothing.
   *
   * ```sql
   * WITH combined_upper AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   *   UNION
   *   SELECT id, UPPER(name) FROM child
   * )
   * SELECT id, name_upper FROM combined_upper
   * ```
   */
  public fun selectUpperNameViaCteWithSetOperationBody(): Many<SelectUpperNameViaCteWithSetOperationBody> = selectUpperNameViaCteWithSetOperationBody(::SelectUpperNameViaCteWithSetOperationBody)

  public fun <T : Any> selectUpperNameViaCteWithSetOperationBodyDynamically(mapper: (id: UUID, name_upper: String) -> T): Query<T>

  public fun selectUpperNameViaCteWithSetOperationBodyDynamically(): Query<SelectUpperNameViaCteWithSetOperationBody> = selectUpperNameViaCteWithSetOperationBodyDynamically(::SelectUpperNameViaCteWithSetOperationBody)

  /**
   * #238: a CTE body that is a bare `TABLE x`.
   *
   * ```sql
   * WITH all_parents AS (
   *   TABLE parent
   * )
   * SELECT id, name, description FROM all_parents
   * ```
   */
  public fun <T : Any> selectParentViaCteTable(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Many<T>

  /**
   * #238: a CTE body that is a bare `TABLE x`.
   *
   * ```sql
   * WITH all_parents AS (
   *   TABLE parent
   * )
   * SELECT id, name, description FROM all_parents
   * ```
   */
  public fun selectParentViaCteTable(): Many<Parent> = selectParentViaCteTable(::Parent)

  public fun <T : Any> selectParentViaCteTableDynamically(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Query<T>

  public fun selectParentViaCteTableDynamically(): Query<Parent> = selectParentViaCteTableDynamically(::Parent)

  /**
   * #238: a CTE body that is a bare `VALUES (...)`.
   *
   * ```sql
   * WITH constant_rows AS (
   *   VALUES (1, 'a'), (2, 'b')
   * )
   * SELECT column1, column2 FROM constant_rows
   * ```
   */
  public fun <T : Any> selectViaCteValues(mapper: (column1: Int?, column2: String?) -> T): Many<T>

  /**
   * #238: a CTE body that is a bare `VALUES (...)`.
   *
   * ```sql
   * WITH constant_rows AS (
   *   VALUES (1, 'a'), (2, 'b')
   * )
   * SELECT column1, column2 FROM constant_rows
   * ```
   */
  public fun selectViaCteValues(): Many<SelectViaCteValues> = selectViaCteValues(::SelectViaCteValues)

  public fun <T : Any> selectViaCteValuesDynamically(mapper: (column1: Int?, column2: String?) -> T): Query<T>

  public fun selectViaCteValuesDynamically(): Query<SelectViaCteValues> = selectViaCteValuesDynamically(::SelectViaCteValues)

  /**
   * #238: a CTE body wrapped in redundant parentheses still resolves normally.
   *
   * ```sql
   * WITH upper_name AS (
   *   (SELECT id, UPPER(name) AS name_upper FROM parent)
   * )
   * SELECT id, name_upper FROM upper_name
   * ```
   */
  public fun <T : Any> selectParentUpperNameViaParenthesizedCte(mapper: (id: UUID, name_upper: String) -> T): Many<T>

  /**
   * #238: a CTE body wrapped in redundant parentheses still resolves normally.
   *
   * ```sql
   * WITH upper_name AS (
   *   (SELECT id, UPPER(name) AS name_upper FROM parent)
   * )
   * SELECT id, name_upper FROM upper_name
   * ```
   */
  public fun selectParentUpperNameViaParenthesizedCte(): Many<SelectParentUpperNameViaParenthesizedCte> = selectParentUpperNameViaParenthesizedCte(::SelectParentUpperNameViaParenthesizedCte)

  public fun <T : Any> selectParentUpperNameViaParenthesizedCteDynamically(mapper: (id: UUID, name_upper: String) -> T): Query<T>

  public fun selectParentUpperNameViaParenthesizedCteDynamically(): Query<SelectParentUpperNameViaParenthesizedCte> = selectParentUpperNameViaParenthesizedCteDynamically(::SelectParentUpperNameViaParenthesizedCte)

  /**
   * #238: a top-level set operation in the main query must never document just the first branch's
   * expression, so this resolves to nothing.
   *
   * ```sql
   * WITH parent_names AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * )
   * SELECT id, name_upper FROM parent_names
   * UNION
   * SELECT id, UPPER(name) FROM child
   * ```
   */
  public fun <T : Any> selectUpperNameUnionAcrossTables(mapper: (id: UUID?, name_upper: String?) -> T): Many<T>

  /**
   * #238: a top-level set operation in the main query must never document just the first branch's
   * expression, so this resolves to nothing.
   *
   * ```sql
   * WITH parent_names AS (
   *   SELECT id, UPPER(name) AS name_upper FROM parent
   * )
   * SELECT id, name_upper FROM parent_names
   * UNION
   * SELECT id, UPPER(name) FROM child
   * ```
   */
  public fun selectUpperNameUnionAcrossTables(): Many<SelectUpperNameUnionAcrossTables> = selectUpperNameUnionAcrossTables(::SelectUpperNameUnionAcrossTables)

  public fun <T : Any> selectUpperNameUnionAcrossTablesDynamically(mapper: (id: UUID?, name_upper: String?) -> T): Query<T>

  public fun selectUpperNameUnionAcrossTablesDynamically(): Query<SelectUpperNameUnionAcrossTables> = selectUpperNameUnionAcrossTablesDynamically(::SelectUpperNameUnionAcrossTables)

  /**
   * #238: `SELECT *` at the OUTER level, expanding a CTE's own explicit column list.
   *
   * ```sql
   * WITH all_cols AS (
   *   SELECT id, name, description FROM parent
   * )
   * SELECT * FROM all_cols
   * ```
   */
  public fun <T : Any> selectAllColumnsOuterFromCte(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Many<T>

  /**
   * #238: `SELECT *` at the OUTER level, expanding a CTE's own explicit column list.
   *
   * ```sql
   * WITH all_cols AS (
   *   SELECT id, name, description FROM parent
   * )
   * SELECT * FROM all_cols
   * ```
   */
  public fun selectAllColumnsOuterFromCte(): Many<Parent> = selectAllColumnsOuterFromCte(::Parent)

  public fun <T : Any> selectAllColumnsOuterFromCteDynamically(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Query<T>

  public fun selectAllColumnsOuterFromCteDynamically(): Query<Parent> = selectAllColumnsOuterFromCteDynamically(::Parent)

  /**
   * #238: `SELECT *` at the INNER (CTE body) level.
   *
   * ```sql
   * WITH all_cols AS (
   *   SELECT * FROM parent
   * )
   * SELECT id, name, description FROM all_cols
   * ```
   */
  public fun <T : Any> selectAllColumnsInnerCte(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Many<T>

  /**
   * #238: `SELECT *` at the INNER (CTE body) level.
   *
   * ```sql
   * WITH all_cols AS (
   *   SELECT * FROM parent
   * )
   * SELECT id, name, description FROM all_cols
   * ```
   */
  public fun selectAllColumnsInnerCte(): Many<Parent> = selectAllColumnsInnerCte(::Parent)

  public fun <T : Any> selectAllColumnsInnerCteDynamically(mapper: (
    id: UUID,
    name: String,
    description: String?,
  ) -> T): Query<T>

  public fun selectAllColumnsInnerCteDynamically(): Query<Parent> = selectAllColumnsInnerCteDynamically(::Parent)

  /**
   * #238: a CTE body item with an implicit (no `AS`) alias.
   *
   * ```sql
   * WITH upper_name AS (
   *   SELECT id, UPPER(name) y FROM parent
   * )
   * SELECT id, y FROM upper_name
   * ```
   */
  public fun <T : Any> selectParentUpperNameImplicitAlias(mapper: (id: UUID, y: String) -> T): Many<T>

  /**
   * #238: a CTE body item with an implicit (no `AS`) alias.
   *
   * ```sql
   * WITH upper_name AS (
   *   SELECT id, UPPER(name) y FROM parent
   * )
   * SELECT id, y FROM upper_name
   * ```
   */
  public fun selectParentUpperNameImplicitAlias(): Many<SelectParentUpperNameImplicitAlias> = selectParentUpperNameImplicitAlias(::SelectParentUpperNameImplicitAlias)

  public fun <T : Any> selectParentUpperNameImplicitAliasDynamically(mapper: (id: UUID, y: String) -> T): Query<T>

  public fun selectParentUpperNameImplicitAliasDynamically(): Query<SelectParentUpperNameImplicitAlias> = selectParentUpperNameImplicitAliasDynamically(::SelectParentUpperNameImplicitAlias)

  /**
   * #238: a CTE name with escaped embedded double quotes, resolved by its real, unescaped name, and
   * a quoted, mixed-case, space-containing output column.
   *
   * ```sql
   * WITH "He""llo" AS (
   *   SELECT id, UPPER(name) AS "My Col" FROM parent
   * )
   * SELECT id, "My Col" FROM "He""llo"
   * ```
   */
  public fun <T : Any> selectViaQuotedCteNameWithEmbeddedQuotes(mapper: (id: UUID, `My Col`: String) -> T): Many<T>

  /**
   * #238: a CTE name with escaped embedded double quotes, resolved by its real, unescaped name, and
   * a quoted, mixed-case, space-containing output column.
   *
   * ```sql
   * WITH "He""llo" AS (
   *   SELECT id, UPPER(name) AS "My Col" FROM parent
   * )
   * SELECT id, "My Col" FROM "He""llo"
   * ```
   */
  public fun selectViaQuotedCteNameWithEmbeddedQuotes(): Many<SelectViaQuotedCteNameWithEmbeddedQuotes> = selectViaQuotedCteNameWithEmbeddedQuotes(::SelectViaQuotedCteNameWithEmbeddedQuotes)

  public fun <T : Any> selectViaQuotedCteNameWithEmbeddedQuotesDynamically(mapper: (id: UUID, `My Col`: String) -> T): Query<T>

  public fun selectViaQuotedCteNameWithEmbeddedQuotesDynamically(): Query<SelectViaQuotedCteNameWithEmbeddedQuotes> = selectViaQuotedCteNameWithEmbeddedQuotesDynamically(::SelectViaQuotedCteNameWithEmbeddedQuotes)
}
