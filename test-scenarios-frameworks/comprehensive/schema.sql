CREATE TABLE author (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT
);

CREATE TABLE book (
  id SERIAL PRIMARY KEY,
  title TEXT NOT NULL,
  author_id INT NOT NULL REFERENCES author(id) ON DELETE CASCADE
);

-- Orphan table: not referenced in any query
CREATE TABLE publisher (
  id SERIAL PRIMARY KEY,
  company_name TEXT NOT NULL
);

-- Adapted types: enum, domains, and a user-configured type mapping for jsonb
CREATE TYPE mood AS ENUM ('happy', 'sad', 'angry');

CREATE DOMAIN email_address AS TEXT
CHECK (VALUE ~ '^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$');

CREATE TABLE person (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  contact_email email_address NOT NULL,
  current_mood mood NOT NULL,
  bio jsonb
);
