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
