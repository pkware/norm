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

-- Reproduces #212: an INSERT ... SELECT ... RETURNING statement whose RETURNING clause aliases
-- "preferences", a column with a configured column-level type override (users.preferences ->
-- com.example.UserPreferences). Before the fix, parseSelectItems read the INSERT's own SELECT
-- source list instead of the RETURNING clause, so the RETURNING alias's second item borrowed
-- "age" as its originalName instead of "preferences" — missing the (users, preferences) override
-- key entirely and falling back to the type-level jsonb override (com.example.JsonData) instead.
-- name: duplicateUserReturningAliasedPreferences :many
INSERT INTO users (email, age, current_mood, metadata, preferences)
SELECT email, age, current_mood, metadata, preferences FROM users WHERE id = ?
RETURNING id, preferences AS duplicated_preferences;
