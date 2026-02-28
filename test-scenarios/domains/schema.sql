-- Custom domain types that require database catalog introspection
CREATE DOMAIN email AS TEXT
CHECK (VALUE ~ '^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$');

CREATE DOMAIN positive_integer AS INTEGER
CHECK (VALUE > 0);

CREATE DOMAIN us_postal_code AS TEXT
CHECK (VALUE ~ '^\d{5}(-\d{4})?$');

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
  scores positive_integer[]
);
