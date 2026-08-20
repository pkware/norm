-- Reproduces #202: a chained data-modifying CTE ("new_child") references an earlier
-- data-modifying CTE ("new_parent"), and a trailing CTE ("audit_entry") is data-modifying
-- with no RETURNING clause at all.
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

-- Reproduces #203: a data-modifying CTE ("new_parent") references a CTE declared LATER
-- in the same WITH RECURSIVE clause ("parent_name").
-- name: createParentFromLaterCte :many
WITH RECURSIVE new_parent AS (
  INSERT INTO parent (name) SELECT label FROM parent_name RETURNING id, name
),
parent_name AS (
  SELECT 'seeded'::TEXT AS label
)
SELECT id, name FROM new_parent;

-- Reproduces #205: a CTE body ("parent_and_child") starts with its own nested WITH clause
-- ("helper") and contains a LEFT JOIN, alongside a sibling data-modifying CTE ("new_parent").
-- The nested WITH must not cause the LEFT JOIN's nullability to be lost — child_name is
-- nullable (child may not exist for a parent), parent_name stays NOT NULL.
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

-- Reproduces #204: a data-modifying CTE ("new_parent") RETURNs a nullable column
-- ("description") under a quoted, mixed-case alias, referenced by the outer query with the
-- same quoting. Before the fix, PgCatalogLoader's stub emitted the alias unquoted, letting
-- PostgreSQL fold it to lowercase and causing the outer reference to fail to resolve
-- ("column new_parent.parentDescription does not exist") when creating the temporary view
-- used for nullability analysis. That failure is caught and silently degrades to asserting
-- every column of "new_parent" NOT NULL — so "parentDescription", genuinely nullable, would
-- be wrongly reported NOT NULL. description is deliberately used (rather than "name", which
-- is NOT NULL and would pass either way, masking the bug) so this scenario actually fails
-- pre-fix instead of coincidentally matching.
-- name: createParentReturningQuotedAlias :many
WITH new_parent AS (
  INSERT INTO parent (name) VALUES (?) RETURNING id AS "parentId", description AS "parentDescription"
)
SELECT new_parent."parentId", new_parent."parentDescription" FROM new_parent;

-- Reproduces #226: a top-level DELETE's RETURNING list computes an expression
-- ("UPPER(description)") over a nullable column. Before the fix,
-- PgCatalogLoader.analyzeUnconvertibleDml unconditionally treated
-- ResultSetMetaData.columnNullableUnknown as NOT NULL for this shape, wrongly reporting
-- description_upper NOT NULL even though description has no NOT NULL constraint.
-- name: deleteParentReturningDescriptionUpper :many
DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper;

-- CTE-wrapped form of the same RETURNING expression as the query above, demonstrating that the
-- top-level probe path (#226) and the existing CTE-body stub path agree on description_upper's
-- nullability.
-- name: deleteParentReturningDescriptionUpperViaCte :many
WITH deleted_parent AS (
  DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
)
SELECT id, name, description_upper FROM deleted_parent;
