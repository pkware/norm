-- Star projection of a table the main queries also project. Its type is generated once, in the main output.
-- name: listAuthorsForAssertions :many
SELECT * FROM author ORDER BY id;

-- Star projection of a table only the test queries read, which pulls in the `audit_action` enum.
-- name: listAuditLog :many
SELECT * FROM audit_log ORDER BY id;

-- name: listAuditLogByAction :many
SELECT * FROM audit_log WHERE action = ? ORDER BY id;

-- name: countAuditLogByAuthor :many
SELECT author_id, count(*) AS entries FROM audit_log GROUP BY author_id ORDER BY author_id;
