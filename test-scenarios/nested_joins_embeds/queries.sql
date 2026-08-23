-- name: getBookDetails :one
-- Returns a book's details, with its author and publisher each embedded as a nested object.
SELECT
  b.title,
  b.isbn,
  sqlc.embed(author),
  b.published_year,
  sqlc.embed(publisher),
  b.page_count,
  b.price
FROM book b
JOIN author ON b.author_id = author.id
JOIN publisher ON b.publisher_id = publisher.id
WHERE b.id = ?;

-- name: listBooksWithFullDetails :many
-- Lists books with their author and publisher each embedded as a nested object, newest first.
SELECT
  b.id,
  sqlc.embed(author),
  b.title,
  b.isbn,
  sqlc.embed(publisher),
  b.published_year,
  b.in_stock
FROM book b
JOIN author ON b.author_id = author.id
JOIN publisher ON b.publisher_id = publisher.id
ORDER BY b.published_year DESC;

-- name: getBookWithReviewCount :one
-- Returns a book's title and review count, with its author embedded as a nested object.
SELECT
  sqlc.embed(author),
  b.title,
  COUNT(r.id)::int AS review_count
FROM book b
JOIN author ON b.author_id = author.id
LEFT JOIN review r ON r.book_id = b.id
WHERE b.id = ?
GROUP BY author.id, author.name, author.email, author.bio, b.title;
