package norm.generator

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Replaces `?` parameter placeholders in [sql] with typed non-null sentinel values (e.g.,
 * `0::int4`, `''::text`).
 *
 * @return The SQL with `?` replaced by typed sentinels, or `null` if parameter metadata
 *   cannot be obtained (caller should fall back to NULL replacement).
 */
internal fun buildViewSqlWithSentinels(connection: Connection, sql: String): String? {
  if ('?' !in sql) return sql
  return try {
    val sentinels = connection.prepareStatement(sql).use { preparedStatement ->
      val parameterMetaData = preparedStatement.parameterMetaData
      (1..parameterMetaData.parameterCount).map { index ->
        nonNullSentinel(parameterMetaData.getParameterTypeName(index))
      }
    }
    replaceParameterPlaceholders(sql) { sentinels.getOrElse(it) { "NULL" } }
  } catch (_: SQLException) {
    null
  }
}

/**
 * Runs the `prosqlbody` probe's full lifecycle: creates a temporary, zero-argument SQL-standard
 * function (`BEGIN ATOMIC ... END`) whose body is [substitutedSql], reads back its parsed query
 * tree from `pg_proc.prosqlbody`, passes that text to [block], then drops the function again.
 *
 * The probe function takes ZERO arguments: a real `$n` parameter would appear as a `PARAM` node in
 * the parsed tree, silently widening every parameter-touching column to nullable. [substitutedSql]
 * must therefore already have every `?` parameter placeholder replaced with a typed literal (see
 * [buildViewSqlWithSentinels]) before it reaches this function.
 *
 * A statement with no result columns at all (an `INSERT`/`UPDATE`/`DELETE`/`MERGE` without
 * `RETURNING`) fails PostgreSQL's `RETURNS SETOF record` check on function creation. That
 * [SQLException], and the `IllegalStateException` a failed [kotlin.check] throws when no
 * `pg_proc` row is found for the freshly-created function, both propagate to the caller
 * uncaught — there is no recovery this function can attempt for either. Once the function has been
 * created, the `DROP FUNCTION` runs whether reading `prosqlbody` or [block] throws; a failed
 * creation leaves nothing to drop.
 *
 * @param substitutedSql the probe function's body text; must contain no `?` placeholder
 * @param block receives the probe function's parsed `prosqlbody` text and returns this function's
 *   own result
 * @return whatever [block] returns
 */
internal inline fun <T> withProsqlbodyNodeTree(
  connection: Connection,
  substitutedSql: String,
  block: (String) -> T,
): T {
  val functionName = "norm_nullability_${UUID.randomUUID().toString().replace("-", "")}"
  connection.createStatement().use { statement ->
    statement.execute(
      "CREATE FUNCTION pg_temp.$functionName() RETURNS SETOF record LANGUAGE sql " +
        // The newline before "; END" is required, not style: substitutedSql can legitimately
        // end in a trailing `--` line comment, which extends to end of line; without a newline
        // separating it from "; END", the comment swallows the terminator too.
        "BEGIN ATOMIC $substitutedSql\n; END",
    )
  }
  try {
    val nodeTree = connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT prosqlbody::text FROM pg_proc " +
          // "pg_temp" is a per-session ALIAS, not a literal schema name: 'pg_temp'::regnamespace
          // fails with `ERROR: schema "pg_temp" does not exist`. pg_my_temp_schema() returns the
          // current session's actual temp schema OID directly.
          "WHERE proname = '$functionName' AND pronamespace = pg_my_temp_schema()",
      ).use { resultSet ->
        check(resultSet.next()) { "No pg_proc row found for probe function $functionName" }
        resultSet.getString(1)
      }
    }
    return block(nodeTree)
  } finally {
    connection.createStatement().use { statement ->
      statement.execute("DROP FUNCTION IF EXISTS pg_temp.$functionName()")
    }
  }
}
