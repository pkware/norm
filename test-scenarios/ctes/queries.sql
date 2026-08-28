-- Creates a parent, a child of that parent, and an audit entry for the child.
-- name: createParentWithChildAndAuditEntry :many
WITH new_parent AS (
  INSERT INTO parent (name) VALUES (?) RETURNING id, name
),
new_child AS (
  INSERT INTO child (parent_id, name)
  SELECT id, ? FROM new_parent
  RETURNING id, parent_id, name
),
audit_entry AS (
  INSERT INTO child (parent_id, name)
  SELECT parent_id, name || '_audit' FROM new_child
  ON CONFLICT (parent_id, name) DO NOTHING
)
SELECT id, parent_id, name FROM new_child;

-- Creates a parent whose name is seeded from another CTE in the same statement.
-- name: createParentFromLaterCte :many
WITH RECURSIVE new_parent AS (
  INSERT INTO parent (name) SELECT label FROM parent_name RETURNING id, name
),
parent_name AS (
  SELECT 'seeded'::TEXT AS label
)
SELECT id, name FROM new_parent;

-- Lists parents with their child's name, if any; child_name is `null` when a parent has no child.
-- name: listParentsWithOptionalChildAlongsideInsert :many
WITH parent_and_child AS (
  WITH helper AS (SELECT 1)
  SELECT parent.id AS parent_id, parent.name AS parent_name, child.name AS child_name
  FROM parent LEFT JOIN child ON child.parent_id = parent.id
),
new_parent AS (
  INSERT INTO parent (name) VALUES ('placeholder') RETURNING id
)
SELECT parent_id, parent_name, child_name FROM parent_and_child;

-- Creates a parent, returning its id and description under quoted, mixed-case aliases.
-- name: createParentReturningQuotedAlias :many
WITH new_parent AS (
  INSERT INTO parent (name) VALUES (?) RETURNING id AS "parentId", description AS "parentDescription"
)
SELECT new_parent."parentId", new_parent."parentDescription" FROM new_parent;

-- Deletes a parent, returning its name and uppercased description.
-- name: deleteParentReturningDescriptionUpper :many
DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper;

-- CTE-wrapped form of deleteParentReturningDescriptionUpper above.
-- name: deleteParentReturningDescriptionUpperViaCte :many
WITH deleted_parent AS (
  DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
)
SELECT id, name, description_upper FROM deleted_parent;

-- Updates a parent's description to a fixed value, returning its name and uppercased description.
-- name: updateParentReturningDescriptionUpper :many
UPDATE parent SET description = 'UPDATED' WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper;

-- Quoted-alias variant of deleteParentReturningDescriptionUpperViaCte above.
-- name: deleteParentReturningQuotedDescriptionUpperViaCte :many
WITH deleted_parent AS (
  DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS "descriptionUpper"
)
SELECT id, name, "descriptionUpper" FROM deleted_parent;

-- #238: two independently declared CTEs, each referenced by its own real name.
-- name: selectUpperNamesFromTwoCtes :many
WITH parent_upper AS (
  SELECT id, UPPER(name) AS name_upper FROM parent
),
child_upper AS (
  SELECT id, UPPER(name) AS name_upper FROM child
)
SELECT parent_upper.name_upper AS parent_name_upper, child_upper.name_upper AS child_name_upper
FROM parent_upper, child_upper;

-- #238: sibling CTEs that both name their output column the same thing must still resolve
-- independently, not to whichever sibling a text scan happens to see first.
-- name: selectSiblingCtesWithSameOutputName :many
WITH parent_same AS (
  SELECT UPPER(name) AS same FROM parent
),
child_same AS (
  SELECT UPPER(name) AS same FROM child
)
SELECT parent_same.same AS parent_same, child_same.same AS child_same
FROM parent_same, child_same;

-- #238: a CTE addressed through an explicit `AS x` FROM alias, referenced by the alias.
-- name: selectCteViaExplicitFromAliasAs :many
WITH parent_upper2 AS (
  SELECT id, UPPER(name) AS name_upper FROM parent
)
SELECT x.id AS parent_id, x.name_upper AS aliased_name_upper FROM parent_upper2 AS x;

-- #238: a CTE addressed through an implicit (no `AS`) FROM alias, referenced by the alias.
-- name: selectCteViaImplicitFromAlias :many
WITH parent_upper3 AS (
  SELECT id, UPPER(name) AS name_upper FROM parent
)
SELECT x.id AS parent_id, x.name_upper AS aliased_name_upper FROM parent_upper3 x;

-- #238: two CTEs joined with an explicit ON predicate.
-- name: selectCtesJoinedOnPredicate :many
WITH parent_upper4 AS (
  SELECT id, UPPER(name) AS name_upper FROM parent
),
child_upper4 AS (
  SELECT parent_id, UPPER(name) AS name_upper FROM child
)
SELECT parent_upper4.name_upper AS parent_name_upper, child_upper4.name_upper AS child_name_upper
FROM parent_upper4 JOIN child_upper4 ON parent_upper4.id = child_upper4.parent_id;

