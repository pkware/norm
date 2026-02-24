-- name: getUserByEmail :one
SELECT * FROM users WHERE email = $1;

-- name: listUsersByAge :many
SELECT * FROM users WHERE age > $1;

-- name: getUsersByZipCode :many
SELECT * FROM users WHERE zip_code = $1;

-- name: createUser :exec
INSERT INTO users (email, age, zip_code)
VALUES ($1, $2, $3);

-- name: updateUser :exec
UPDATE users
SET
  email = coalesce(:email, users.email),
  age = coalesce(:age, users.age),
  zip_code = coalesce(:zipCode, users.zip_code)
WHERE id = :id;
