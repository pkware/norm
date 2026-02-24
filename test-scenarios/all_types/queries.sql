-- name: all :many
SELECT * FROM type;

-- name: single :one
SELECT string_type FROM type;

-- name: insertOne :execrows
INSERT INTO type(string_type) VALUES ($1);

-- name: insertMultiple :execrows
INSERT INTO type(string_type, int_type) VALUES ($1, $2);

-- name: updateAllStrings :execrows
UPDATE type SET string_type = $1 WHERE string_type IS NOT NULL;

-- Execrows without parameters.
-- name: deleteAll :execrows
DELETE FROM type;

-- Execrows with 1 parameter.
-- name: deleteById :execrows
DELETE FROM type WHERE serial_type = $1;

-- Exec without parameters.
-- name: resetTypes :exec
CALL reset_type_table();

-- Exec with parameters.
-- name: updateStringType :exec
CALL update_string_type($1, $2);
