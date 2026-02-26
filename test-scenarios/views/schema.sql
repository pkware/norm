-- Tests that views and materialized views are properly discovered by JDBC metadata.
-- PostgreSQL reports these as "VIEW" and "MATERIALIZED VIEW", not "TABLE".

CREATE TABLE employee (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  department TEXT NOT NULL,
  salary INTEGER NOT NULL,
  active BOOLEAN NOT NULL DEFAULT true
);

COMMENT ON TABLE employee IS 'A company employee.';
COMMENT ON COLUMN employee.id IS 'Unique identifier.';
COMMENT ON COLUMN employee.name IS 'Full name of the employee.';
COMMENT ON COLUMN employee.department IS 'Department the employee belongs to.';
COMMENT ON COLUMN employee.salary IS 'Annual salary in whole dollars.';
COMMENT ON COLUMN employee.active IS 'Whether the employee is currently active.';

-- Simple pass-through view with a filter.
CREATE VIEW active_employee AS
  SELECT id, name, department, salary
  FROM employee
  WHERE active = true;

COMMENT ON VIEW active_employee IS 'Active employees only.';

-- Aggregating materialized view.
CREATE MATERIALIZED VIEW department_summary AS
  SELECT department, COUNT(*) AS employee_count, AVG(salary)::INTEGER AS average_salary
  FROM employee
  WHERE active = true
  GROUP BY department;

COMMENT ON MATERIALIZED VIEW department_summary IS 'Summary statistics per department.';
