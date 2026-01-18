-- name: getTwoConsecutiveEmbeds :one
-- Two consecutive embeds
-- Expected indices:
--   1-2: author (id, name)
--   3-5: publisher (id, company_name, country)
SELECT
  sqlc.embed(author),
  sqlc.embed(publisher)
FROM book b
JOIN author ON b.author_id = author.id
JOIN publisher ON b.publisher_id = publisher.id
WHERE b.id = $1;

-- name: getThreeConsecutiveEmbeds :one
-- Three consecutive embeds - tests cumulative offset errors
-- Expected indices:
--   1-2: author (id, name)
--   3-5: publisher (id, company_name, country)
--   6-7: reviewer (id, reviewer_name)
-- BUG HYPOTHESIS: Second and third embeds may start at wrong indices
SELECT
  sqlc.embed(author),
  sqlc.embed(publisher),
  sqlc.embed(reviewer)
FROM book b
JOIN author ON b.author_id = author.id
JOIN publisher ON b.publisher_id = publisher.id
JOIN reviewer ON b.reviewer_id = reviewer.id
WHERE b.id = $1;

-- name: getEmbedRegularEmbed :one
-- Embed, regular, embed pattern
-- Expected indices:
--   1-2: author (id, name)
--   3: b.title
--   4-6: publisher (id, company_name, country)
SELECT
  sqlc.embed(author),
  b.title,
  sqlc.embed(publisher)
FROM book b
JOIN author ON b.author_id = author.id
JOIN publisher ON b.publisher_id = publisher.id
WHERE b.id = $1;
