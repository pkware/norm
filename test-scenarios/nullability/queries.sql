-- name: findClaimingRuns :many
SELECT DISTINCT claimed_by_workflow_id, claimed_by_run_id
FROM prefix_enumeration_backlog
WHERE deployment_id = :deploymentId
  AND claimed
  AND completed_at IS NULL
  AND claimed_by_workflow_id IS NOT NULL
  AND claimed_by_run_id IS NOT NULL
LIMIT :runLimit;

-- Rule: ROLLUP null-extends a grouping key that is NOT NULL in the schema — name must be
-- reported nullable even though department.name carries a NOT NULL constraint.
-- name: departmentHeadcountByRollup :many
SELECT name, COUNT(*) AS headcount
FROM department
GROUP BY ROLLUP (name);

-- Rule: an expression grouping key (#236) is null-extended too — ROLLUP(lower(name)) nulls
-- out the re-evaluated lower(name) in the target list, not just a bare grouping Var.
-- name: departmentNameLowerByRollup :many
SELECT lower(name) AS name_lower
FROM department
GROUP BY ROLLUP (lower(name));

-- Rule: a NOT NULL column from the optional side of a LEFT JOIN is nullable — full_name can
-- be null-extended when a department has no matching employee.
-- name: employeesByDepartment :many
SELECT department.id, employee.full_name
FROM department
LEFT JOIN employee ON employee.department_id = department.id;

-- Rule: the outer-join nullability check runs first and Norm does not narrow it — full_name
-- is reported nullable even though the WHERE clause means no null-extended row can actually
-- survive. This pins deliberate analyzer conservatism, not a semantic necessity.
-- name: employeesByDepartmentFiltered :many
SELECT employee.full_name
FROM department
LEFT JOIN employee ON employee.department_id = department.id
WHERE employee.full_name IS NOT NULL;

-- Rule: WHERE narrows through a strict function chain — lower(code) = :code proves code
-- non-null for any surviving row, even though widget.code carries no NOT NULL constraint.
-- (Selecting only `code`, rather than every widget column, avoids the single-table
-- "returns every column" projection that would reuse the table's schema-level type instead
-- of this query's narrowed one — see SqlStatement.isSingleTableStarProjection.)
-- name: widgetByLowerCode :many
SELECT code
FROM widget
WHERE lower(code) = :code;

-- Rule: a DML-to-SELECT conversion drops the SET clause but keeps WHERE — narrowing must
-- stay suppressed because WHERE's proof about `note` doesn't survive the SET that nulls it out.
-- name: clearAccountNote :many
UPDATE account
SET note = NULL
WHERE note IS NOT NULL
RETURNING note;
