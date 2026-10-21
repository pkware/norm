package norm.generator

import org.junit.jupiter.api.Test

class LibPgQueryProbeTest {
  private fun probe(label: String, sql: String) {
    val tree = try {
      LibPgQuery.parseJsonText(sql)
    } catch (error: LibPgQueryError) {
      "ERROR ${error.sqlMessage} (cursor ${error.cursorPosition})"
    }
    val tokens = try {
      LibPgQuery.scan(sql).joinToString(" ") {
        "[${it.start},${it.end})'${String(
          sql.toByteArray(),
          it.start,
          it.end - it.start,
          Charsets.UTF_8,
        )}'k${it.keywordKind}"
      }
    } catch (error: LibPgQueryError) {
      "SCAN ERROR ${error.sqlMessage} (cursor ${error.cursorPosition})"
    }
    println("PROBE $label\n  sql:    $sql\n  tree:   $tree\n  tokens: $tokens")
  }

  @Test
  fun `prints raw parse and scan output`() {
    probe("comment tokens", "SELECT a /* c */ AS b, -- x\n c FROM t")
    probe("question glued to operators", "SELECT a FROM t WHERE a=? AND b>=? AND c IN (?,?) AND d=-? AND e=?::int")
    probe(
      "question in literals",
      "SELECT '?' AS q, \"?\" FROM t -- ?\n WHERE a = 0 /* ? */ AND s = E'\\'?' AND d = \$\$?\$\$",
    )
    probe(
      "pgjdbc escape and operator suffixes",
      "SELECT a FROM t WHERE data ?? 'k' AND data ?| ARRAY['x'] AND data ??| ARRAY['y']",
    )
    probe(
      "rewritten one-character substitute",
      "SELECT a FROM t WHERE a=0 AND b>=0 AND c IN (0,0) AND d=-0 AND e=0::int",
    )
    probe("placeholder offsets preserved", "SELECT id, ? AS p, name FROM t WHERE a = ?")
    probe("placeholder offsets rewritten", "SELECT id, 0 AS p, name FROM t WHERE a = 0")
    probe("dollar-quoted body", "SELECT \$tag\$ ? ' \$tag\$ AS x, 1 FROM t")
    probe("unicode", "SELECT 'é' AS a, ñ FROM t")
    probe("set operation", "SELECT a FROM t UNION SELECT b FROM u")
    probe("parenthesized set operation", "(SELECT a) UNION (SELECT b)")
    probe("values", "VALUES (1, 2)")
    probe("table shorthand", "TABLE t")
    probe("insert select", "INSERT INTO t(a) SELECT a, b FROM s")
    probe("star", "SELECT *, t.*, a FROM t")
    probe("merge returning", "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = 1 RETURNING t.a")
  }
}
