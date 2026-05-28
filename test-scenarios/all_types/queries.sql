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

-- Execrows without parameters.
-- name: deleteAll :execrows
DELETE FROM type;

-- Execrows with 1 parameter.
-- name: deleteById :execrows
DELETE FROM type WHERE serial_type = ?;

-- Exec without parameters.
-- name: resetTypes :exec
CALL reset_type_table();

-- Exec with parameters.
-- name: updateStringType :exec
CALL update_string_type(?, ?);

-- :many with a parameter: verify params flow through the Many code path.
-- name: filterByStringType :many
SELECT * FROM type WHERE string_type = ?;

-- Query against a view (pass-through columns preserve nullability from base table).
-- name: listNotNullView :many
SELECT * FROM not_null_view;

-- Query against a materialized view with computed columns (aggregates are nullable).
-- name: getTypeSummary :one
SELECT * FROM type_summary WHERE string_type = ?;

-- LEFT JOIN: right side NOT NULL columns become nullable (#58).
-- name: departmentEmployees :many
SELECT d.id, d.name AS dept_name, e.name AS employee_name, e.nickname
FROM department d
LEFT JOIN employee e ON e.department_id = d.id;

-- UNION ALL: node tree has no VAR at top level, nullability from JDBC metadata.
-- name: allNames :many
SELECT name FROM department
UNION ALL
SELECT name FROM employee;

-- Reused named parameter in :execrows — exercises batch body codegen.
-- name: updateBothStrings :execrows
UPDATE type SET string_type = :string_type, text_type = :string_type WHERE serial_type = :serial_type;

-- Reused named parameter in :one — exercises buildOne body codegen.
-- name: findByMatchingStrings :one
SELECT * FROM type WHERE string_type = :value AND text_type = :value;

-- Reused named parameter in :many — exercises queryBinder body codegen.
-- name: filterByMatchingStrings :many
SELECT * FROM type WHERE string_type = :value AND text_type = :value;
