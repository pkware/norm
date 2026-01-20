-- name: getAuthor :one
SELECT * FROM author WHERE id = $1;

-- name: getBook :one
SELECT * FROM book WHERE id = $1;
-- Note: publisher is NOT queried
