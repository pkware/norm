-- Domains that should remain auto-generated (not overridden)
CREATE DOMAIN email AS TEXT
CHECK (VALUE ~ '^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$');

CREATE DOMAIN positive_integer AS INTEGER
CHECK (VALUE > 0);

-- Enum type that will be overridden by a user type mapping
CREATE TYPE mood AS ENUM ('happy', 'sad', 'angry');

CREATE TABLE users (
  id SERIAL PRIMARY KEY,
  email email NOT NULL,
  age positive_integer,
  current_mood mood NOT NULL,
  metadata jsonb NOT NULL,
  preferences jsonb NOT NULL,
  past_moods mood[],   -- nullable array; same type-level override applies (mood → CustomMood)
  tag_list jsonb[]     -- nullable array; same type-level override applies (jsonb → JsonData)
);
