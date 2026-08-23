-- name: getComplexBook :one
-- Returns a book's id, title, isbn, and publication year, with its author embedded as a nested object.
SELECT
  b.id,
  b.title,
  sqlc.embed(author),
  b.isbn,
  b.published_year
FROM book b
JOIN author ON b.author_id = author.id
WHERE b.id = ?;

-- name: getSandwichBook :one
-- Returns a book's title, isbn, page count, and publication year, with its publisher embedded as a nested object.
SELECT
  b.title,
  b.isbn,
  sqlc.embed(publisher),
  b.page_count,
  b.published_year
FROM book b
JOIN publisher ON b.publisher_id = publisher.id
WHERE b.id = ?;

-- name: getAlternatingBook :one
-- Returns a book's title, isbn, and publication year, with its author and publisher each embedded as nested objects.
SELECT
  b.title,
  sqlc.embed(author),
  b.isbn,
  sqlc.embed(publisher),
  b.published_year
FROM book b
JOIN author ON b.author_id = author.id
JOIN publisher ON b.publisher_id = publisher.id
WHERE b.id = ?;
