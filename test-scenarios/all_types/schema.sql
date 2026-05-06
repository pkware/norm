CREATE TABLE type (
  smallserial_type smallserial,
  serial2_type smallserial NOT NULL,
  pg_serial2_type smallserial,
  serial_type serial,
  serial4_type serial NOT NULL,
  pg_serial4_type serial,
  bigserial_type bigserial,
  serial8_type bigserial NOT NULL,
  pg_serial8_type bigserial,
  smallint_type SMALLINT,
  int2_type smallint NOT NULL,
  pg_int2_type smallint,
  integer_type INTEGER,
  int_type INT,
  int4_type integer NOT NULL,
  pg_int4_type integer,
  bigint_type bigint,
  int8_type bigint NOT NULL,
  pg_int8_type bigint,
  real_type REAL,
  float4_type real NOT NULL,
  pg_float4_type real,
  float_type FLOAT,
  double_type DOUBLE PRECISION,
  float8_type double precision NOT NULL,
  pg_float8_type double precision,
  numeric_type NUMERIC,
  pg_numeric_type NUMERIC,
  bool_type bool,
  pg_bool_type bool,
  jsonb_type jsonb,
  blob_type oid,
  text_type text,
  varchar_type VARCHAR,
  pg_varchar_type VARCHAR,
  bpchar_type bpchar,
  pg_bpchar_type bpchar,
  string_type text NOT NULL,
  date_type date,
  date_notnull_type date NOT NULL,
  pg_date_type date,
  time_type time,
  time_notnull_type time NOT NULL,
  pg_time_type time,
  timetz_type timetz,
  timetz_notnull_type timetz NOT NULL,
  pg_timetz_type timetz,
  timestamp_type timestamp,
  timestamp_notnull_type timestamp NOT NULL,
  pg_timestamp_type timestamp,
  timestamptz_type timestamptz,
  timestamptz_notnull_type timestamptz NOT NULL,
  pg_timestamptz_type timestamptz,
  uuid_type uuid,
  uuid_notnull_type uuid NOT NULL,
  pg_uuid_type uuid,
  bytea_type bytea,
  bytea_notnull_type bytea NOT NULL,
  pg_bytea_type bytea,
  int_array_type int4[],
  int_array_notnull_type int4[] NOT NULL,
  text_array_type text[],
  text_array_notnull_type text[] NOT NULL
);

-- View for testing that query result nullability is inferred from the underlying table columns.
CREATE VIEW not_null_view AS
  SELECT serial_type, string_type, int4_type FROM type;

COMMENT ON VIEW not_null_view IS 'Non-nullable columns from the type table.';

-- Materialized view for testing nullability inference.
CREATE MATERIALIZED VIEW not_null_materialized_view AS
  SELECT serial_type, string_type, text_type FROM type;

COMMENT ON MATERIALIZED VIEW not_null_materialized_view IS 'Mix of nullable and non-nullable columns.';

-- Materialized view with computed columns (aggregates are nullable).
CREATE MATERIALIZED VIEW type_summary AS
  SELECT
    string_type,
    COUNT(*) AS row_count,
    AVG(int4_type)::INTEGER AS average_value
  FROM type
  GROUP BY string_type;

COMMENT ON MATERIALIZED VIEW type_summary IS 'Aggregated statistics per string_type value.';

-- Stored procedure for :exec testing (no parameters)
CREATE OR REPLACE PROCEDURE reset_type_table()
LANGUAGE SQL
AS $$
  DELETE FROM type;
$$;

-- Stored procedure for :exec testing (with parameters)
CREATE OR REPLACE PROCEDURE update_string_type(p_id INT, p_new_value TEXT)
LANGUAGE SQL
AS $$
  UPDATE type SET string_type = p_new_value WHERE serial_type = p_id;
$$;

-- Tables for JOIN nullability testing.
CREATE TABLE department (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE employee (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  department_id INT NOT NULL REFERENCES department(id),
  nickname TEXT
);

CREATE TABLE project (
  id SERIAL PRIMARY KEY,
  title TEXT NOT NULL,
  lead_employee_id INT REFERENCES employee(id)
);
