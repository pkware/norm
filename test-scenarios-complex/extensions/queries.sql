-- Query using pgcrypto for password hashing
-- name: createUser :exec
INSERT INTO user_credentials (username, password_hash)
VALUES ($1, crypt($2, gen_salt('bf')));

-- Query using pgcrypto for password verification
-- name: verifyPassword :one
SELECT EXISTS(
  SELECT 1 FROM user_credentials
  WHERE username = $1
  AND password_hash = crypt($2, password_hash)
) AS valid;

-- Query using settings table (for potential tablefunc pivot)
-- name: getUserSettings :many
SELECT setting_key, setting_value
FROM user_settings
WHERE user_id = $1;

-- name: setSetting :exec
INSERT INTO user_settings (user_id, setting_key, setting_value)
VALUES ($1, $2, $3)
ON CONFLICT (user_id, setting_key)
DO UPDATE SET setting_value = EXCLUDED.setting_value;
