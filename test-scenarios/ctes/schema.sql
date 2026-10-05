-- Parent/child tables with a foreign key and a unique constraint, used by a chain of
-- data-modifying CTEs where a later CTE references an earlier one, plus a trailing data-modifying
-- CTE with no RETURNING clause.
-- description is nullable, so a data-modifying CTE's RETURNING alias reads a nullable column.
-- The golden output marks that column nullable.
CREATE TABLE parent (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  description TEXT
);

CREATE TABLE child (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  parent_id UUID NOT NULL REFERENCES parent(id),
  name TEXT NOT NULL,
  UNIQUE (parent_id, name)
);

-- View over child, used by the comma-separated mixed-FROM-list scenario (a CTE, a table, a
-- view, a derived table, and a set-returning function all in the same FROM clause).
CREATE VIEW child_summary AS
  SELECT id, parent_id, name FROM child;