-- #238: an INNER JOIN USING merged column between two CTEs; PostgreSQL aliases it directly to the
-- left side, so this DOES resolve. child_own_name is a bare, non-merged column from the right side,
-- included only to force a generated data class to inspect.
-- name: selectCtesInnerJoinUsingMergedColumn :many
WITH parent_label AS (
  SELECT UPPER(name) AS shared_label FROM parent
),
child_label AS (
  SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
)
SELECT shared_label, child_own_name FROM parent_label JOIN child_label USING (shared_label);

-- #238: a FULL JOIN USING merged column between two CTEs is `COALESCE(left, right)`, so it must
-- resolve to nothing rather than attributing it to either side.
-- name: selectCtesFullJoinUsingMergedColumn :many
WITH parent_label AS (
  SELECT UPPER(name) AS shared_label FROM parent
),
child_label AS (
  SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
)
SELECT shared_label, child_own_name FROM parent_label FULL JOIN child_label USING (shared_label);

-- #238: a plain (inner) NATURAL JOIN merged column between two CTEs; like INNER JOIN USING, this
-- DOES resolve.
-- name: selectCtesNaturalJoin :many
WITH parent_label AS (
  SELECT UPPER(name) AS shared_label FROM parent
),
child_label AS (
  SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
)
SELECT shared_label, child_own_name FROM parent_label NATURAL JOIN child_label;

-- #238: a NATURAL FULL JOIN merged column between two CTEs is the same COALESCE case as
-- selectCtesFullJoinUsingMergedColumn above, so it must resolve to nothing.
-- name: selectCtesNaturalFullJoin :many
WITH parent_label AS (
  SELECT UPPER(name) AS shared_label FROM parent
),
child_label AS (
  SELECT UPPER(name) AS shared_label, name AS child_own_name FROM child
)
SELECT shared_label, child_own_name FROM parent_label NATURAL FULL JOIN child_label;

-- #238: a comma-separated FROM list mixing a CTE, an ordinary table, a view, a derived table, and
-- a set-returning function; only the CTE-derived column should document its expression.
-- name: selectMixedFromSourcesCommaSeparated :many
WITH parent_label2 AS (
  SELECT id, UPPER(name) AS name_upper FROM parent
)
SELECT
  parent_label2.name_upper AS parent_name_upper,
  child.name AS child_name,
  child_summary.name AS summary_name,
  derived.constant_label,
  generated.generated_number
FROM parent_label2, child, child_summary, (SELECT 'literal'::TEXT AS constant_label) derived,
  generate_series(1, 3) AS generated(generated_number)
WHERE child.parent_id = parent_label2.id AND child_summary.id = child.id;

-- #238: a CTE selecting from another CTE; provenance must chase the bare column reference through
-- to the CTE that actually computed it.
-- name: selectChainedCteProvenance :many
WITH parent_label3 AS (
  SELECT id, UPPER(name) AS name_upper FROM parent
),
parent_label3_relay AS (
  SELECT id, name_upper FROM parent_label3
)
SELECT id, name_upper FROM parent_label3_relay;

-- #238: a nested WITH inside a CTE body shadows an outer CTE of the same name; provenance must
-- resolve against the inner, correctly scoped body, not the outer one. The literal `1 AS n` proves
-- the OUTER cte's own body position is also correctly attributed, independent of either "shadow_cte".
-- name: selectNestedCteShadowingOuterName :many
WITH shadow_cte AS (
  SELECT UPPER(name) AS ux FROM parent
),
outer_consumer AS (
  WITH shadow_cte AS (
    SELECT LOWER(name) AS ux FROM parent
  )
  SELECT ux, 1 AS n FROM shadow_cte
)
SELECT ux, n FROM outer_consumer;

-- #238: UPDATE ... FROM cte ... RETURNING resolves the returned CTE column to its body position.
-- name: updateChildNameFromParentDescriptionUpper :many
WITH desc_source AS (
  SELECT id, UPPER(description) AS description_upper FROM parent
)
UPDATE child SET name = desc_source.description_upper
FROM desc_source
WHERE child.parent_id = desc_source.id
RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS updated_description;

-- #238: DELETE ... USING cte ... RETURNING resolves the returned CTE column to its body position.
-- name: deleteChildUsingParentDescriptionUpper :many
WITH desc_source AS (
  SELECT id, UPPER(description) AS description_upper FROM parent
)
DELETE FROM child USING desc_source
WHERE child.parent_id = desc_source.id
RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS deleted_description;

