package norm.generator

import java.sql.Connection
import java.sql.SQLException

/**
 * Answers, for a `RETURNING`-list `:targetList`-to-`:returningList` substitution, whether a
 * `RETURNING` item that merely reads back a `:targetList` assignment can be trusted as what
 * `RETURNING` actually sees for [relid] — `false` whenever a row-level `BEFORE` trigger, a
 * rewrite rule, an `INSTEAD OF` trigger, or an FDW could substitute a different final value.
 *
 * @return `true` only when [relid] and every transitive inheritance/partition descendant is a
 *   plain table with no risky `relkind`, no mutating row-level trigger, and no non-view rewrite
 *   rule — `false` for every other case, including the catalog query itself failing to execute
 *   (treated exactly like a confirmed risk: the caller must not trust the substitution when it
 *   cannot rule the risk out).
 */
internal fun isSubstitutionSafeForRelation(connection: Connection, relid: Int): Boolean = try {
  connection.prepareStatement(
    """
    WITH RECURSIVE descendants(relid) AS (
      SELECT ?::integer
      UNION
      SELECT i.inhrelid::integer
      FROM pg_catalog.pg_inherits i
      JOIN descendants d ON i.inhparent = d.relid
    )
    SELECT
      EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class c
        JOIN descendants d ON c.oid = d.relid
        WHERE c.relkind IN ('v', 'm', 'f')
      ) AS has_risky_relkind,
      EXISTS (
        SELECT 1
        FROM pg_catalog.pg_trigger tg
        JOIN descendants d ON tg.tgrelid = d.relid
        WHERE NOT tg.tgisinternal
          AND (tg.tgtype & 1) = 1
          AND (tg.tgtype & 2) = 2
          AND ((tg.tgtype & 4) = 4 OR (tg.tgtype & 16) = 16)
      ) AS has_mutating_row_trigger,
      EXISTS (
        SELECT 1
        FROM pg_catalog.pg_rewrite rw
        JOIN descendants d ON rw.ev_class = d.relid
        WHERE rw.rulename <> '_RETURN'
      ) AS has_non_view_rewrite_rule
    """.trimIndent(),
  ).use { preparedStatement ->
    preparedStatement.setInt(1, relid)
    preparedStatement.executeQuery().use { resultSet ->
      check(resultSet.next()) { "Expected exactly one row from the substitution-safety EXISTS query" }
      !resultSet.getBoolean("has_risky_relkind") &&
        !resultSet.getBoolean("has_mutating_row_trigger") &&
        !resultSet.getBoolean("has_non_view_rewrite_rule")
    }
  }
} catch (_: SQLException) {
  false
}
