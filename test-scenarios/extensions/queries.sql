-- Creates a user with a hashed password.
-- name: createUser :exec
INSERT INTO user_credentials (username, password_hash, nullable_password_hash)
VALUES (?, crypt(?, gen_salt('bf')), crypt(?, gen_salt('bf')));

-- Returns whether the given username and password match a stored credential.
-- name: verifyPassword :one
SELECT EXISTS(
  SELECT 1 FROM user_credentials
  WHERE username = ?
  AND password_hash = crypt(?, password_hash)
) AS valid;

-- Lists a user's settings as key-value pairs.
-- name: getUserSettings :many
SELECT setting_key, setting_value
FROM user_settings
WHERE user_id = ?;

-- name: setSetting :exec
INSERT INTO user_settings (user_id, setting_key, setting_value)
VALUES (?, ?, ?)
ON CONFLICT (user_id, setting_key)
DO UPDATE SET setting_value = EXCLUDED.setting_value;

-- Computes a cryptographic digest of the given data.
-- name: computeDigest :one
SELECT digest(?, ?) AS hash;

-- Computes an HMAC signature of the given data.
-- name: computeHmac :one
SELECT hmac(?, ?, ?) AS signature;

-- Computes a cryptographic digest of the given data and hex-encodes it.
-- name: computeEncodedHash :one
SELECT encode(digest(?, ?), ?) AS encoded_hash;

-- Decodes the given encoded data.
-- name: decodeData :one
SELECT decode(?, ?) AS decoded;

-- Generates normally distributed random numbers.
-- name: generateRandomNumbers :many
SELECT * FROM normal_rand(?, ?, ?);

-- Pivots a user's settings into named columns.
-- name: getUserSettingsPivot :many
SELECT user_id, setting1, setting2
FROM crosstab(?) AS ct(user_id int, setting1 text, setting2 text);

-- Pivots settings by category into named columns.
-- name: getUserSettingsByCategory :many
SELECT row_name, category1, category2, category3
FROM crosstab(?, ?) AS ct(row_name text, category1 int, category2 int, category3 int);
