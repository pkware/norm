package norm.generator.baseline

private const val DOLLAR = '$'

/** SQL that the keyword and identifier scanners must treat like PostgreSQL does, keyed `issue-331#n`. */
internal val KEYWORD_ISSUE_INPUTS: List<String> = listOf(
  "SELECT s.from FROM s",
  "SELECT 1 AS from FROM s",
  "SELECT a AS distinct FROM t",
  "SELECT s.is distinct FROM s",
  "INSERT INTO t(a) SELECT s.returning FROM s RETURNING a",
  "INSERT INTO t(a) SELECT 1 AS as RETURNING a",
  "SELECT ٣.from FROM ٣",
)

private fun withFollowingQuery(firstBody: String, lineEnding: String = "\n"): String =
  "-- name: first :one\n$firstBody\n\n-- Doc for second.\n-- name: second :many\nSELECT 2;\n".replace("\n", lineEnding)

/** Query files that exercise where a query body ends, keyed `issue-419#n`. */
internal val QUERY_BODY_ISSUE_INPUTS: List<String> = listOf(
  withFollowingQuery("DO $DOLLAR$DOLLAR BEGIN PERFORM 1;\nEND $DOLLAR$DOLLAR;"),
  withFollowingQuery("SELECT 'a;\nb' AS v;"),
  withFollowingQuery("SELECT 1 /* note;\nmore */ AS v;"),
  withFollowingQuery("SELECT E'\\';\n' AS v;"),
  withFollowingQuery("SELECT 1 AS \"a;\nb\";"),
  withFollowingQuery("SELECT 1 /* outer /* inner; */ still;\n*/ AS v;"),
  withFollowingQuery("SELECT 1\n-- trailing;\n;"),
  withFollowingQuery("SELECT ${DOLLAR}tag${DOLLAR}a;\nb${DOLLAR}tag$DOLLAR AS v;"),
  withFollowingQuery("SELECT 1; -- note"),
  "-- name: first :one\nSELECT 1; SELECT 2;",
  "-- name: first :one\nSELECT 1;\nSELECT 2;\n-- name: b :one\nSELECT 3;",
  "-- name: first :exec\nDO $DOLLAR$DOLLAR\n-- name: b :one\nSELECT 1;\n$DOLLAR$DOLLAR;",
  "-- name: first :one\nSELECT 1\n\n-- name: second :one\nSELECT 2;",
  withFollowingQuery("SELECT 'a;\nb' AS v;", lineEnding = "\r\n"),
  withFollowingQuery("CREATE FUNCTION f() RETURNS int LANGUAGE sql\nBEGIN ATOMIC\n  SELECT 1;\n  SELECT 2;\nEND;"),
)

/** The query file of the block-comment report, keyed `issue-459`. */
internal const val BLOCK_COMMENT_ISSUE_INPUT: String = """-- name: first :one
SELECT 1;

/*
-- name: old :one
SELECT 2;
*/

-- name: c :one
SELECT 3;
"""
