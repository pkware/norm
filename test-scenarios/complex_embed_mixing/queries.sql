-- name: getComplexBook :one
-- THE CRITICAL TEST: regular, embed, regular pattern
-- Expected indices:
--   1: b.id
--   2: b.title
--   3-4: author (id, name)
--   5: b.isbn
--   6: b.published_year
-- BUG HYPOTHESIS: After the embed, isbn and published_year may use wrong indices
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
-- Sandwich pattern: regular columns on both sides of 3-column embed
-- Expected indices:
--   1: b.title
--   2: b.isbn
--   3-5: publisher (id, company_name, country)
--   6: b.page_count
--   7: b.published_year
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
-- Multiple embeds with regular columns between
-- Expected indices:
--   1: b.title
--   2-3: author (id, name)
--   4: b.isbn
--   5-7: publisher (id, company_name, country)
--   8: b.published_year
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
