-- name: getAuthor :one
SELECT * FROM author WHERE id = ?;

-- name: getBook :one
SELECT * FROM book WHERE id = ?;

-- name: addAuthor :exec
INSERT INTO author(name, email) VALUES (?, ?);
-- Note: publisher is NOT queried
