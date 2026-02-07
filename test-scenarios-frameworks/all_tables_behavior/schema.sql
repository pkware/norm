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
