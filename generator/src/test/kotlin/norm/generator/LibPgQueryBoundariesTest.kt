package norm.generator

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import com.google.gson.JsonObject
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LibPgQueryBoundariesTest {
  private fun tokenText(sql: String, token: ScanToken): String =
    String(sql.toByteArray(), token.start, token.end - token.start)

  private fun tokenStart(sql: String, text: String): Int =
    LibPgQuery.scan(sql).first { tokenText(sql, it) == text }.start

  private fun statement(sql: String, kind: String): JsonObject =
    LibPgQuery.parseJson(sql)["stmts"].asJsonArray[0].asJsonObject["stmt"].asJsonObject[kind].asJsonObject

  @Nested
  inner class SelectList {
    private val sql = "SELECT id, name AS n, COUNT(*) FROM users WHERE a = 1"

    @Test
    fun `each item records only its start offset`() {
      val targets = statement(sql, "SelectStmt")["targetList"].asJsonArray
        .map { it.asJsonObject["ResTarget"].asJsonObject }
      assertThat(targets.map { it["location"].asInt }).containsExactly(7, 11, 22)
      assertThat(targets.flatMap { it.keySet() }.toSet()).isEqualTo(setOf("name", "val", "location"))
    }

    @Test
    fun `the FROM keyword is not where the first FROM item starts`() {
      val select = statement(sql, "SelectStmt")
      val fromItemStart = select["fromClause"].asJsonArray[0].asJsonObject["RangeVar"].asJsonObject["location"].asInt
      assertThat(fromItemStart).isEqualTo(36)
      assertThat(tokenStart(sql, "FROM")).isEqualTo(31)
      assertThat(select["location"]).isNull()
    }

    @Test
    fun `the RETURNING keyword is not where the first returned item starts`() {
      val returning = "INSERT INTO t(a) VALUES (1) RETURNING a, b AS c"
      val firstItem = statement(
        returning,
        "InsertStmt",
      )["returningClause"].asJsonObject["exprs"].asJsonArray[0].asJsonObject["ResTarget"]
        .asJsonObject["location"].asInt
      assertThat(firstItem).isEqualTo(38)
      assertThat(tokenStart(returning, "RETURNING")).isEqualTo(28)
    }
  }

  @Nested
  inner class CommonTableExpressions {
    private val sql = "WITH x AS (SELECT 1 AS a), y AS (SELECT 2 AS b) SELECT a FROM x"

    @Test
    fun `a CTE records its name offset and no body extent`() {
      val ctes = statement(sql, "SelectStmt")["withClause"].asJsonObject["ctes"].asJsonArray
        .map { it.asJsonObject["CommonTableExpr"].asJsonObject }
      assertThat(ctes.map { it["location"].asInt }).containsExactly(5, 27)
      assertThat(ctes[0]["ctequery"].asJsonObject["SelectStmt"].asJsonObject["location"]).isNull()
    }

    @Test
    fun `scan tokens give the body parentheses that the tree omits`() {
      val bodies = mutableListOf<Pair<Int, Int>>()
      var depth = 0
      var open = -1
      for (token in LibPgQuery.scan(sql)) {
        when (tokenText(sql, token)) {
          "(" -> if (depth++ == 0) open = token.start
          ")" -> if (--depth == 0) bodies += open to token.start
        }
      }
      assertThat(bodies).containsExactly(10 to 24, 32 to 46)
    }
  }

  @Nested
  inner class Placeholders {
    @Test
    fun `a question mark placeholder is a syntax error`() {
      assertFailure { LibPgQuery.parseJson("SELECT a FROM t WHERE a = ? AND b = ?") }
        .isInstanceOf(LibPgQueryError::class)
    }

    @Test
    fun `the lexer does not split a question mark from adjacent operator characters`() {
      val sql = "SELECT a FROM t WHERE a=? AND b>=? AND d=-?"
      val operatorTokens = LibPgQuery.scan(sql).map { tokenText(sql, it) }.filter { '?' in it }
      assertThat(operatorTokens).containsExactly("=?", ">=?", "=-?")
    }

    @Test
    fun `a dollar parameter parses to a ParamRef with its offset`() {
      val sql = "SELECT a FROM t WHERE a = \$1"
      val right = statement(sql, "SelectStmt")["whereClause"].asJsonObject["A_Expr"].asJsonObject["rexpr"].asJsonObject
      assertThat(right["ParamRef"].asJsonObject["location"].asInt).isEqualTo(26)
    }

    @ParameterizedTest
    @ValueSource(
      strings = [
        "SELECT * FROM t WHERE id = ?",
        "SELECT * FROM t WHERE a = ? AND b = ?",
        "SELECT * FROM t WHERE note = 'really?' AND id = ?",
        "SELECT * FROM t WHERE note = 'it''s a ? mark' AND id = ?",
        "SELECT * FROM t -- why?\nWHERE id = ?",
        "SELECT * FROM t /* what? */ WHERE id = ?",
        "SELECT j ?? 'k' FROM t WHERE a = ? AND id = ?",
        "SELECT j ??? 1",
        "SELECT '??' /* ?? */ -- ??\n, ?",
        "SELECT \$\$a ? b\$\$ FROM t WHERE id = ?",
        "SELECT \$tag\$a ? b\$tag\$ FROM t WHERE id = ?",
        "SELECT \"quoted?identifier\" FROM t WHERE id = ?",
        "SELECT E'a\\'?b' FROM t WHERE id = ?",
        "SELECT /* a /* b */ c ? */ 1 FROM t WHERE id = ?",
        "SELECT a FROM t WHERE a=? AND b>=? AND c IN (?,?) AND d=-? AND e=?::int",
        "SELECT digest(?, ?)",
      ],
    )
    fun `placeholder positions found through scan tokens equal the hand-written lexer's`(sql: String) {
      val rewritten = replacePlaceholdersKeepingOffsets(sql)!!
      val fromTokens = sql.indices.filter { sql[it] == '?' && rewritten[it] == '0' }
      assertThat(fromTokens).isEqualTo(placeholderPositions(sql).toList())
      assertThat(rewritten.length).isEqualTo(sql.length)
    }

    @Test
    fun `the rewritten text parses and keeps every item offset`() {
      val sql = "SELECT id, ? AS p, name FROM t WHERE a = ?"
      val rewritten = replacePlaceholdersKeepingOffsets(sql)!!
      assertThat(rewritten).isNotEqualTo(sql)
      val locations = statement(rewritten, "SelectStmt")["targetList"].asJsonArray
        .map { it.asJsonObject["ResTarget"].asJsonObject["location"].asInt }
      assertThat(locations).containsExactly(7, 11, 19)
      assertThat(libPgQueryParseSelectItems(sql).map { it.expression }).containsExactly("id", "?", "name")
    }

    @ParameterizedTest
    @ValueSource(
      strings = [
        "SELECT coalesce(?, a) AS x, b FROM t",
        "SELECT j ?? 'k' AS has, id FROM t",
        "INSERT INTO t(a, p) VALUES (1, 2) RETURNING a, ? AS p",
      ],
    )
    fun `item text keeps the original placeholders`(sql: String) {
      assertThat(libPgQueryParseSelectItems(sql)).isEqualTo(parseSelectItems(sql))
    }

    @Test
    fun `item text of a placeholder expression is the original source`() {
      assertThat(libPgQueryParseSelectItems("SELECT coalesce(?, a) AS x, b FROM t").map { it.expression })
        .containsExactly("coalesce(?, a)", "b")
      assertThat(libPgQueryParseSelectItems("SELECT j ?? 'k' AS has, id FROM t").map { it.expression })
        .containsExactly("j ?? 'k'", "id")
      assertThat(
        libPgQueryParseSelectItems("INSERT INTO t(a, p) VALUES (1, 2) RETURNING a, ? AS p").map {
          it.expression
        },
      )
        .containsExactly("a", "?")
    }
  }

  @Nested
  inner class SetOperations {
    @Test
    fun `a set operation wrapped in one parenthesis pair differs from the hand-written parser`() {
      val sql = "(SELECT a UNION SELECT b)"
      assertThat(parseSelectItems(sql)).containsExactly(SelectItem("a UNION SELECT b", null, null))
      assertThat(libPgQueryParseSelectItems(sql)).isEmpty()
    }
  }
}
