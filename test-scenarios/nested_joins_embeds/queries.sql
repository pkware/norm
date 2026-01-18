-- name: getBookDetails :one
-- Real-world complex query: 10+ columns with multiple embeds
-- This simulates a realistic join-heavy query with mixed regular and embed columns
-- Expected indices:
--   1: b.title
--   2: b.isbn
--   3-6: author (id, name, email, bio) - 4 columns
--   7: b.published_year
--   8-10: publisher (id, company_name, country) - 3 columns
--   11: b.page_count
--   12: b.price
-- BUG HYPOTHESIS: Catastrophic failures in middle columns - published_year, page_count, price
-- likely to have wrong indices after multiple embeds
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
WHERE b.id = $1;

-- name: listBooksWithFullDetails :many
-- Similar complex pattern for :many queries
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
-- Mix of embeds and aggregates
SELECT
  sqlc.embed(author),
  b.title,
  COUNT(r.id)::int AS review_count
FROM book b
JOIN author ON b.author_id = author.id
LEFT JOIN review r ON r.book_id = b.id
WHERE b.id = $1
GROUP BY author.id, author.name, author.email, author.bio, b.title;
