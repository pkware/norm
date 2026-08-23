# Nullability Test Scenario

Golden queries that each pin one result-nullability inference rule end-to-end.

## Membership rule

A query belongs here iff its golden output's nullability differs from what raw JDBC column
metadata reports for that column — i.e. the query exists to pin one nullability inference rule
end-to-end.

If a query's point is the SQL construct itself (CTE resolution, an embed shape, a type mapping),
it belongs in that construct's scenario even when nullability shows up in its generated output.
Concretely: a nullability regression whose only interesting property is an inferred `?` (or the
absence of one) goes here; a construct-specific bug whose symptom happens to be wrong nullability
goes in that construct's bucket — a CTE bug goes in `ctes`, which already carries
nullability-through-RETURNING goldens.

## Fixture note

`widgetByLowerCode` selects only `code`, not `*`. A single-table `SELECT *` reuses the table's own
schema-level type instead of this query's WHERE-narrowed one, which would silently stop the query
from exercising the narrowing rule it exists to pin.
