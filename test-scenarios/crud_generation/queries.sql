-- User-defined query with the same name as a synthetic one (should take priority)
-- name: findAllAuthor :many
SELECT id, name FROM author ORDER BY name;

-- A normal user query that doesn't conflict
-- name: getAuthorByName :one
SELECT * FROM author WHERE name = ?;
