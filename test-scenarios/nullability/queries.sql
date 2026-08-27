-- name: findClaimingRuns :many
SELECT DISTINCT claimed_by_workflow_id, claimed_by_run_id
FROM prefix_enumeration_backlog
WHERE deployment_id = :deploymentId
  AND claimed
  AND completed_at IS NULL
  AND claimed_by_workflow_id IS NOT NULL
  AND claimed_by_run_id IS NOT NULL
LIMIT :runLimit;

-- Returns headcount per department name; name is `null` on the ROLLUP subtotal row.
-- name: departmentHeadcountByRollup :many
SELECT name, COUNT(*) AS headcount
FROM department
GROUP BY ROLLUP (name);

-- Returns lowercased department names; name_lower is `null` on the ROLLUP subtotal row.
-- name: departmentNameLowerByRollup :many
SELECT lower(name) AS name_lower
FROM department
GROUP BY ROLLUP (lower(name));

-- Lists employees by department; full_name is `null` when a department has no employees.
-- name: employeesByDepartment :many
SELECT department.id, employee.full_name
FROM department
LEFT JOIN employee ON employee.department_id = department.id;

-- Lists employees whose full_name is not `null`; the type stays nullable, since Norm doesn't narrow across outer joins.
-- name: employeesByDepartmentFiltered :many
SELECT employee.full_name
FROM department
LEFT JOIN employee ON employee.department_id = department.id
WHERE employee.full_name IS NOT NULL;

-- Returns non-`null` widget codes; the WHERE clause proves code non-null even though the column allows `null`.
-- name: widgetByLowerCode :many
SELECT code
FROM widget
WHERE lower(code) = :code;

-- Clears an account's note; the returned note is always `null`, reflecting the value just set.
-- name: clearAccountNote :many
UPDATE account
SET note = NULL
WHERE note IS NOT NULL
RETURNING note;

-- Reports whether a department's name matches any employee's full name, read through a derived
-- table rather than directly from employee; non-`null` because full_name is NOT NULL.
-- name: departmentNameMatchesAnyEmployeeViaDerivedTable :many
SELECT department.name = ANY (SELECT names.full_name FROM (SELECT full_name FROM employee) names) AS name_matches
FROM department;

-- Reports whether a department's name matches any employee's full name, read through an
-- enclosing CTE rather than directly from employee; non-`null` because full_name is NOT NULL.
-- name: departmentNameMatchesAnyEmployeeViaCte :many
WITH employee_names AS (SELECT full_name FROM employee)
SELECT department.name = ANY (SELECT full_name FROM employee_names) AS name_matches
FROM department;
