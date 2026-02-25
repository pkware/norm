-- Tests that partitioned tables (relkind='p') are properly discovered by JDBC metadata.
-- PostgreSQL reports partitioned parents as "PARTITIONED TABLE", not "TABLE".

CREATE TABLE event (
  id UUID NOT NULL DEFAULT gen_random_uuid(),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  category TEXT NOT NULL,
  payload JSONB,

  PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

COMMENT ON TABLE event IS 'Partitioned event log.';
COMMENT ON COLUMN event.id IS 'Unique identifier for the event.';
COMMENT ON COLUMN event.created_at IS 'When the event occurred. Used as partition key.';
COMMENT ON COLUMN event.category IS 'Event category.';
COMMENT ON COLUMN event.payload IS 'Event payload. Null when the event carries no extra data.';

CREATE TABLE event_2026 PARTITION OF event
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
