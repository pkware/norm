CREATE TABLE author (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE publisher (
  id SERIAL PRIMARY KEY,
  company_name TEXT NOT NULL,
  country TEXT NOT NULL
);

CREATE TABLE book (
  id SERIAL PRIMARY KEY,
  title TEXT NOT NULL,
  isbn TEXT NOT NULL,
  author_id INT NOT NULL REFERENCES author(id),
  publisher_id INT NOT NULL REFERENCES publisher(id),
  published_year INT NOT NULL,
  page_count INT NOT NULL
);
