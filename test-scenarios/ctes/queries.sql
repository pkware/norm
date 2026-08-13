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
