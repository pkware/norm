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
