-- Parent/child tables with a foreign key and a unique constraint, used to reproduce #202:
-- a chain of data-modifying CTEs where a later CTE references an earlier one, plus a
-- trailing data-modifying CTE with no RETURNING clause.
-- description is nullable, used to reproduce #204: a data-modifying CTE's RETURNING alias
-- must round-trip through a genuinely nullable column, or a pre-fix bug whose fallback
-- fabricates every column NOT NULL would coincidentally produce the same golden output.
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

-- View over child, used by #238's comma-separated mixed-FROM-list scenario (a CTE, a table, a
-- view, a derived table, and a set-returning function all in the same FROM clause).
CREATE VIEW child_summary AS
  SELECT id, parent_id, name FROM child;
