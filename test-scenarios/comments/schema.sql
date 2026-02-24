-- Tests that COMMENT ON TABLE and COMMENT ON COLUMN propagate to generated Kotlin KDoc.

CREATE TABLE author (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  bio TEXT
);

COMMENT ON TABLE author IS 'A person who writes books.';
COMMENT ON COLUMN author.id IS 'Unique identifier for the author.';
COMMENT ON COLUMN author.name IS 'Full name of the author.';
COMMENT ON COLUMN author.bio IS 'Short biography. Null if not provided.';

CREATE TABLE book (
  id SERIAL PRIMARY KEY,
  title TEXT NOT NULL,
  author_id INTEGER NOT NULL REFERENCES author(id),
  published_year INTEGER,
  isbn TEXT
);

COMMENT ON TABLE book IS 'A published book in the catalog.';
COMMENT ON COLUMN book.id IS 'Unique identifier for the book.';
COMMENT ON COLUMN book.title IS 'Title of the book.';
COMMENT ON COLUMN book.author_id IS 'Foreign key to the author who wrote the book.';
COMMENT ON COLUMN book.published_year IS 'Year the book was published. Null if unknown.';
-- isbn intentionally has no comment to test mixed scenarios.
