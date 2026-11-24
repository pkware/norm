package norm.generator

import org.intellij.lang.annotations.Language
import java.sql.Connection
import java.sql.ResultSet

/**
 * Runs [sql] as a prepared statement and calls [readRow] once per result row.
 *
 * @param sql The query, with a `?` placeholder for each of [binds].
 * @param binds Values bound to the placeholders in order, so identifiers never reach the SQL text.
 * @param readRow Called with the result set positioned on each row; it must not advance the cursor.
 */
internal fun Connection.query(@Language("PostgreSQL") sql: String, vararg binds: Any?, readRow: (ResultSet) -> Unit) {
  prepareStatement(sql).use { statement ->
    binds.forEachIndexed { index, bind -> statement.setObject(index + 1, bind) }
    statement.executeQuery().use { resultSet ->
      while (resultSet.next()) readRow(resultSet)
    }
  }
}
