CREATE TABLE prefix_enumeration_backlog (
  id BIGINT PRIMARY KEY,
  deployment_id UUID NOT NULL,
  claimed BOOLEAN NOT NULL,
  completed_at TIMESTAMPTZ,
  claimed_by_workflow_id TEXT,
  claimed_by_run_id TEXT
);
