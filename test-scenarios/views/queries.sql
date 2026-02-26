-- Query against a view.
-- name: listActiveEmployees :many
SELECT * FROM active_employee ORDER BY name;

-- Query against a materialized view.
-- name: getDepartmentSummary :one
SELECT * FROM department_summary WHERE department = ?;

-- Query against the base table still works alongside views.
-- name: getEmployeeById :one
SELECT * FROM employee WHERE id = ?;
