-- Parent/child tables with a foreign key and a unique constraint, used to reproduce #202:
-- a chain of data-modifying CTEs where a later CTE references an earlier one, plus a
-- trailing data-modifying CTE with no RETURNING clause.
CREATE TABLE parent (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL
);

CREATE TABLE child (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  parent_id UUID NOT NULL REFERENCES parent(id),
  name TEXT NOT NULL,
  UNIQUE (parent_id, name)
);
