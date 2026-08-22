-- Custom domain types that require database catalog introspection
CREATE DOMAIN email AS TEXT
CHECK (VALUE ~ '^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$');

CREATE DOMAIN positive_integer AS INTEGER
CHECK (VALUE > 0);

CREATE DOMAIN us_postal_code AS TEXT
CHECK (VALUE ~ '^\d{5}(-\d{4})?$');

-- Domains over every other base type Norm supports as a plain column type, each exercised below
-- on both a result column and a query parameter.
CREATE DOMAIN order_placed_at AS TIMESTAMPTZ;

CREATE DOMAIN external_reference AS UUID;

CREATE DOMAIN preferred_date AS DATE;

CREATE DOMAIN opening_time AS TIME;

CREATE DOMAIN meeting_time_tz AS TIMETZ;

CREATE DOMAIN created_at_local AS TIMESTAMP;

CREATE DOMAIN large_object_ref AS OID;

CREATE DOMAIN thumbnail AS BYTEA;

-- Custom enum types that require adapter generation
CREATE TYPE mood AS ENUM ('happy', 'sad', 'angry');

COMMENT ON TYPE mood IS 'Represents the emotional state of a person. Used in the users table to track current and historical moods.';


CREATE TABLE users (
  id SERIAL PRIMARY KEY,
  email email NOT NULL,
  age positive_integer,
  zip_code us_postal_code,
  current_mood mood NOT NULL,
  previous_mood mood,
  past_moods mood[],
  scores positive_integer[],
  placed_at order_placed_at NOT NULL,
  external_ref external_reference,
  preferred_date preferred_date,
  opening_time opening_time,
  meeting_time_tz meeting_time_tz,
  created_at_local created_at_local,
  large_object_ref large_object_ref,
  thumbnail thumbnail
);
