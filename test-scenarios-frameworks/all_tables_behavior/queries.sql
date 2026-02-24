-- name: getAuthor :one
SELECT * FROM author WHERE id = ?;

-- name: getBook :one
SELECT * FROM book WHERE id = ?;
-- Note: publisher is NOT queried
