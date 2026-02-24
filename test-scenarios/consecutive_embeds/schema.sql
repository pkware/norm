CREATE TABLE author (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE publisher (
  id SERIAL PRIMARY KEY,
  company_name TEXT NOT NULL,
  country TEXT NOT NULL
);

CREATE TABLE reviewer (
  id SERIAL PRIMARY KEY,
  reviewer_name TEXT NOT NULL
);

CREATE TABLE book (
  id SERIAL PRIMARY KEY,
  title TEXT NOT NULL,
  author_id INT NOT NULL REFERENCES author(id),
  publisher_id INT NOT NULL REFERENCES publisher(id),
  reviewer_id INT REFERENCES reviewer(id)
);
