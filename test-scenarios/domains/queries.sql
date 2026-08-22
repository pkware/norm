-- name: getUserByEmail :one
SELECT * FROM users WHERE email = ?;

-- name: listUsersByAge :many
SELECT * FROM users WHERE age > ?;

-- name: getUsersByZipCode :many
SELECT * FROM users WHERE zip_code = ?;

-- name: getUsersByMood :many
SELECT * FROM users WHERE current_mood = ?;

-- name: getUsersPlacedAfter :many
SELECT * FROM users WHERE placed_at > ?;

-- name: getUserByExternalReference :one
SELECT * FROM users WHERE external_ref = ?;

-- name: getUsersByPreferredDate :many
SELECT * FROM users WHERE preferred_date = ?;

-- name: getUsersByOpeningTime :many
SELECT * FROM users WHERE opening_time = ?;

-- name: getUsersByMeetingTimeTz :many
SELECT * FROM users WHERE meeting_time_tz = ?;

-- name: getUsersByCreatedAtLocal :many
SELECT * FROM users WHERE created_at_local = ?;

-- name: getUsersByLargeObjectRef :many
SELECT * FROM users WHERE large_object_ref = ?;

-- name: getUsersByThumbnail :many
SELECT * FROM users WHERE thumbnail = ?;

-- name: createUser :exec
INSERT INTO users (email, age, zip_code, current_mood, previous_mood)
VALUES (?, ?, ?, ?, ?);

-- name: updateUser :exec
UPDATE users
SET
  email = coalesce(:email, users.email),
  age = coalesce(:age, users.age),
  zip_code = coalesce(:zipCode, users.zip_code)
WHERE id = :id;

-- name: updateMood :exec
UPDATE users
SET
  current_mood = ?,
  previous_mood = ?
WHERE id = ?;

-- name: updateArrayColumns :exec
UPDATE users
SET
  past_moods = ?,
  scores = ?
WHERE id = ?;
