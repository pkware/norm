-- name: getUserById :one
SELECT * FROM users WHERE id = ?;

-- name: createUser :exec
INSERT INTO users (email, age, current_mood, metadata, preferences)
VALUES (?, ?, ?, ?, ?);

-- name: updatePastMoods :exec
UPDATE users SET past_moods = ?, tag_list = ? WHERE id = ?;
