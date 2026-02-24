-- name: getUserByEmail :one
SELECT * FROM users WHERE email = $1;

-- name: listUsersByAge :many
SELECT * FROM users WHERE age > $1;

-- name: getUsersByZipCode :many
SELECT * FROM users WHERE zip_code = $1;

-- name: createUser :exec
INSERT INTO users (email, age, zip_code)
VALUES ($1, $2, $3);