-- #238: INSERT ... SELECT ... FROM cte RETURNING; the RETURNING list resolves against the INSERT
-- target relation, never the feeding CTE, so this must document the ordinary columns, not the CTE.
-- name: insertChildFromParentDescriptionUpper :many
WITH desc_source AS (
  SELECT id, UPPER(description) AS description_upper FROM parent
)
INSERT INTO child (parent_id, name)
SELECT id, description_upper FROM desc_source
RETURNING parent_id AS inserted_parent_id, name AS inserted_name;

-- #238: an explicit column list is resolved positionally against the body's own resname, not the
-- renamed column name -- but "parent_label" itself still resolves to nothing here, since its body
-- item ("UPPER(name)", with no AS and no implicit alias token at all) has no verifiable name to
-- cross-validate the position against. "parent_id" (a bare, unaliased "id") IS verifiable by its
-- own name, and resolves correctly to "parent.id".
-- name: selectParentViaCteWithExplicitColumnList :many
WITH renamed_parent(parent_label, parent_id) AS (
  SELECT UPPER(name), id FROM parent
)
SELECT parent_label, parent_id FROM renamed_parent;

-- #238: WITH RECURSIVE resolves to nothing, since no single body position feeds every iteration.
-- name: countChildGenerationsRecursive :many
WITH RECURSIVE parent_chain AS (
  SELECT id, name, 0 AS depth FROM parent
  UNION ALL
  SELECT p.id, p.name, parent_chain.depth + 1
  FROM parent p
  JOIN parent_chain ON p.id = parent_chain.id AND parent_chain.depth < 3
)
SELECT id, name, depth FROM parent_chain;

-- #238: a CTE body with a top-level set operation resolves to nothing.
-- name: selectUpperNameViaCteWithSetOperationBody :many
WITH combined_upper AS (
  SELECT id, UPPER(name) AS name_upper FROM parent
  UNION
  SELECT id, UPPER(name) FROM child
)
SELECT id, name_upper FROM combined_upper;

-- #238: a CTE body that is a bare `TABLE x`. `TABLE x` cannot itself carry a computed expression,
-- so the derived column that pins provenance is computed in the main query instead, over a column
-- passed straight through from the CTE.
-- name: selectParentViaCteTable :many
WITH all_parents AS (
  TABLE parent
)
SELECT id, UPPER(name) AS name_upper, description FROM all_parents;

-- #238: a CTE body that is a bare `VALUES (...)`.
-- name: selectViaCteValues :many
WITH constant_rows AS (
  VALUES (1, 'a'), (2, 'b')
)
SELECT column1, column2 FROM constant_rows;

-- #238: a CTE body wrapped in redundant parentheses still resolves normally.
-- name: selectParentUpperNameViaParenthesizedCte :many
WITH upper_name AS (
  (SELECT id, UPPER(name) AS name_upper FROM parent)
)
SELECT id, name_upper FROM upper_name;

-- #238: a top-level set operation in the main query must never document just the first branch's
-- expression, so this resolves to nothing.
-- name: selectUpperNameUnionAcrossTables :many
WITH parent_names AS (
  SELECT id, UPPER(name) AS name_upper FROM parent
)
SELECT id, name_upper FROM parent_names
UNION
SELECT id, UPPER(name) FROM child;

-- #238: `SELECT *` at the OUTER level, expanding a CTE's own explicit column list -- including one
-- that is itself a computed expression, so the star-expanded result gets its own type with a
-- pinned `@property` line instead of aliasing `Parent` and pinning nothing.
-- name: selectAllColumnsOuterFromCte :many
WITH all_cols AS (
  SELECT id, name, description, UPPER(name) AS name_upper FROM parent
)
SELECT * FROM all_cols;

-- #238: `SELECT *` at the INNER (CTE body) level. A star sharing a body with any other item -- even
-- a computed one -- is deliberately UNRESOLVABLE text-side (parseOutputItemsWithAlias's own star
-- truncation guard drops the star and everything after it, so the item count can never match the
-- node tree's), so the derived column that pins provenance is computed in the main query instead,
-- over a column passed straight through the inner star.
-- name: selectAllColumnsInnerCte :many
WITH all_cols AS (
  SELECT * FROM parent
)
SELECT id, UPPER(name) AS name_upper, description FROM all_cols;

-- #238: a CTE body item with an implicit (no `AS`) alias.
-- name: selectParentUpperNameImplicitAlias :many
WITH upper_name AS (
  SELECT id, UPPER(name) y FROM parent
)
SELECT id, y FROM upper_name;

-- #238: a CTE name with escaped embedded double quotes, resolved by its real, unescaped name, and
-- a quoted, mixed-case, space-containing output column.
-- name: selectViaQuotedCteNameWithEmbeddedQuotes :many
WITH "He""llo" AS (
  SELECT id, UPPER(name) AS "My Col" FROM parent
)
SELECT id, "My Col" FROM "He""llo";
