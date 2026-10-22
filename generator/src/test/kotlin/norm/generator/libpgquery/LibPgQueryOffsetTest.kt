package norm.generator.libpgquery

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.prop
import norm.generator.JsonValue
import norm.generator.descendants
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LibPgQueryOffsetTest {
  private val grinning = "😀"

  private fun locations(tree: JsonValue, nodeName: String): List<Int> = tree.descendants()
    .filterIsInstance<JsonValue.JsonObject>()
    .mapNotNull { it.objectField(nodeName)?.intField("location") }
    .toList()

  @Nested
  inner class ParseLocations {

    @Test
    fun `column location is a String index after a non-BMP literal`() {
      val sql = "SELECT '$grinning' AS emoji, target FROM t"
      assertThat(locations(LibPgQuery.parse(sql), "ColumnRef")).isEqualTo(listOf(sql.indexOf("target")))
    }

    @Test
    fun `column location is a String index after a BMP character outside ASCII`() {
      val sql = "SELECT 'café' AS drink, target FROM t"
      assertThat(locations(LibPgQuery.parse(sql), "ColumnRef")).isEqualTo(listOf(sql.indexOf("target")))
    }

    @Test
    fun `location is unchanged for ASCII text`() {
      val sql = "SELECT target FROM t"
      assertThat(locations(LibPgQuery.parse(sql), "ColumnRef")).isEqualTo(listOf(sql.indexOf("target")))
    }

    @Test
    fun `statement bounds are String indexes`() {
      val sql = "SELECT '$grinning'; SELECT '$grinning$grinning'; SELECT 3"
      val statements = LibPgQuery.parse(sql).objectArrayField("stmts")
      val texts = statements.map {
        val start = it.intField("stmt_location") ?: 0
        val length = it.intField("stmt_len") ?: (sql.length - start)
        sql.substring(start, start + length).trim()
      }
      assertThat(texts).isEqualTo(listOf("SELECT '$grinning'", "SELECT '$grinning$grinning'", "SELECT 3"))
    }

    @Test
    fun `a string valued location field is unchanged`() {
      val tree = LibPgQuery.parse("CREATE TABLESPACE \"$grinning\" LOCATION '/data'")
      val statement = tree.objectArrayField("stmts").single().objectField("stmt")!!
      assertThat(statement.objectField("CreateTableSpaceStmt")?.stringField("location")).isEqualTo("/data")
    }

    @Test
    fun `negative locations are unchanged`() {
      val sql = "SELECT '$grinning' AS emoji FROM t ORDER BY emoji"
      assertThat(locations(LibPgQuery.parse(sql), "SortBy")).isEqualTo(listOf(-1))
    }
  }

  @Nested
  inner class AgainstAsciiEquivalent {

    private val statements = listOf(
      "SELECT '#' AS a, f(x, y) OVER (PARTITION BY z ORDER BY w), CAST(q AS int), r::text FROM t1 AS u JOIN v USING (id)",
      "WITH c AS (SELECT '#' AS a) SELECT a, a + 1 AS b, CASE WHEN a > 1 THEN 'x' ELSE 'y' END FROM c WHERE a IN (1, 2)",
      "INSERT INTO t (a, b) VALUES ('#', 2) ON CONFLICT (a) DO UPDATE SET b = excluded.b RETURNING a, b",
      "UPDATE t SET a = '#', b = (SELECT max(c) FROM u) WHERE EXISTS (SELECT 1 FROM v) RETURNING WITH (OLD AS o) o.a",
      "DELETE FROM t USING u WHERE t.a = '#' AND u.b = ANY (ARRAY[1, 2]) RETURNING *",
      "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = '#' WHEN NOT MATCHED THEN DO NOTHING",
      "CREATE TABLE t (id int PRIMARY KEY, name text DEFAULT '#' CHECK (name <> ''), ref int REFERENCES u (id))",
      "CREATE VIEW v AS SELECT a[1], b.c, d -> 'e', 'f' || '#' FROM t WINDOW w AS (ORDER BY a)",
      "SELECT '#' ; SELECT 2 ; SELECT 3",
      "NOTIFY \"#\", 'payload'",
      "CREATE SUBSCRIPTION \"#\" CONNECTION 'host=h' PUBLICATION p WITH (connect = false)",
      "CREATE TABLE \"#\" (a int) WITH (fillfactor = 70)",
      "CREATE ROLE \"#\" PASSWORD 'secret'",
      "SELECT * FROM JSON_TABLE('#', '$' AS pth COLUMNS (a int PATH '$.a')) AS jt",
      "SELECT * FROM generate_series(1, 3) AS g(n) WHERE n BETWEEN 1 AND 2 AND '#' IS NOT NULL ORDER BY n LIMIT 1",
    )

    @Test
    fun `every numeric field matches the parse of text with an ASCII literal of equal length`() {
      for (template in statements) {
        val nonBmp = LibPgQuery.parse(template.replace("#", "$grinning"))
        val ascii = LibPgQuery.parse(template.replace("#", "ab"))
        assertThat(scalars(nonBmp), template).isEqualTo(scalars(ascii))
      }
    }

    private fun scalars(tree: JsonValue): List<Pair<String, String>> = (sequenceOf(tree) + tree.descendants())
      .filterIsInstance<JsonValue.JsonObject>()
      .flatMap { node ->
        node.fields.mapNotNull { (key, value) -> (value as? JsonValue.JsonScalar)?.let { key to it.rawText } }
      }
      .toList()
  }

  @Nested
  inner class ScanOffsets {

    @Test
    fun `token bounds are String indexes after a non-BMP literal`() {
      val sql = "SELECT '$grinning' AS emoji, next"
      val texts = LibPgQuery.scan(sql).map { sql.substring(it.start, it.end) }
      assertThat(texts).isEqualTo(listOf("SELECT", "'$grinning'", "AS", "emoji", ",", "next"))
    }

    @Test
    fun `token bounds are String indexes after a BMP character outside ASCII`() {
      val sql = "SELECT 'café', next"
      val texts = LibPgQuery.scan(sql).map { sql.substring(it.start, it.end) }
      assertThat(texts).isEqualTo(listOf("SELECT", "'café'", ",", "next"))
    }
  }

  @Nested
  inner class ErrorCursor {

    @Test
    fun `parse error cursor is a String index after a non-BMP literal`() {
      val sql = "SELECT '$grinning$grinning' ) FROM t"
      assertFailure { LibPgQuery.parse(sql) }
        .isInstanceOf(LibPgQueryException::class)
        .prop(LibPgQueryException::cursorIndex)
        .isEqualTo(sql.indexOf(')'))
    }

    @Test
    fun `scan error cursor is a String index after a non-BMP literal`() {
      val sql = "SELECT '$grinning$grinning', \"unterminated"
      assertFailure { LibPgQuery.scan(sql) }
        .isInstanceOf(LibPgQueryException::class)
        .prop(LibPgQueryException::cursorIndex)
        .isEqualTo(sql.indexOf('"'))
    }

    @Test
    fun `cursor is null when PostgreSQL reports no position`() {
      val sql = "SELECT " + "ARRAY[".repeat(3000) + "1" + "]".repeat(3000)
      assertFailure { LibPgQuery.parse(sql) }
        .isInstanceOf(LibPgQueryException::class)
        .all {
          hasMessage("stack depth limit exceeded")
          prop(LibPgQueryException::cursorIndex).isNull()
        }
    }
  }
}
