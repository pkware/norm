CREATE TABLE prefix_enumeration_backlog (
  id BIGINT PRIMARY KEY,
  deployment_id UUID NOT NULL,
  claimed BOOLEAN NOT NULL,
  completed_at TIMESTAMPTZ,
  claimed_by_workflow_id TEXT,
  claimed_by_run_id TEXT
);

CREATE TABLE department (
  id BIGINT PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE employee (
  id BIGINT PRIMARY KEY,
  department_id BIGINT NOT NULL,
  full_name TEXT NOT NULL
);

CREATE TABLE widget (
  id BIGINT PRIMARY KEY,
  code TEXT
);

CREATE TABLE account (
  id BIGINT PRIMARY KEY,
  note TEXT
);
