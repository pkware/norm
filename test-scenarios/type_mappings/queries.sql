-- name: getUserById :one
SELECT * FROM users WHERE id = ?;

-- name: createUser :exec
INSERT INTO users (email, age, current_mood, metadata, preferences)
VALUES (?, ?, ?, ?, ?);
