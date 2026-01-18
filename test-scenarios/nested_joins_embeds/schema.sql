CREATE TABLE author (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT NOT NULL,
  bio TEXT
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
  page_count INT NOT NULL,
  price DECIMAL(10, 2) NOT NULL,
  in_stock BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE review (
  id SERIAL PRIMARY KEY,
  book_id INT NOT NULL REFERENCES book(id),
  reviewer_name TEXT NOT NULL,
  rating INT NOT NULL,
  review_text TEXT,
  review_date DATE NOT NULL
);
