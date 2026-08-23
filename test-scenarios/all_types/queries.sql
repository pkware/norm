-- name: all :many
SELECT * FROM type;

-- name: single :one
SELECT string_type FROM type;

-- name: insertOne :execrows
INSERT INTO type(string_type) VALUES (?);

-- name: insertMultiple :execrows
INSERT INTO type(string_type, int_type) VALUES (?, ?);

-- name: updateAllStrings :execrows
UPDATE type SET string_type = ? WHERE string_type IS NOT NULL;

-- name: deleteAll :execrows
DELETE FROM type;

-- name: deleteById :execrows
DELETE FROM type WHERE serial_type = ?;

-- Resets the type table to its initial state.
-- name: resetTypes :exec
CALL reset_type_table();

-- Updates string_type for the given row via a stored procedure.
-- name: updateStringType :exec
CALL update_string_type(?, ?);

-- name: filterByStringType :many
SELECT * FROM type WHERE string_type = ?;

-- name: listNotNullView :many
SELECT * FROM not_null_view;

-- Returns aggregate summary statistics for the given string_type.
-- name: getTypeSummary :one
SELECT * FROM type_summary WHERE string_type = ?;

-- Lists each department alongside an employee's name and nickname, which are `null` when the
-- department has no employees.
-- name: departmentEmployees :many
SELECT d.id, d.name AS dept_name, e.name AS employee_name, e.nickname
FROM department d
LEFT JOIN employee e ON e.department_id = d.id;

-- Lists all department and employee names together.
-- name: allNames :many
SELECT name FROM department
UNION ALL
SELECT name FROM employee;

-- Updates both string_type and text_type for a given row.
-- name: updateBothStrings :execrows
UPDATE type SET string_type = :string_type, text_type = :string_type WHERE serial_type = :serial_type;

-- Finds a row where string_type and text_type both equal the given value.
-- name: findByMatchingStrings :one
SELECT * FROM type WHERE string_type = :value AND text_type = :value;

-- Lists rows where string_type and text_type both equal the given value.
-- name: filterByMatchingStrings :many
SELECT * FROM type WHERE string_type = :value AND text_type = :value;

-- Updates the jsonb_type column for a given row.
-- name: updateJsonb :execrows
UPDATE type SET jsonb_type = ? WHERE string_type = ?;

-- Updates the json_type column for a given row.
-- name: updateJson :execrows
UPDATE type SET json_type = ? WHERE string_type = ?;

-- Updates the int and text array columns for a given row.
-- name: updateIntTextArrays :execrows
UPDATE type SET
  int_array_type = ?,
  int_array_notnull_type = ?,
  text_array_type = ?,
  text_array_notnull_type = ?
WHERE string_type = ?;

-- name: updateNumericArrays :execrows
UPDATE type SET
  int2_array_type = ?,
  int2_array_notnull_type = ?,
  int8_array_type = ?,
  int8_array_notnull_type = ?,
  float4_array_type = ?,
  float4_array_notnull_type = ?,
  float8_array_type = ?,
  float8_array_notnull_type = ?,
  numeric_array_type = ?,
  numeric_array_notnull_type = ?
WHERE string_type = ?;

-- name: updateDateTimeArrays :execrows
UPDATE type SET
  date_array_type = ?,
  date_array_notnull_type = ?,
  time_array_type = ?,
  time_array_notnull_type = ?,
  timetz_array_type = ?,
  timetz_array_notnull_type = ?,
  timestamp_array_type = ?,
  timestamp_array_notnull_type = ?,
  timestamptz_array_type = ?,
  timestamptz_array_notnull_type = ?
WHERE string_type = ?;

-- name: updateOtherArrays :execrows
UPDATE type SET
  bool_array_type = ?,
  bool_array_notnull_type = ?,
  json_array_type = ?,
  json_array_notnull_type = ?,
  jsonb_array_type = ?,
  jsonb_array_notnull_type = ?,
  bytea_array_type = ?,
  bytea_array_notnull_type = ?,
  uuid_array_type = ?,
  uuid_array_notnull_type = ?
WHERE string_type = ?;

-- Updates the oid array columns for a given row.
-- name: updateOidArray :execrows
UPDATE type SET
  oid_array_type = ?,
  oid_array_notnull_type = ?
WHERE string_type = ?;
