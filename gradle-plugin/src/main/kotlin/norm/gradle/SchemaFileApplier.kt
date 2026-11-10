package norm.gradle

import org.gradle.api.logging.Logger
import org.postgresql.util.PSQLException
import java.io.File
import java.sql.Connection
import java.sql.SQLException

/**
 * Applies schema files to the analysis database and creates `NOLOGIN` stubs for roles that a file references but
 * the database lacks, such as roles that exist only in production.
 *
 * Each file runs inside a transaction that this class opens and closes, so a file that references a missing role is
 * rolled back and applied again once the role exists. A file that Postgres refuses to run inside a transaction block,
 * such as `CREATE INDEX CONCURRENTLY`, is applied without the transaction and without role stubbing.
 *
 * @property connection a superuser connection in autocommit mode.
 * @property logger receives one warning per stubbed role.
 */
internal class SchemaFileApplier(private val connection: Connection, private val logger: Logger) {

  private val stubbedRoles = mutableSetOf<String>()

  init {
    // Missing roles are recognized by the server's English error text.
    execute("SET lc_messages = 'C'")
  }

  /**
   * Applies [file], creating a stub for each missing role it references and applying it again.
   *
   * @throws IllegalStateException if the file fails for any reason other than a missing role, if a stubbed role is
   *   reported missing again, if the file committed part of its work before referencing the missing role, or if a file
   *   that cannot run in a transaction fails when applied without one.
   * @throws SQLException if a stub role cannot be created.
   */
  @Throws(SQLException::class)
  fun apply(file: File) {
    val content = file.readText()
    while (true) {
      val failure = try {
        applyInTransaction(content)
        return
      } catch (e: SQLException) {
        e
      }
      if (failure.sqlState == ACTIVE_SQL_TRANSACTION_SQL_STATE) {
        applyOutsideTransaction(file, content, failure)
        return
      }
      val role = failure.missingRole()
      if (role == null || role in stubbedRoles) throw applyFailure(file, content, failure)

      execute("ROLLBACK")
      if (fetchMarker() != MARKER_CLEAN) {
        throw IllegalStateException(
          "Norm: Failed to apply schema '${file.absolutePath}': role \"$role\" does not exist, and the file " +
            "committed part of its work before referencing it, so Norm cannot create the role and apply the file " +
            "again. Create the role in an earlier schema file.",
          failure,
        )
      }
      execute("CREATE ROLE ${quoteIdentifier(role)} NOLOGIN")
      stubbedRoles += role
      logger.warn(
        "Norm: Created NOLOGIN stub role '$role' because schema '${file.absolutePath}' references it and the " +
          "analysis database does not have it.",
      )
    }
  }

  private fun applyOutsideTransaction(file: File, content: String, transactionFailure: SQLException) {
    execute("ROLLBACK")
    if (fetchMarker() != MARKER_CLEAN) throw applyFailure(file, content, transactionFailure)
    try {
      execute(content)
    } catch (e: SQLException) {
      throw applyFailure(file, content, e)
    }
  }

  private fun applyInTransaction(content: String) {
    execute("SET $MARKER_SETTING = '$MARKER_CLEAN'")
    execute("BEGIN")
    execute("SET $MARKER_SETTING = '$MARKER_COMMITTED'")
    execute(content)
    execute("COMMIT")
  }

  private fun fetchMarker(): String? = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT current_setting('$MARKER_SETTING', true)").use { rows ->
      if (rows.next()) rows.getString(1) else null
    }
  }

  private fun execute(sql: String) {
    connection.createStatement().use { it.execute(sql) }
  }

  private fun SQLException.missingRole(): String? {
    val serverError = (this as? PSQLException)?.serverErrorMessage ?: return null
    if (serverError.sqlState != UNDEFINED_OBJECT_SQL_STATE) return null
    return MISSING_ROLE_MESSAGE.matchEntire(serverError.message.orEmpty())?.groupValues?.get(1)
  }

  private fun applyFailure(file: File, content: String, failure: SQLException): IllegalStateException {
    val locationInfo = (failure as? PSQLException)?.serverErrorMessage?.position
      ?.takeIf { it > 0 }
      ?.let { position -> sqlPositionToLineColumn(content, position) }
      ?.let { (line, column) -> " at line $line, column $column" }
      ?: ""
    return IllegalStateException(
      "Norm: Failed to apply schema '${file.absolutePath}'$locationInfo: ${failure.message}",
      failure,
    )
  }

  private fun quoteIdentifier(name: String) = "\"${name.replace("\"", "\"\"")}\""

  private companion object {
    const val UNDEFINED_OBJECT_SQL_STATE = "42704"
    const val ACTIVE_SQL_TRANSACTION_SQL_STATE = "25001"
    const val MARKER_SETTING = "norm.schema_file"
    const val MARKER_CLEAN = "clean"
    const val MARKER_COMMITTED = "committed"

    val MISSING_ROLE_MESSAGE = Regex("""^role "(.+)" does not exist$""", RegexOption.DOT_MATCHES_ALL)
  }
}

/**
 * Converts a 1-based character position in a SQL string to a (line, column) pair.
 *
 * PostgreSQL reports errors using a character position ([org.postgresql.util.ServerErrorMessage.position]).
 * This function converts that position to a human-readable line and column
 * so that errors can be traced back to the correct location in the source file.
 *
 * @param sql The full SQL string that was submitted to PostgreSQL.
 * @param position The 1-based character offset reported by PostgreSQL.
 * @return A pair of (1-based line number, 1-based column number).
 */
private fun sqlPositionToLineColumn(sql: String, position: Int): Pair<Int, Int> {
  val textBefore = sql.take((position - 1).coerceAtLeast(0))
  val line = textBefore.count { it == '\n' } + 1
  val column = position - textBefore.lastIndexOf('\n') - 1
  return line to column
}
