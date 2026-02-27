CREATE TYPE author_status AS ENUM ('active', 'inactive', 'suspended');

CREATE TABLE author (
  id INT NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name VARCHAR NOT NULL,
  email VARCHAR,
  revision INT NOT NULL DEFAULT 1,
  status author_status NOT NULL DEFAULT 'active'
);

CREATE TABLE book (
  id INT NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title VARCHAR NOT NULL,
  author_id INT NOT NULL REFERENCES author(id),
  first_published TIMESTAMP,
  copies_sold INT NOT NULL DEFAULT 0
);

CREATE PROCEDURE assign_books_to_author(
    p_author_id INT,
    p_book_ids INT[]
)
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE book
  SET author_id = p_author_id
  WHERE id = ANY (p_book_ids);
END;
$$;

CREATE TABLE Publisher (
  id INT NOT NULL GENERATED ALWAYS AS IDENTITY,
  name VARCHAR NOT NULL,
  PRIMARY KEY (id, name)
);
