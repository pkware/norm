-- Lists authors by name, overriding the synthesized CRUD query of the same name.
-- name: findAllAuthor :many
SELECT id, name FROM author ORDER BY name;

-- Returns an author by name.
-- name: getAuthorByName :one
SELECT * FROM author WHERE name = ?;

-- Tests that pgjdbc's `??` escape for the jsonb `?` operator is not counted as a parameter placeholder.
-- name: findDocumentByKeyInRange :many
SELECT id, title FROM document WHERE metadata ?? 'key' AND id >= ? AND id < ?;

-- name: findDocumentByKeyAndTitle :many
SELECT id FROM document WHERE metadata ?? :key AND title = :title;
