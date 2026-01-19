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

-- Test: Simple digest function with 2 parameters returning bytea
-- Function: digest(data text, algorithm text) → bytea
-- Expected: Parameters (String, String), Return ByteArray
-- name: computeDigest :one
SELECT digest($1, $2) AS hash;

-- Test: HMAC function with 3 parameters (text, bytea, text) returning bytea
-- Function: hmac(data text, key bytea, algorithm text) → bytea
-- Expected: Parameters (String, ByteArray, String), Return ByteArray
-- name: computeHmac :one
SELECT hmac($1, $2, $3) AS signature;

-- Test: Nested function calls - encode(digest(...))
-- Functions: digest(text, text) → bytea, encode(bytea, text) → text
-- Expected: Parameters (String, String, String), Return String
-- name: computeEncodedHash :one
SELECT encode(digest($1, $2), $3) AS encoded_hash;

-- Test: decode function - reverse of encode
-- Function: decode(data text, format text) → bytea
-- Expected: Parameters (String, String), Return ByteArray
-- name: decodeData :one
SELECT decode($1, $2) AS decoded;

-- Test: Set-returning function normal_rand with 3 numeric parameters
-- Function: normal_rand(num_rows int, mean float8, stddev float8) → setof float8
-- Expected: Parameters (Int, Double, Double), Return Many<Double>
-- name: generateRandomNumbers :many
SELECT * FROM normal_rand($1, $2, $3);

-- Test: crosstab with single parameter and explicit column definitions
-- Function: crosstab(sql text) → setof record
-- Expected: Parameters (String), Return Many with structured result
-- name: getUserSettingsPivot :many
SELECT user_id, setting1, setting2
FROM crosstab($1) AS ct(user_id int, setting1 text, setting2 text);

-- Test: crosstab with 2 parameters - source and category SQLs
-- Function: crosstab(source_sql text, category_sql text) → setof record
-- Expected: Parameters (String, String), Return Many with structured result
-- name: getUserSettingsByCategory :many
SELECT row_name, category1, category2, category3
FROM crosstab($1, $2) AS ct(row_name text, category1 int, category2 int, category3 int);
