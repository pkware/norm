-- Star projection against a partitioned table.
-- name: getEventById :one
SELECT * FROM event WHERE id = ? AND created_at = ?;

-- name: listEventsByCategory :many
SELECT id, created_at, category FROM event WHERE category = ? ORDER BY created_at DESC;

-- name: addEvent :exec
INSERT INTO event (category, payload) VALUES (?, ?::jsonb);
