-- name: getUserById :one
SELECT * FROM users WHERE id = ?;

-- name: createUser :exec
INSERT INTO users (email, age, current_mood, metadata, preferences)
VALUES (?, ?, ?, ?, ?);

-- name: updatePastMoods :exec
UPDATE users SET past_moods = ?, tag_list = ? WHERE id = ?;

-- name: getDocumentById :one
SELECT * FROM documents WHERE id = ?;

-- name: createDocument :exec
INSERT INTO documents (payload, doc) VALUES (?, ?);

-- name: updatePreferences :many
UPDATE users SET preferences = ? WHERE id = ?
RETURNING id, preferences AS old_preferences;

-- Duplicates a user's row, returning the new row's id and preferences under an alias.
-- name: duplicateUserReturningAliasedPreferences :many
INSERT INTO users (email, age, current_mood, metadata, preferences)
SELECT email, age, current_mood, metadata, preferences FROM users WHERE id = ?
RETURNING id, preferences AS duplicated_preferences;
