-- name: getUserByEmail :one
SELECT * FROM users WHERE email = ?;

-- name: listUsersByAge :many
SELECT * FROM users WHERE age > ?;

-- name: getUsersByZipCode :many
SELECT * FROM users WHERE zip_code = ?;

-- name: createUser :exec
INSERT INTO users (email, age, zip_code)
VALUES (?, ?, ?);

-- name: updateUser :exec
UPDATE users
SET
  email = coalesce(:email, users.email),
  age = coalesce(:age, users.age),
  zip_code = coalesce(:zipCode, users.zip_code)
WHERE id = :id;
