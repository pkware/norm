-- name: getTwoConsecutiveEmbeds :one
-- Returns a book's author and publisher, each embedded as a nested object.
SELECT
  sqlc.embed(author),
  sqlc.embed(publisher)
FROM book b
JOIN author ON b.author_id = author.id
JOIN publisher ON b.publisher_id = publisher.id
WHERE b.id = ?;

-- name: getThreeConsecutiveEmbeds :one
-- Returns a book's author, publisher, and reviewer, each embedded as a nested object.
SELECT
  sqlc.embed(author),
  sqlc.embed(publisher),
  sqlc.embed(reviewer)
FROM book b
JOIN author ON b.author_id = author.id
JOIN publisher ON b.publisher_id = publisher.id
JOIN reviewer ON b.reviewer_id = reviewer.id
WHERE b.id = ?;

-- name: getEmbedRegularEmbed :one
-- Returns a book's title, with its author and publisher each embedded as a nested object.
SELECT
  sqlc.embed(author),
  b.title,
  sqlc.embed(publisher)
FROM book b
JOIN author ON b.author_id = author.id
JOIN publisher ON b.publisher_id = publisher.id
WHERE b.id = ?;
