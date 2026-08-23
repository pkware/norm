-- Lists authors by name, overriding the synthesized CRUD query of the same name.
-- name: findAllAuthor :many
SELECT id, name FROM author ORDER BY name;

-- Returns an author by name.
-- name: getAuthorByName :one
SELECT * FROM author WHERE name = ?;
