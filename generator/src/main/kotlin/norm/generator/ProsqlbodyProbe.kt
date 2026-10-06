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
 * Compiles [body] into a temporary SQL-standard function (`BEGIN ATOMIC ... END`) with the given [signature].
 * Passes the function's parsed query tree from `pg_proc.prosqlbody` to [block] and drops the function afterwards.
 *
 * The `DROP FUNCTION` runs whether reading `prosqlbody` or [block] throws. A failed creation leaves nothing to
 * drop, and [onRejectedCreate] decides its outcome. If `pg_proc` has no row for the new function, an
 * [IllegalStateException] propagates to the caller.
 *
 * @param body the probe function's body text.
 * @param signature the argument types of the probe function, as `CREATE FUNCTION` accepts them.
 * @param returns the `RETURNS` clause of the probe function.
 * @param onRejectedCreate receives the [SQLException] of a rejected `CREATE FUNCTION` and returns this function's
 *   result instead of calling [block]. It rethrows by default.
 * @param block receives the probe function's parsed `prosqlbody` text and returns this function's
 *   own result
 * @return whatever [block] or [onRejectedCreate] returns
 */
internal inline fun <T> withProsqlbodyNodeTree(
  connection: Connection,
  body: String,
  signature: String = "",
  returns: String = "SETOF record",
  onRejectedCreate: (SQLException) -> T = { throw it },
  block: (String) -> T,
): T {
  val functionName = "norm_nullability_${UUID.randomUUID().toString().replace("-", "")}"
  try {
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE FUNCTION pg_temp.$functionName($signature) RETURNS $returns LANGUAGE sql " +
          // The newline before "; END" keeps a trailing `--` comment in the body from swallowing the terminator.
          "BEGIN ATOMIC $body\n; END",
      )
    }
  } catch (rejection: SQLException) {
    return onRejectedCreate(rejection)
  }
  try {
    val nodeTree = connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT prosqlbody::text FROM pg_proc " +
          // `pg_temp` is a per-session alias, so the temporary schema is addressed by `pg_my_temp_schema()`.
          "WHERE proname = '$functionName' AND pronamespace = pg_my_temp_schema()",
      ).use { resultSet ->
        check(resultSet.next()) { "No pg_proc row found for probe function $functionName" }
        resultSet.getString(1)
      }
    }
    return block(nodeTree)
  } finally {
    connection.createStatement().use { statement ->
      statement.execute("DROP FUNCTION IF EXISTS pg_temp.$functionName($signature)")
    }
  }
}
