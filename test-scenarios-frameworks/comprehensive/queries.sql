-- name: getAuthor :one
SELECT * FROM author WHERE id = ?;

-- name: getBook :one
SELECT * FROM book WHERE id = ?;

-- name: addAuthor :exec
INSERT INTO author(name, email) VALUES (?, ?);
-- Note: publisher is NOT queried

-- name: getPersonById :one
SELECT * FROM person WHERE id = ?;

-- name: createPerson :exec
INSERT INTO person (name, contact_email, current_mood, bio) VALUES (?, ?, ?, ?);
