package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** One SQL input that must still trip the star guard of [SelectItem.isStar], dropping to an empty list. */
internal data class StarGuardCase(val description: String, val sql: String) {
  override fun toString() = description
}

/** One SQL input that must not trip the star guard, resolving to [expected] instead. */
internal data class NotStarGuardCase(val description: String, val sql: String, val expected: List<SelectItem>) {
  override fun toString() = description
}

/**
 * One item of an expected select list.
 *
 * @property expression the full expression text.
 * @property alias the alias as written, or `null`.
 * @property isAliasExplicit whether the alias follows `AS`.
 */
internal data class ExpectedItem(
  val expression: String,
  val alias: String? = null,
  val isAliasExplicit: Boolean = false,
)

/** One SQL input whose items are exactly [expected], including where each item's expression ends. */
internal data class ItemExtentCase(val sql: String, val expected: List<ExpectedItem>) {
  constructor(sql: String, vararg expected: ExpectedItem) : this(sql, expected.toList())

  override fun toString() = sql.replace("\n", "\\n")
}

/**
 * A select list item written with no whitespace between it and the text around it. The input is
 * [prefix] + [expression] + [suffix]; its comma form puts `id,` before the expression.
 *
 * @property alias the alias that [suffix] starts with, if any.
 * @property isStar whether the item is a star, which drops every item after it.
 */
internal data class GluedShape(
  val prefix: String,
  val expression: String,
  val suffix: String,
  val alias: String? = null,
  val isAliasExplicit: Boolean = false,
  val isStar: Boolean = false,
) {
  val sql: String get() = prefix + expression + suffix
  val commaSql: String get() = "$prefix id,$expression$suffix"
  val expected: List<ExpectedItem> get() = listOf(ExpectedItem(expression, alias, isAliasExplicit))
  val commaExpected: List<ExpectedItem> get() = listOf(ExpectedItem("id")) + if (isStar) emptyList() else expected

  override fun toString() = sql.replace("\n", "\\n")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlOutputClauseTest {

  @Nested
  inner class ParseSelectItems {

    @Test
    fun `UPDATE RETURNING with simple columns`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id, name")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `UPDATE RETURNING with qualified alias`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING tier, old.tier AS old_tier")
      assertThat(result).containsExactly(
        SelectItem("tier", "tier", null),
        SelectItem("old.tier", "tier", "old"),
      )
    }

    @Test
    fun `INSERT RETURNING`() {
      val result = parseSelectItems("INSERT INTO t(x) VALUES(1) RETURNING id, name")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `DELETE RETURNING`() {
      val result = parseSelectItems("DELETE FROM t WHERE id = 1 RETURNING id, name")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `RETURNING with computed expression`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id, count(*) AS total")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("count(*)", null, null),
      )
    }

    @Test
    fun `RETURNING with trailing semicolon`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id, name;")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `RETURNING with CAST does not confuse inner AS with alias AS`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING CAST(tier AS text) AS tier_text")
      assertThat(result).containsExactly(
        SelectItem("CAST(tier AS text)", null, null),
      )
    }

    @Test
    fun `RETURNING star`() {
      val result = parseSelectItems("DELETE FROM t RETURNING *")
      assertThat(result).containsExactly(
        SelectItem("*", null, null, isStar = true),
      )
    }

    @Test
    fun `an unmatched closing parenthesis in a RETURNING item is rejected by PostgreSQL, so there are no items`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id, ) (bogus AS alias")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an unmatched square bracket in a RETURNING item is rejected by PostgreSQL, so there are no items`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id, a[b AS c")
      assertThat(result).isEmpty()
    }

    @Test
    fun `RETURNING WITH OLD-NEW alias prologue is not part of the first item`() {
      // PostgreSQL 18's `RETURNING WITH (OLD AS o, NEW AS n) o.x, n.x` returns 2 columns. The prologue is not
      // part of the first item, which is the column reference `o.x`.
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING WITH (OLD AS o, NEW AS n) o.x, n.x")
      assertThat(result).containsExactly(
        SelectItem("o.x", "x", "o"),
        SelectItem("n.x", "x", "n"),
      )
    }

    @Test
    fun `SELECT with simple columns`() {
      val result = parseSelectItems("SELECT id, name FROM users")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `SELECT with qualified alias`() {
      val result = parseSelectItems("SELECT author.name AS author_name FROM author")
      assertThat(result).containsExactly(
        SelectItem("author.name", "name", "author"),
      )
    }

    @Test
    fun `SELECT with computed expression`() {
      val result = parseSelectItems("SELECT COUNT(*) AS total FROM users")
      assertThat(result).containsExactly(
        SelectItem("COUNT(*)", null, null),
      )
    }

    @Test
    fun `SELECT star`() {
      // A lone star has nothing after it to shift, so the star-truncation guard leaves it alone.
      val result = parseSelectItems("SELECT * FROM users")
      assertThat(result).containsExactly(
        SelectItem("*", null, null, isStar = true),
      )
    }

    @Test
    fun `no SELECT or RETURNING returns empty list`() {
      val result = parseSelectItems("CALL my_proc()")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a qualified column named from does not truncate the select list at its own name`() {
      val result = parseSelectItems("SELECT s.from FROM s")
      assertThat(result).containsExactly(
        SelectItem("s.from", "from", "s"),
      )
    }

    @Test
    fun `a qualified column named returning is not taken for the RETURNING clause itself`() {
      val result = parseSelectItems("INSERT INTO t(a) SELECT s.returning FROM s RETURNING a")
      assertThat(result).containsExactly(
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `WITH RECURSIVE CTE body SELECT is not mistaken for the main query's SELECT`() {
      // The items come from the main query's SELECT ("SELECT id, name FROM new_parent"), not the
      // CTE body's own SELECT ("SELECT label FROM parent_name").
      val result = parseSelectItems(
        """
        WITH RECURSIVE new_parent AS (
          INSERT INTO parent (name) SELECT label FROM parent_name RETURNING id, name
        ),
        parent_name AS (
          SELECT 'seeded'::TEXT AS label
        )
        SELECT id, name FROM new_parent
        """.trimIndent(),
      )
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `plain WITH CTE body select list of a different length is not picked over the main query`() {
      val result = parseSelectItems(
        "WITH c AS (SELECT 1, 2, 3) SELECT id, name FROM main_table",
      )
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("name", "name", null),
      )
    }

    @Test
    fun `INSERT SELECT RETURNING picks the RETURNING clause, not the SELECT source`() {
      val result = parseSelectItems("INSERT INTO t(a,b) SELECT x, y FROM s RETURNING id, a")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `UPDATE RETURNING with a scalar subquery in SET does not pick the subquery's SELECT`() {
      val result = parseSelectItems("UPDATE t SET x = (SELECT max(y) FROM s) RETURNING id")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `SELECT keyword inside a string literal ahead of the real clause is not mistaken for it`() {
      val result = parseSelectItems("UPDATE t SET note = 'please SELECT this' RETURNING id")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `SELECT keyword inside a block comment ahead of the real clause is not mistaken for it`() {
      val result = parseSelectItems("UPDATE t /* SELECT me not */ SET x = 1 RETURNING id")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `RETURNING as a plain SELECT's column alias is not mistaken for the RETURNING keyword`() {
      // RETURNING is not a reserved word in PostgreSQL — it is legal as a column alias in a
      // SELECT's target list — confirmed valid syntax on PostgreSQL 18.4 ("CREATE TABLE t
      // (returning int)" and "FROM users AS returning" are rejected, so the alias position
      // specifically is the hole). The main query's own leading keyword is SELECT, so this alias
      // is not read as the RETURNING clause keyword and the real "FROM users" is not misread as a
      // single bogus item.
      val result = parseSelectItems("SELECT preferences AS returning FROM users")
      assertThat(result).containsExactly(
        SelectItem("preferences", "preferences", null),
      )
    }

    @Test
    fun `RETURNING as a column alias alongside another item is not mistaken for the RETURNING keyword`() {
      val result = parseSelectItems("SELECT id, preferences AS returning FROM users")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("preferences", "preferences", null),
      )
    }

    @Test
    fun `RETURNING as a column alias after a WITH clause is not mistaken for the RETURNING keyword`() {
      val result = parseSelectItems("WITH c AS (SELECT 1) SELECT x AS returning FROM c")
      assertThat(result).containsExactly(
        SelectItem("x", "x", null),
      )
    }

    @Test
    fun `an AS returning alias inside a DML statement's own source SELECT is not mistaken for the RETURNING keyword`() {
      // Confirmed against a real PostgreSQL 18.4 container: "INSERT INTO users (email,
      // preferences) SELECT email AS returning, preferences FROM users RETURNING id, email,
      // preferences" returns exactly 3 columns (id, email, preferences). The `returning` alias belongs to
      // the first item of the source SELECT.
      val result = parseSelectItems(
        "INSERT INTO users (email, preferences) SELECT email AS returning, preferences " +
          "FROM users RETURNING id, email, preferences",
      )
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("email", "email", null),
        SelectItem("preferences", "preferences", null),
      )
    }

    @Test
    fun `an AS returning alias in the RETURNING list itself is not mistaken for an earlier real keyword`() {
      // The alias follows the real RETURNING keyword here, so the list still comes from that clause.
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id AS returning")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `an AS returning alias immediately preceding the real keyword in a FROM-less source select`() {
      val result = parseSelectItems("INSERT INTO t (a) SELECT 1 AS returning RETURNING a, b")
      assertThat(result).containsExactly(
        SelectItem("a", "a", null),
        SelectItem("b", "b", null),
      )
    }

    @Test
    fun `a block comment between AS and its returning alias does not disturb keyword recognition`() {
      // Confirmed against a real PostgreSQL 18.4 container: "SELECT 1 AS/*c*/returning" is valid
      // syntax — a comment is a legal separator between "AS" and its alias.
      val result = parseSelectItems("INSERT INTO t (a) SELECT 1 AS/*c*/returning RETURNING a, b")
      assertThat(result).containsExactly(
        SelectItem("a", "a", null),
        SelectItem("b", "b", null),
      )
    }

    @Test
    fun `a line comment between AS and its returning alias does not disturb keyword recognition`() {
      // Confirmed against a real PostgreSQL 18.4 container: "SELECT 1 AS--x" followed by a newline
      // then "returning" is valid syntax, same as the block-comment case above.
      val result = parseSelectItems("INSERT INTO t (a) SELECT 1 AS--x\nreturning RETURNING a, b")
      assertThat(result).containsExactly(
        SelectItem("a", "a", null),
        SelectItem("b", "b", null),
      )
    }

    @Test
    fun `a source column named with a trailing non-ASCII character is not mistaken for an AS returning alias`() {
      // Confirmed against a real PostgreSQL 18.4 container against "users(id, email, age,
      // preferences)" and a source table "src": "INSERT INTO users (email, age, preferences)
      // SELECT returning€, age, preferences FROM src RETURNING id, email" returns exactly 2
      // columns (id, email) -- "returning€" is a legal column name (PostgreSQL's lexer admits any
      // byte >= 0x80 inside an unquoted identifier), not the RETURNING keyword's alias position.
      // The word scan keeps "returning€" as one word, so only the real RETURNING clause supplies
      // the 2 items.
      val result = parseSelectItems(
        "INSERT INTO users (email, age, preferences) " +
          "SELECT returning€, age, preferences FROM src RETURNING id, email",
      )
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("email", "email", null),
      )
    }

    @Test
    fun `a dollar-quoted string with a non-ASCII tag is recognized, so later items are not misaligned`() {
      // Confirmed against a real PostgreSQL 18.4: "SELECT $€$x,$€$ AS lbl,
      // age, email FROM users" returns exactly 3 columns (lbl, age, email); "$€$x,$€$" is a single
      // dollar-quoted string literal tagged "€" (a legal tag character -- PostgreSQL's scan.l
      // admits any byte >= 0x80 in a dollar-quote tag, same as in an ordinary identifier). The tag
      // run includes "€", so the dollar-quoted string is skipped as one lexical unit and its
      // contents ("x", ",", the second "$€$") do not split into extra items.
      val result = parseSelectItems("SELECT \$€\$x,\$€\$ AS lbl, age, email FROM users")
      assertThat(result).containsExactly(
        SelectItem("\$€\$x,\$€\$", null, null),
        SelectItem("age", "age", null),
        SelectItem("email", "email", null),
      )
    }

    @Test
    fun `RETURNING a column with a trailing non-ASCII character keeps its original name`() {
      // Confirmed against a real PostgreSQL 18.4 container -- "CREATE TABLE
      // users(id int, email text, age int, preferences€ text)" then "INSERT INTO
      // users(id,email,age,preferences€) VALUES (1,'e',2,'p') RETURNING preferences€, email"
      // returns columns named exactly "preferences€" and "email" -- "preferences€" is a legal
      // unquoted PostgreSQL column name (the lexer admits any byte >= 0x80 inside an unquoted
      // identifier).
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING preferences€, email")
      assertThat(result).containsExactly(
        SelectItem("preferences€", "preferences€", null),
        SelectItem("email", "email", null),
      )
    }

    @Test
    fun `a column name written with a supplementary-plane character resolves via the widened regex`() {
      // A supplementary-plane character is a surrogate pair in a Kotlin/UTF-16 String -- both code
      // units are >= 0x80, so PostgreSQL's own lexer admits it in an unquoted identifier the same
      // as any other >= 0x80 byte (confirmed against PostgreSQL 18.4: "CREATE TABLE astral(id int,
      // x𝐀y text)" -- U+1D400 MATHEMATICAL BOLD CAPITAL A -- succeeds, and "SELECT
      // x𝐀y FROM astral" resolves it unquoted).
      val result = parseSelectItems("SELECT x𝐀y, id FROM astral")
      assertThat(result).containsExactly(
        SelectItem("x𝐀y", "x𝐀y", null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a digit-leading fragment abutting a non-ASCII character is not mistaken for a column reference`() {
      // Confirmed on PostgreSQL 18.4, "SELECT 2€" is rejected outright ("trailing junk after
      // numeric literal"), so "2€" is not an identifier PostgreSQL would ever lex and the whole
      // statement yields no items.
      val result = parseSelectItems("SELECT 2€, email FROM users")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an AS keyword directly abutting a closing parenthesis is recognized as an alias boundary`() {
      // Confirmed on PostgreSQL 18.4: "SELECT (1)AS b" returns column "b" -- "AS" here abuts
      // the closing ")" with no whitespace at all, yet is still the real keyword, because ")" is
      // not an identifier character. The parenthesized "age" is still the column reference "age".
      val result = parseSelectItems("SELECT (age)AS b FROM users")
      assertThat(result).containsExactly(
        SelectItem("(age)", "age", null),
      )
    }

    @Test
    fun `an AS keyword directly abutting a closing parenthesis, followed by a quoted alias, is recognized`() {
      // Confirmed on PostgreSQL 18.4: "SELECT (1)AS"b"" returns column "b" -- same boundary as
      // the plain-alias case above, but with a double-quoted alias directly following "AS" (no
      // whitespace on that side either). A double quote is not an identifier character, so the
      // keyword is recognized and the alias is split off.
      val result = parseSelectItems("SELECT (age)AS\"b\" FROM users")
      assertThat(result).containsExactly(
        SelectItem("(age)", "age", null),
      )
    }

    @Test
    fun `an identifier ending in the letters AS is not mistaken for the AS keyword`() {
      // Confirmed on PostgreSQL 18.4: "CREATE TABLE d1(dataAS text)" then "SELECT 1 dataAS"
      // returns column "dataas" -- "dataAS" lexes as one identifier, never as "data" followed by
      // the keyword "AS". The item is the column "dataas" with the implicit alias "b".
      val result = parseOutputItemsWithAlias("SELECT dataAS b FROM users")
      assertThat(result).containsExactly(
        OutputItemWithAlias(SelectItem("dataAS", "dataas", null), "b", isAliasExplicit = false),
      )
    }

    @Test
    fun `an AS keyword fused with a following non-ASCII character is not an alias boundary`() {
      // Confirmed on PostgreSQL 18.4: "SELECT 1 AS€b" returns column "as€b" -- "AS€b" lexes as
      // one implicit-alias identifier (€ is a legal identifier-continuation character), never as
      // the keyword "AS" followed by "€b".
      val result = parseOutputItemsWithAlias("SELECT age AS€b FROM users")
      assertThat(result).containsExactly(
        OutputItemWithAlias(SelectItem("age", "age", null), "AS€b", isAliasExplicit = false),
      )
    }

    @Test
    fun `an AS keyword fused with a preceding non-ASCII character is not an alias boundary`() {
      // Confirmed on PostgreSQL 18.4: "SELECT age x€AS b FROM users" is a syntax error --
      // "x€AS" lexes as one implicit-alias identifier (the same reasoning as the "AS€b" case
      // above, from the other side), leaving a stray extra token "b" with nothing to attach to.
      val result = parseOutputItemsWithAlias("SELECT age x€AS FROM users")
      assertThat(result).containsExactly(
        OutputItemWithAlias(SelectItem("age", "age", null), "x€AS", isAliasExplicit = false),
      )
    }

    @Test
    fun `a candidate AS word match is ASCII-only, not fooled by a character the JVM case-folds to S`() {
      // U+017F "long s" (ſ) upper-cases to ASCII "S" under Kotlin/Java's ignoreCase comparison, but
      // PostgreSQL's own AS keyword match is ASCII-only: "SELECT x AS aſ" (confirmed on PostgreSQL
      // 18.4) has a real "AS" keyword and a column literally named "aſ", which the result reports
      // as the alias.
      val result = parseOutputItemsWithAlias("SELECT x AS aſ FROM t").single()
      assertThat(result.alias).isEqualTo("aſ")
    }

    @Test
    fun `an unquoted uppercase column reference is ASCII-folded to lowercase`() {
      // pgjdbc's ResultSetMetaData.getColumnName reports "id" (PostgreSQL folds an unquoted
      // reference via downcase_identifier before ever resolving it against the catalog).
      // JdbcAnalyzer.buildResultColumns prefers this parsed columnName over the JDBC-reported one,
      // so storing the matched text verbatim ("ID") would make catalog.findColumn miss and silently
      // drop the column's Postgres comment.
      val result = parseSelectItems("SELECT ID FROM t")
      assertThat(result).containsExactly(
        SelectItem("ID", "id", null),
      )
    }

    @Test
    fun `an unquoted uppercase qualified column reference folds both the table and column name`() {
      val result = parseSelectItems("SELECT T.ID FROM t")
      assertThat(result).containsExactly(
        SelectItem("T.ID", "id", "t"),
      )
    }

    @Test
    fun `an unquoted non-ASCII uppercase column reference is NOT folded`() {
      // foldAsciiCase's own KDoc documents this directly: PostgreSQL's downcase_identifier folds
      // only plain ASCII A-Z, never a non-ASCII letter, even one with an obvious upper/lower
      // pairing -- an unquoted "Ü" fails to resolve against a column actually named "ü" ("column
      // "Ü" does not exist"), and instead resolves against a column named "Ü" (quoted, uppercase).
      // Folding via Kotlin's String.lowercase() here would silently disagree with PostgreSQL about
      // which of two differently-cased columns an unquoted reference targets -- the exact
      // regression foldAsciiCase exists to prevent.
      val result = parseSelectItems("SELECT Ü FROM t")
      assertThat(result).containsExactly(
        SelectItem("Ü", "Ü", null),
      )
    }

    @Test
    fun `quoted CTE name`() {
      val result = parseSelectItems("""WITH "myCte" AS (SELECT 1 AS a) SELECT a FROM "myCte"""")
      assertThat(result).containsExactly(
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a parenthesized main query following a CTE clause still resolves`() {
      // The one pair wrapping the whole main query does not change its select list, so this resolves
      // like the unparenthesized form.
      val result = parseSelectItems("WITH c AS (SELECT 1 AS a) (SELECT a FROM c)")
      assertThat(result).containsExactly(SelectItem("a", "a", null))
    }

    @Test
    fun `a parenthesized main query with no WITH clause still resolves`() {
      // Same as above, with no leading WITH clause at all.
      val result = parseSelectItems("(SELECT a FROM x)")
      assertThat(result).containsExactly(SelectItem("a", "a", null))
    }

    @Test
    fun `parenthesized UNION branches with no WITH clause yield the first branch's items`() {
      // PostgreSQL names the result column after the first branch.
      val result = parseSelectItems("(SELECT a FROM x) UNION (SELECT b FROM y)")
      assertThat(result).containsExactly(SelectItem("a", "a", null))
    }

    @Test
    fun `VALUES clause yields no items`() {
      // A bare VALUES list has no top-level SELECT/RETURNING to find.
      val result = parseSelectItems("VALUES (1, 2)")
      assertThat(result).isEmpty()
    }

    @Test
    fun `TABLE clause yields no items`() {
      // Same reason as the VALUES case above.
      val result = parseSelectItems("TABLE t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a body wrapped in one redundant pair of parentheses still finds the top-level SELECT`() {
      // A CTE body like `(SELECT ...)` slices to this text. The wrapping pair does not change
      // its select list.
      val result = parseSelectItems("(SELECT id, UPPER(name) AS name_upper FROM parent)")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem("UPPER(name)", null, null),
      )
    }

    @Test
    fun `a body wrapped in two redundant pairs of parentheses still finds the top-level SELECT`() {
      val result = parseSelectItems("((SELECT id FROM parent))")
      assertThat(result).containsExactly(SelectItem("id", "id", null))
    }

    @Test
    fun `two separately parenthesized set-operation branches yield the first branch's items`() {
      // The first branch alone names the result column.
      val result = parseSelectItems("(SELECT id FROM parent) UNION (SELECT id FROM child)")
      assertThat(result).containsExactly(SelectItem("id", "id", null))
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    internal inner class StarGuard {

      @ParameterizedTest(name = "{0}")
      @MethodSource("starGuardCases")
      fun `still triggers the star guard`(testCase: StarGuardCase) {
        assertThat(parseSelectItems(testCase.sql)).isEmpty()
      }

      fun starGuardCases(): List<StarGuardCase> = listOf(
        StarGuardCase(
          description = "star alongside another item returns empty list to avoid shifting later items",
          sql = "SELECT *, id FROM t",
        ),
        // Confirmed on real PostgreSQL: "(tgt.*)" is valid syntax, equivalent to "tgt.*". The
        // star is the first item here, so nothing precedes it to keep.
        StarGuardCase(
          description = "parenthesized star as the first item drops the whole list",
          sql = "SELECT (tgt.*), id AS ident FROM tgt",
        ),
        StarGuardCase(
          description = "star with a trailing block comment as the first item drops the whole list",
          sql = "SELECT tgt.* /*c*/, id AS ident FROM tgt",
        ),
        StarGuardCase(
          description = "star with a trailing line comment as the first item drops the whole list",
          sql = "SELECT tgt.* -- c\n, id AS ident FROM tgt",
        ),
        StarGuardCase(
          description = "star with whitespace around the qualifying dot as the first item drops the whole list",
          sql = "SELECT tgt . *, id AS ident FROM tgt",
        ),
        // Confirmed on PostgreSQL 18: "SELECT t.* AS whatever, 7 AS id FROM t" returns
        // 3 columns (a, b, id) for a two-column "t". The star's own "AS whatever" alias does not hide it,
        // and the guard drops the later "id" item with the rest of the list, because t's expansion shifts
        // its position.
        StarGuardCase(
          description = "an aliased star still triggers the star guard",
          sql = "SELECT t.* AS whatever, 7 AS id FROM t",
        ),
        StarGuardCase(
          description = "an aliased star in RETURNING still triggers the star guard",
          sql = "UPDATE t SET x = 1 RETURNING t.* AS whatever, 7 AS id",
        ),
        // Confirmed on PostgreSQL 18.4: valid syntax, returns every column of "tgt". The comment
        // sits outside the parentheses, on the far side of the closing ")", and the item is still a star,
        // so a later item is not shifted onto the wrong ResultSetMetaData column.
        StarGuardCase(
          description = "a trailing line comment outside a wrapping parenthesis still triggers the star guard",
          sql = "SELECT (tgt.*) -- c\n, id AS ident FROM tgt",
        ),
        // Confirmed on PostgreSQL 18.4: valid syntax, same shape as the line-comment case above.
        StarGuardCase(
          description = "a trailing block comment outside a wrapping parenthesis still triggers the star guard",
          sql = "SELECT (tgt.*) /*c*/, id AS ident FROM tgt",
        ),
        // Confirmed on PostgreSQL 18.4: valid syntax. "tgt./*c*/ *" is a star although
        // a comment sits between the dot and the star.
        StarGuardCase(
          description = "a comment between the dot and the star still triggers the star guard",
          sql = "SELECT tgt./*c*/ *, id AS ident FROM tgt",
        ),
        // An implicit alias (no "AS") is valid PostgreSQL syntax on a star ("u.* whatever" —
        // identical in meaning to "u.* AS whatever"; "u" aliases "users" to make the star
        // unambiguous). "SELECT u.* whatever, preferences FROM users u" returns every column of
        // "users" plus a trailing "preferences" item. The guard drops "preferences", whose result
        // position depends on how many columns "users" has.
        StarGuardCase(
          description = "an implicit alias on a star still triggers the star guard",
          sql = "SELECT u.* whatever, preferences FROM users u",
        ),
        StarGuardCase(
          description = "an explicit AS alias on a star keeps working alongside the implicit-alias check",
          sql = "SELECT u.* AS whatever, preferences FROM users u",
        ),
        // Confirmed on PostgreSQL 18.4: a double-quoted implicit alias is valid syntax. The
        // whitespace inside the quoted identifier ("My Col") must not be mistaken for the
        // boundary before it — only the whitespace between "u.*" and the quoted identifier counts.
        StarGuardCase(
          description = "a quoted implicit alias on a star still triggers the star guard",
          sql = """SELECT u.* "My Col", preferences FROM users u""",
        ),
        // "u.* whatever -- c" is valid PostgreSQL syntax (an implicit alias on a star, with a
        // trailing comment) and is a star like the zero-separator case below.
        StarGuardCase(
          description = "an implicit alias followed by a line comment still triggers the star guard",
          sql = "SELECT u.* whatever -- c\n, preferences FROM users u",
        ),
        // Same defect as the line-comment case above, block-comment form.
        StarGuardCase(
          description = "an implicit alias followed by a block comment still triggers the star guard",
          sql = "SELECT u.* whatever /*c*/, preferences FROM users u",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT
        // u.*/*c*/whatever, preferences FROM users u" returns 5 columns against a 4-column "users"
        // (the star's own expansion plus "preferences") — the comment is just as much a
        // non-separator as no separator at all.
        StarGuardCase(
          description =
          "a comment abutting an implicit alias with no surrounding whitespace now triggers the star guard",
          sql = "SELECT u.*/*c*/whatever, preferences FROM users u",
        ),
        // Confirmed against a real PostgreSQL 18 container:
        // "SELECT t.*-- c\nwhatever, id FROM t" against a 3-column "t" is valid and returns 4 real
        // columns (t's own 3 plus "id") — a star with an implicit alias.
        StarGuardCase(
          description = "a line comment abutting an implicit alias now triggers the star guard",
          sql = "SELECT t.*-- c\nwhatever, id FROM t",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT
        // u.*whatever, preferences FROM users u" returns 5 columns against a 4-column "users" — the
        // star's own expansion plus "preferences" — with the implicit alias "whatever" ignored.
        StarGuardCase(
          description = "a zero-separator implicit alias abutting a star now triggers the star guard",
          sql = "SELECT u.*whatever, preferences FROM users u",
        ),
        // The double-quoted identifier "my*table" (a table literally named that) contains a "*" character.
        // Confirmed against a real PostgreSQL 18.4 container: `SELECT "my*table".*z, c FROM
        // "my*table"` is valid and returns 3 columns (the star's own 2 plus "c"), implicit alias "z"
        // ignored.
        StarGuardCase(
          description =
          "a star qualified by a double-quoted identifier containing a literal star character is found lexically",
          sql = """SELECT "my*table".*z, c FROM "my*table"""",
        ),
        // Same regression guard as above, without the trailing implicit alias.
        StarGuardCase(
          description = "a star qualified by a double-quoted identifier containing a literal star character, no alias",
          sql = """SELECT "my*table".*, c FROM "my*table"""",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT DISTINCT ON (a) t.*, a FROM t"
        // returns 3 columns (t's own 2 plus "a") against a 2-column "t" — a star, with no implicit
        // alias here.
        StarGuardCase(
          description = "a DISTINCT ON prefix before a qualified star still triggers the star guard",
          sql = "SELECT DISTINCT ON (a) t.*, a FROM t",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT (t).*, a FROM t" returns 3
        // columns (t's own 2 plus "a") — PostgreSQL's composite-value-expansion idiom, distinct from
        // the wrapping-parenthesis case ("(tgt.*)") this guard also handles: here only "(t)" is
        // parenthesized, not the whole item, so the wrapping-paren unwrap loop never fires and the
        // item's full text ends in ".*" directly, matching the plain suffix check.
        StarGuardCase(
          description = "a parenthesized composite expansion still triggers the star guard",
          sql = "SELECT (t).*, a FROM t",
        ),
        // Same idiom as above, over a function call — confirmed
        // against a real PostgreSQL 18.4 container: "SELECT (f2(1)).*, a FROM t" returns 3 columns
        // ("f2" is a real function returning a row of "t"'s shape).
        StarGuardCase(
          description = "a parenthesized composite expansion of a function call still triggers the star guard",
          sql = "SELECT (f2(1)).*, a FROM t",
        ),
        StarGuardCase(
          description = "a parenthesized composite expansion in RETURNING still triggers the star guard",
          sql = "UPDATE t SET a = a RETURNING (t).*, a",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: `SELECT U&"my*table".*, c FROM
        // "my*table"` returns 3 columns (the star's own 2 plus "c") -- the "U&" Unicode-escape prefix
        // on the quoted identifier is ordinary PostgreSQL syntax.
        StarGuardCase(
          description = "a Unicode-escape quoted identifier qualifying a star still triggers the star guard",
          sql = """SELECT U&"my*table".*, c FROM "my*table"""",
        ),
        // "(t).*x" is a composite expansion followed directly by the implicit alias "x".
        StarGuardCase(
          description = "an implicit alias on a parenthesized composite expansion still triggers the star guard",
          sql = "SELECT (t).*x, a FROM t",
        ),
        // Same as the no-alias DISTINCT ON case above, with the ordinary qualified-star-with-implicit-alias
        // shape of "u.*whatever".
        StarGuardCase(
          description = "an implicit alias on a DISTINCT ON qualified star still triggers the star guard",
          sql = "SELECT DISTINCT ON (a) t.*x, a FROM t",
        ),
        StarGuardCase(
          description = "a Unicode-escape quoted identifier with an implicit alias still triggers the star guard",
          sql = """SELECT U&"my*table".*z, c FROM "my*table"""",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT (t.*::t).* whatever, a FROM t"
        // returns 3 columns (t's own 2 plus "a") against a 2-column "t". The qualifier "(t.*::t)."
        // contains its own star (inside the cast expression, a leftover "t.*" that Postgres accepts
        // as an argument to the composite cast).
        StarGuardCase(
          description =
          "a star qualifier that itself contains a star via a parenthesized cast still triggers the star guard",
          sql = "SELECT (t.*::t).* whatever, a FROM t",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT (ARRAY[t.*])[1].* whatever, a
        // FROM t" returns 3 columns (t's own 2 plus "a"). The qualifier "(ARRAY[t.*])[1]." contains
        // its own star inside the ARRAY literal.
        StarGuardCase(
          description =
          "a star qualifier that itself contains a star via an ARRAY subscript still triggers the star guard",
          sql = "SELECT (ARRAY[t.*])[1].* whatever, a FROM t",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT (f(a*2)).* z, a FROM t" returns
        // 3 columns (t's own 2 plus "a") — "f" is a real function returning a row of "t"'s shape. The
        // qualifier "(f(a*2))." contains an unrelated multiplication star inside the function call's
        // own argument.
        StarGuardCase(
          description = "a star qualifier containing a star via multiplication inside a function call " +
            "still triggers the star guard",
          sql = "SELECT (f(a*2)).* z, a FROM t",
        ),
        // Same shape as the ARRAY-subscript case above, with no separator before the implicit alias
        // — the same shape, for this qualifier. Confirmed against a real PostgreSQL 18.4 container:
        // "SELECT (ARRAY[t.*])[1].*whatever, a FROM t" returns the same 3 columns.
        StarGuardCase(
          description =
          "a zero-separator implicit alias on a star qualifier containing its own star still triggers the star guard",
          sql = "SELECT (ARRAY[t.*])[1].*whatever, a FROM t",
        ),
        // "(u.*)whatever" parenthesizes only "u.*", not the trailing alias. Confirmed against a real PostgreSQL 18.4
        // container: "SELECT (u.*) whatever, preferences FROM users u" returns 5 columns against a
        // 4-column "users" (the star's own expansion plus "preferences").
        StarGuardCase(
          description = "a star wrapped only up to its implicit alias still triggers the star guard",
          sql = "SELECT (u.*) whatever, preferences FROM users u",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT ((t.*)) x, a FROM t" returns 3
        // columns (t's own 2 plus "a") against a 2-column "t".
        StarGuardCase(
          description = "a doubly-wrapped star with an implicit alias still triggers the star guard",
          sql = "SELECT ((t.*)) x, a FROM t",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "UPDATE t SET a = 1 RETURNING (t.*) x,
        // a" returns 3 columns (t's own 2 plus "a").
        StarGuardCase(
          description = "a wrapped star with an implicit alias in RETURNING still triggers the star guard",
          sql = "UPDATE t SET a = 1 RETURNING (t.*) x, a",
        ),
        // The U&-lookahead ordering guard: this is the exact shape that breaks if the bare-identifier
        // rule is tried before the Unicode-escape rule when searching for the trailing alias segment
        // — a naive forward walk would match "U" alone as a one-character identifier segment, then
        // separately match "\"a\"" as a later segment, making the alias appear to end at the quoted
        // identifier while leaving a dangling "U&" attached to the prefix (`u.*U&`), which is not
        // star-shaped at all. Trying the Unicode-escape rule first consumes "U&\"a\"" as one segment,
        // so the prefix correctly reduces to "u.*". Confirmed against a real PostgreSQL 18.4
        // container: `SELECT u.* U&"a", preferences FROM users u` returns 5 columns against a
        // 4-column "users".
        StarGuardCase(
          description = "a Unicode-escape identifier as the implicit alias itself still triggers the star guard",
          sql = """SELECT u.* U&"a", preferences FROM users u""",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: `SELECT t.* U&"d!0061t" UESCAPE '!', a
        // FROM t` returns 3 columns (t's own 2 plus "a") against a 2-column "t" — PostgreSQL merges
        // the Unicode-escape identifier, the UESCAPE keyword, and its single-quoted escape-character
        // string into one lexical unit (here spelling the same identifier "U&\"data\"" would, via the
        // custom "!" escape character), so the whole thing is the implicit alias.
        StarGuardCase(
          description = "a Unicode-escape alias with a UESCAPE clause still triggers the star guard",
          sql = """SELECT t.* U&"d!0061t" UESCAPE '!', a FROM t""",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: `SELECT U&"!0074" UESCAPE '!'.* x, a
        // FROM t` returns 3 columns (t's own 2 plus "a"). Here the UESCAPE-extended Unicode-escape
        // identifier is the qualifier, not the alias.
        StarGuardCase(
          description = "a Unicode-escape qualifier with a UESCAPE clause still triggers the star guard",
          sql = """SELECT U&"!0074" UESCAPE '!'.* x, a FROM t""",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT ALL t.* x, a FROM t" returns 3
        // columns (t's own 2 plus "a"). The ALL quantifier is not part of the item.
        StarGuardCase(
          description =
          "an ALL quantifier before a qualified star with an implicit alias still triggers the star guard",
          sql = "SELECT ALL t.* x, a FROM t",
        ),
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT all2.* a, p FROM all2" returns 3
        // columns (all2's own 2 plus "p") against a real table named "all2". "all2" is the table name.
        StarGuardCase(
          description =
          "a star on a table whose name starts with the letters of the ALL quantifier still triggers the star guard",
          sql = "SELECT all2.* a, p FROM all2",
        ),
        // Written as a Kotlin unicode escape (\u0301, COMBINING ACUTE ACCENT) so the intent survives
        // any editor, and so a precomposed character (which already works and would not exercise this
        // case) never sneaks in: the alias is "p", "r", "e", COMBINING ACUTE ACCENT (U+0301), "f", "s"
        // -- the NFD spelling of "prefs" with an accent on the "e" -- not the single precomposed
        // "e-acute" codepoint. PostgreSQL's lexer accepts any byte >= 0x80 in an unquoted identifier;
        // a combining mark (Unicode category Mn) is one such byte, but is not a Unicode "letter"
        // (Char.isLetter() rejects it). Confirmed against a real PostgreSQL 18.4 container (via
        // psql -f, to avoid shell/encoding mangling the input): this exact alias (NFD) on "users"
        // returns 5 columns against a 4-column "users".
        StarGuardCase(
          description = "an implicit alias written in NFD with a combining mark still triggers the star guard",
          sql = "SELECT u.* pre\u0301fs, preferences FROM users u",
        ),
        // Written as a Kotlin unicode escape (\u20AC, the Euro sign). Confirmed against a real
        // PostgreSQL 18.4 container: this exact query returns 3 columns (t's own 2 plus "a") against
        // a 2-column "t" -- a currency sign is Unicode category Sc (symbol), not a letter, but
        // PostgreSQL's lexer still accepts it (any byte >= 0x80) as an unquoted identifier character.
        StarGuardCase(
          description = "an implicit alias containing a currency symbol still triggers the star guard",
          sql = "SELECT t.* \u20ACtotal, a FROM t",
        ),
        // Written as a Kotlin unicode escape (\u00A9, the copyright sign). Confirmed against a real
        // PostgreSQL 18.4 container: this exact query returns 3 columns (t's own 2 plus "a") -- the
        // copyright sign (Unicode category So, symbol/other) is, on its own, a complete legal
        // unquoted identifier in PostgreSQL.
        StarGuardCase(
          description = "an implicit alias that is a bare symbol still triggers the star guard",
          sql = "SELECT t.* \u00A9, a FROM t",
        ),
        // Written as a Kotlin unicode escape surrogate pair (\uD83D\uDE80, the rocket emoji,
        // U+1F680 -- a supplementary-plane character). Confirmed against a real PostgreSQL 18.4
        // container: this exact query returns 5 columns against a 4-column "users". A
        // supplementary-plane character is a surrogate pair in a Kotlin/UTF-16 String -- both code
        // units are >= 0x80, so no special surrogate-aware handling is needed for the per-character
        // loop to consume it correctly.
        StarGuardCase(
          description = "an implicit alias that is a supplementary-plane character still triggers the star guard",
          sql = "SELECT u.* \uD83D\uDE80, preferences FROM users u",
        ),
        // Same alias as the SELECT currency-symbol case above, RETURNING form. Confirmed against a
        // real PostgreSQL 18.4 container: this exact statement returns 3 columns (t's own 2 plus
        // "a").
        StarGuardCase(
          description = "an implicit alias containing a currency symbol in RETURNING still triggers the star guard",
          sql = "UPDATE t SET a = 1 RETURNING t.* \u20ACtotal, a",
        ),
        // A non-ASCII alias ending in a combining mark, which is an identifier character. Written as a Kotlin
        // unicode escape (\u0301, COMBINING ACUTE ACCENT): the alias is "c", "a", "f", "e", COMBINING
        // ACUTE ACCENT (U+0301) -- the NFD spelling of "cafe" with an accent on the "e" -- not the
        // precomposed codepoint. Confirmed against a real PostgreSQL 18.4 container: this exact query
        // returns 3
        // columns (t's own 2 plus "a").
        StarGuardCase(
          description = "a zero-separator NFD alias still triggers the star guard",
          sql = "SELECT t.*cafe\u0301, a FROM t",
        ),
        // PostgreSQL numeric literals use ASCII digits exclusively, so a non-ASCII digit starting a
        // qualifier can only be an identifier's first character. The table
        // here is literally named \u0663 (ARABIC-INDIC DIGIT THREE), written as a Kotlin unicode
        // escape. Confirmed against a real PostgreSQL 18.4 container with a real table named \u0663:
        // "SELECT \u0663.* x, a FROM \u0663" returns 3 columns (\u0663's own 2 plus "a").
        StarGuardCase(
          description = "a qualifier starting with a non-ASCII digit still triggers the star guard",
          sql = "SELECT \u0663.* x, a FROM \u0663",
        ),
        // Same regression guard as above, zero-separator implicit-alias form. Confirmed against a
        // real PostgreSQL 18.4 container: "SELECT \u0663.*x, a FROM \u0663" returns the same 3
        // columns.
        StarGuardCase(
          description =
          "a zero-separator alias on a qualifier starting with a non-ASCII digit still triggers the star guard",
          sql = "SELECT \u0663.*x, a FROM \u0663",
        ),
        // The table alias here is "x", "\u20AC" (the Euro sign, >= 0x80 but neither a letter nor a digit),
        // "9", written as Kotlin unicode escapes. Confirmed against a real PostgreSQL 18.4
        // container: "SELECT x\u20AC9.* w, preferences FROM users x\u20AC9" returns 5 columns
        // against a 4-column "users".
        StarGuardCase(
          description =
          "a qualifier containing a currency symbol followed by an ASCII digit still triggers the star guard",
          sql = "SELECT x\u20AC9.* w, preferences FROM users x\u20AC9",
        ),
        // Same regression guard as above, with a different >= 0x80-but-not-letter-or-digit character:
        // "\u00B9" (SUPERSCRIPT ONE, Unicode category No -- number, other -- still not a letter or a
        // digit per isIdentifierChar's isLetterOrDigit()). The table alias here is "x", "\u00B9",
        // "9", written as Kotlin unicode escapes. Confirmed against a real PostgreSQL 18.4 container:
        // "SELECT x¹9.* w, a FROM t x¹9" returns 3 columns (t's own 2 plus "a").
        StarGuardCase(
          description =
          "a qualifier containing a superscript digit followed by an ASCII digit still triggers the star guard",
          sql = "SELECT x\u00B99.* w, a FROM t x\u00B99",
        ),
        // Same regression guard again, this time with a combining mark: the qualifier is
        // "c", "a", "f", "e", COMBINING ACUTE ACCENT (\u0301), "2" -- the NFD spelling of "cafe" with
        // an accent on the "e", followed by an ASCII digit, written as Kotlin unicode escapes.
        // Confirmed against a real PostgreSQL 18.4 container: "SELECT café2.* w, a FROM t café2" (NFD)
        // returns 3 columns (t's own 2 plus "a").
        StarGuardCase(
          description = "a qualifier written in NFD followed by an ASCII digit still triggers the star guard",
          sql = "SELECT cafe\u03012.* w, a FROM t cafe\u03012",
        ),
        // "x€ E'a\'b'" has a real space between "€" and the
        // standalone "E", so the whole "E'a\'b'" is one backslash-escaped string and the item ends in the
        // star ".* y". parseSelectItems drops it and everything after it, giving the fail-safe emptyList()
        // this test asserts.
        StarGuardCase(
          description =
          "a typed-literal call with a standalone E before a backslash-quote does not manufacture a star item",
          sql = "SELECT (f(x€ E'a\\'b')).* y, id FROM t",
        ),
        // "1 - -1" has two "-" tokens genuinely separated by a space and is not a comment. Confirmed
        // against a real PostgreSQL 18.4 container: this query returns 4 columns (f1, f2, id, name)
        // against a 2-column "t" — the star and everything before it must be dropped, since two items
        // come after it.
        StarGuardCase(
          description =
          "a negative literal subtraction inside a ROW composite star qualifier does not manufacture a --comment",
          sql = "SELECT (ROW(1, 2 - -1)).* y, id, name FROM t",
        ),
        // Same fusion class as the ROW case above, inside a function call's own argument.
        // Confirmed against a real PostgreSQL 18.4 container: this query returns 3
        // columns (f1, f2, id) — f(int) returns a 2-column composite.
        StarGuardCase(
          description =
          "a negative literal subtraction inside a function-call star qualifier does not manufacture a --comment",
          sql = "SELECT (f(1 - -1)).* y, id FROM t",
        ),
        // Same fusion class, inside a CAST's ROW argument. Confirmed against a real PostgreSQL 18.4
        // container: this query returns 3 columns (f1, f2, id).
        StarGuardCase(
          description = "a negative literal subtraction inside a CAST star qualifier does not manufacture a --comment",
          sql = "SELECT (CAST(ROW(1 - -1, 2) AS pair)).* y, id FROM t",
        ),
        // Same fusion class, inside an ARRAY subscript. Confirmed against a real PostgreSQL 18.4
        // container: this query returns 3 columns (id, name, id) — "t.*" expands to id/name, then
        // the trailing bare "id" is a separate item after the star.
        StarGuardCase(
          description =
          "a negative literal subtraction inside an array-subscript star qualifier does not manufacture a --comment",
          sql = "SELECT (ARRAY[t])[1 - -1].* y, id FROM t",
        ),
        // "xq E'...'" ("xq" — a domain over text — used as a type name in PostgreSQL's own
        // "typename Sconst" typed-literal form, genuinely separated from the following E-string by a
        // space). Confirmed against a real PostgreSQL 18.4 container: this query returns 3 columns (f1, f2,
        // id) — f(xq) returns a 2-column composite.
        StarGuardCase(
          description = "a space-separated E-string typed literal is not fused into a standalone-E escape string",
          sql = "SELECT (f(xq E'a\\'b')).* y, id FROM t",
        ),
        // "xq $q$...$q$" is a typed literal with a dollar-quoted string, separated from the type name by a
        // space. Confirmed against a real PostgreSQL 18.4 container: this query returns 3 columns (f1, f2, id).
        StarGuardCase(
          description = "a space-separated dollar-quoted typed literal is not fused into the identifier before it",
          sql = "SELECT (f(xq \$q\$a'b\$q\$)).* y, id FROM t",
        ),
      )

      @ParameterizedTest(name = "{0}")
      @MethodSource("notMistakenForStarGuardCases")
      fun `is not mistaken for the star guard`(testCase: NotStarGuardCase) {
        assertThat(parseSelectItems(testCase.sql)).containsExactly(*testCase.expected.toTypedArray())
      }

      fun notMistakenForStarGuardCases(): List<NotStarGuardCase> = listOf(
        // The star guard keeps items before a star, since an item like "id" here has a positionally
        // exact mapping regardless of the star's own unknown expansion width.
        NotStarGuardCase(
          description = "items strictly before a later star are kept, not discarded",
          sql = "SELECT id AS ident, t.* FROM t",
          expected = listOf(
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "RETURNING items strictly before a later star are kept, not discarded",
          sql = "UPDATE t SET x = 1 RETURNING id AS ident, t.*",
          expected = listOf(
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "count star is not mistaken for the star guard even with internal whitespace",
          sql = "SELECT count( * ) AS total, id FROM t",
          expected = listOf(
            SelectItem("count( * )", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "a cast on a star-shaped expression is not mistaken for the star guard",
          sql = "SELECT t.*::text AS whatever, id FROM t",
          expected = listOf(
            SelectItem("t.*::text", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "count star with an implicit alias is not mistaken for the star guard",
          sql = "SELECT count(*) total, id FROM t",
          expected = listOf(
            SelectItem("count(*)", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "a cast on a star with no whitespace is unaffected by the implicit-alias check",
          sql = "SELECT t.*::text, id FROM t",
          expected = listOf(
            SelectItem("t.*::text", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "a cast on a star with a comment immediately before it is not mistaken for the star guard",
          sql = "SELECT t.*/*c*/::text, a FROM t",
          expected = listOf(
            SelectItem("t.*/*c*/::text", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        // On PostgreSQL 18, "SELECT t.* ::text, a FROM t" is valid and returns 2 columns (t, a),
        // with no star expansion.
        NotStarGuardCase(
          description = "a whitespace-separated cast on a star is not mistaken for an implicit alias",
          sql = "SELECT t.* ::text, a FROM t",
          expected = listOf(
            SelectItem("t.* ::text", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        // A different spaced-cast shape from the string-text case above, to confirm the "::" check
        // is not narrowly tied to one particular cast target type.
        NotStarGuardCase(
          description = "a whitespace-separated array cast on a star is not mistaken for an implicit alias",
          sql = "SELECT t.* ::integer[], a FROM t",
          expected = listOf(
            SelectItem("t.* ::integer[]", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        // On PostgreSQL 18, "SELECT t.*-- c\n::text, a FROM t" is valid and returns 2 columns (t, a),
        // with no star expansion. The "::" stays attached to "t.*".
        NotStarGuardCase(
          description = "a line comment between a star and its cast is not mistaken for an implicit alias",
          sql = "SELECT t.*-- c\n::text, a FROM t",
          expected = listOf(
            SelectItem("t.*-- c\n::text", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        // A single item short-circuits the star guard entirely (there is nothing after it to
        // shift), so truncation does not apply. The whole clause
        // still parses correctly, with the comment swallowing nothing past it.
        NotStarGuardCase(
          description = "an implicit alias followed by a comment with no following item is unaffected by truncation",
          sql = "SELECT u.* whatever -- c\nFROM users u",
          expected = listOf(
            SelectItem("u.*", null, null, isStar = true),
          ),
        ),
        // Regression guard: PostgreSQL identifiers cannot start with a digit, so "2." is not a valid
        // qualifier — this is ordinary multiplication ("2 * 3"), with "3 lbl" an implicit alias on
        // the numeric literal "3" (PostgreSQL 18.4: "SELECT 2.*3 lbl, a FROM t" returns 2 columns).
        // A qualifier is a letter or underscore, then identifier characters, so "2." is not one.
        NotStarGuardCase(
          description = "a digit-leading qualifier before a star is arithmetic, not a star",
          sql = "SELECT 2.*3 lbl, a FROM t",
          expected = listOf(
            SelectItem("2.*3", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        NotStarGuardCase(
          description = "a digit-leading qualifier before a star with explicit whitespace is arithmetic, not a star",
          sql = "SELECT 2. * 3 AS x, a FROM t",
          expected = listOf(
            SelectItem("2. * 3", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        // On PostgreSQL 18.4, "SELECT 2.*a lbl, a FROM t" is valid arithmetic ("2 * a AS lbl"),
        // returning 2 columns.
        NotStarGuardCase(
          description = "a digit-leading qualifier before a star with an implicit alias remains arithmetic, not a star",
          sql = "SELECT 2.*a lbl, a FROM t",
          expected = listOf(
            SelectItem("2.*a", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        // On PostgreSQL 18.4, "SELECT count(*) * 2 tot, (SELECT 1) AS id FROM t" returns 2 columns
        // (tot, id).
        NotStarGuardCase(
          description = "an aggregate star multiplied by a constant is not mistaken for the star guard",
          sql = "SELECT count(*) * 2 tot, (SELECT 1) AS id FROM t",
          expected = listOf(
            SelectItem("count(*) * 2", null, null),
            SelectItem("(SELECT 1)", null, null),
          ),
        ),
        // On PostgreSQL 18.4, "SELECT ALL 2.*a lbl, b FROM t" is valid arithmetic ("ALL 2 * a AS lbl"),
        // returning 2 columns.
        NotStarGuardCase(
          description = "an ALL quantifier fused with a digit-leading qualifier is still arithmetic, not a star",
          sql = "SELECT ALL 2.*a lbl, b FROM t",
          expected = listOf(
            SelectItem("2.*a", null, null),
            SelectItem("b", "b", null),
          ),
        ),
        // On PostgreSQL 18.4, "SELECT DISTINCT a, b FROM t" returns 2 columns (a, b) -- a plain
        // DISTINCT with no ON (...) clause. The first item's expression/columnName must be the bare
        // "a", with no quantifier text glued onto it ("DISTINCTa").
        NotStarGuardCase(
          description = "a DISTINCT quantifier with no ON clause is not part of the first item",
          sql = "SELECT DISTINCT a, b FROM t",
          expected = listOf(
            SelectItem("a", "a", null),
            SelectItem("b", "b", null),
          ),
        ),
        // The digit-led qualifier "2" makes this multiplication regardless of what the trailing alias is
        // made of (here an "x" followed by a COMBINING ACUTE ACCENT, \u0301, written as a Kotlin unicode
        // escape). Checked against PostgreSQL 18.4 with a temp table (this identifier isn't a real
        // column anywhere, so a plain SELECT against "t" can't execute to confirm a row, only a
        // parse): a column named by this exact identifier plus "SELECT 2.*<that identifier> lbl, a
        // FROM temp_check" returns 2 columns (lbl, a) -- ordinary arithmetic, not a star, matching
        // the identically-shaped ASCII "2.*a lbl, a FROM t" case above.
        NotStarGuardCase(
          description =
          "a digit-leading qualifier before a star with an NFD implicit alias remains arithmetic, not a star",
          sql = "SELECT 2.*x\u0301 lbl, a FROM t",
          expected = listOf(
            SelectItem("2.*x\u0301", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        NotStarGuardCase(
          description = "a multiplication expression with a trailing line comment is not mistaken for the star guard",
          sql = "SELECT a * b -- c\n, id FROM t",
          expected = listOf(
            SelectItem("a * b", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description =
          "a multiplication expression with an implicit alias and internal block comment is not mistaken as a star",
          sql = "SELECT 2 * x /*c*/ total, id FROM t",
          expected = listOf(
            SelectItem("2 * x", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description =
          "a function-call multiplication expression with a trailing line comment is not mistaken for the star guard",
          sql = "SELECT sum(x) * 2 -- c\n, id FROM t",
          expected = listOf(
            SelectItem("sum(x) * 2", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "a cast on a star with a trailing line comment is not mistaken for the star guard",
          sql = "SELECT t.*::text -- c\n, id FROM t",
          expected = listOf(
            SelectItem("t.*::text", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description =
          "count star with an implicit alias and a trailing line comment is not mistaken for the star guard",
          sql = "SELECT count(*) total -- c\n, id FROM t",
          expected = listOf(
            SelectItem("count(*)", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description =
          "a multiplication expression split across a line comment and a newline is not mistaken for the star guard",
          sql = "SELECT a * -- c\n b, id FROM t",
          expected = listOf(
            SelectItem("a * -- c\n b", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "a parenthesized cast with an implicit alias is not mistaken for the star guard",
          sql = "SELECT (a*b)::int lbl, id FROM t",
          expected = listOf(
            SelectItem("(a*b)::int", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "a string literal containing a star with an implicit alias is not mistaken for the star guard",
          sql = "SELECT 'lit *' foo, id FROM t",
          expected = listOf(
            SelectItem("'lit *'", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description =
          "an array-indexed multiplication expression with an implicit alias is not mistaken for the star guard",
          sql = "SELECT arr[1] * 2 tot, id FROM t",
          expected = listOf(
            SelectItem("arr[1] * 2", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "a lone star with no implicit alias is unaffected by the implicit-alias check",
          sql = "SELECT * FROM t",
          expected = listOf(
            SelectItem("*", null, null, isStar = true),
          ),
        ),
        NotStarGuardCase(
          description = "a lone qualified star with no implicit alias is unaffected by the implicit-alias check",
          sql = "SELECT t.* FROM t",
          expected = listOf(
            SelectItem("t.*", null, null, isStar = true),
          ),
        ),
        // "preferences prefs" is a real implicit alias (no "AS") on the column reference
        // "preferences", so the first item keeps its column name and the guard does not fire.
        NotStarGuardCase(
          description =
          "an implicit alias on a non-star column is not mistaken for the star guard and keeps its column name",
          sql = "SELECT preferences prefs, id FROM t",
          expected = listOf(
            SelectItem("preferences", "preferences", null),
            SelectItem("id", "id", null),
          ),
        ),
        // columnName is `null` for the bare literal "5": it is a numeric literal, not a column reference.
        NotStarGuardCase(
          description = "WITH clause main query with no FROM clause",
          sql = "WITH c AS (SELECT 1) SELECT 5, 'x'",
          expected = listOf(
            SelectItem("5", null, null),
            SelectItem("'x'", null, null),
          ),
        ),
        // With no WITH clause, the first branch's SELECT is the first SELECT in the statement.
        NotStarGuardCase(
          description = "UNION picks the first branch's columns",
          sql = "SELECT a FROM x UNION SELECT b FROM y",
          expected = listOf(
            SelectItem("a", "a", null),
          ),
        ),
      )
    }
  }

  @Nested
  inner class QuotedColumnReferenceTest {

    @Test
    fun `an unqualified quoted column reference resolves to its logical name`() {
      val result = parseSelectItems("SELECT \"ux\" FROM c").single()
      assertThat(result.columnName).isEqualTo("ux")
      assertThat(result.isColumnNameQuoted).isTrue()
      assertThat(result.tableName).isNull()
    }

    @Test
    fun `a quoted column reference containing a space resolves to its logical name`() {
      val result = parseSelectItems("SELECT \"My Col\" FROM c").single()
      assertThat(result.columnName).isEqualTo("My Col")
      assertThat(result.isColumnNameQuoted).isTrue()
    }

    @Test
    fun `a quoted column reference with a doubled-quote escape is not mangled, and collapses to one literal quote`() {
      // On PostgreSQL 18, ResultSetMetaData.getColumnName for
      // `SELECT "He""llo" FROM (SELECT 1 AS "He""llo") s` reports `He"llo` (no surrounding quotes,
      // the doubled "" already collapsed to a single literal ") -- this must agree exactly, since
      // JdbcAnalyzer.buildResultColumns' originalName prefers this value over rsmd.getColumnName.
      val result = parseSelectItems("SELECT \"He\"\"llo\" FROM c").single()
      assertThat(result.columnName).isEqualTo("He\"llo")
      assertThat(result.isColumnNameQuoted).isTrue()
    }

    @Test
    fun `an unquoted table qualifier with a quoted column resolves both parts independently`() {
      val result = parseSelectItems("SELECT t.\"ux\" FROM c").single()
      assertThat(result.tableName).isEqualTo("t")
      assertThat(result.isTableNameQuoted).isFalse()
      assertThat(result.columnName).isEqualTo("ux")
      assertThat(result.isColumnNameQuoted).isTrue()
    }

    @Test
    fun `a quoted table qualifier with an unquoted column resolves both parts independently`() {
      val result = parseSelectItems("SELECT \"t\".ux FROM c").single()
      assertThat(result.tableName).isEqualTo("t")
      assertThat(result.isTableNameQuoted).isTrue()
      assertThat(result.columnName).isEqualTo("ux")
      assertThat(result.isColumnNameQuoted).isFalse()
    }

    @Test
    fun `a fully quoted qualified reference resolves both parts as quoted`() {
      val result = parseSelectItems("SELECT \"t\".\"ux\" FROM c").single()
      assertThat(result.tableName).isEqualTo("t")
      assertThat(result.isTableNameQuoted).isTrue()
      assertThat(result.columnName).isEqualTo("ux")
      assertThat(result.isColumnNameQuoted).isTrue()
    }

    @Test
    fun `a quoted qualifier containing a literal dot is not mistaken for a table-column separator`() {
      // "my.table" is one quoted identifier, not "my" qualifying "table" -- the internal dot must
      // stay inside the quoted span.
      val result = parseSelectItems("SELECT \"my.table\".ux FROM c").single()
      assertThat(result.tableName).isEqualTo("my.table")
      assertThat(result.isTableNameQuoted).isTrue()
      assertThat(result.columnName).isEqualTo("ux")
    }

    @Test
    fun `an unquoted reference is not flagged as quoted`() {
      val result = parseSelectItems("SELECT ux FROM c").single()
      assertThat(result.columnName).isEqualTo("ux")
      assertThat(result.isColumnNameQuoted).isFalse()
      assertThat(result.tableName).isNull()
    }

    @Test
    fun `an empty quoted identifier is rejected by PostgreSQL, so there are no items`() {
      // PostgreSQL rejects a zero-length delimited identifier outright ("SELECT """" FROM t" is a
      // syntax error).
      val result = parseSelectItems("SELECT \"\" FROM c")
      assertThat(result).isEmpty()
    }
  }

  @Nested
  inner class IdentifierTruncation {

    @Test
    fun `an over-length unquoted column name is truncated to 63 bytes at parse time`() {
      // catalog.findColumn matches columnName by exact == against a server-truncated name.
      val overLongName = "a".repeat(70)
      val result = parseSelectItems("SELECT $overLongName FROM t").single()
      assertThat(result.columnName).isEqualTo("a".repeat(63))
    }

    @Test
    fun `an over-length quoted column name is truncated to 63 bytes at parse time`() {
      val overLongName = "a".repeat(70)
      val result = parseSelectItems("SELECT \"$overLongName\" FROM t").single()
      assertThat(result.columnName).isEqualTo("a".repeat(63))
    }

    @Test
    fun `an over-length table qualifier is truncated to 63 bytes at parse time`() {
      val overLongTable = "b".repeat(70)
      val result = parseSelectItems("SELECT $overLongTable.col FROM t").single()
      assertThat(result.tableName).isEqualTo("b".repeat(63))
    }

    @Test
    fun `an over-length quoted alias is raw, not truncated, and still ends in its quote`() {
      // Truncating text that still carries its quotes would count those bytes and could drop the
      // closing one. The alias stays raw here and is truncated after folding instead.
      val overLongName = "a".repeat(70)
      val result = parseOutputItemsWithAlias("SELECT x AS \"$overLongName\"").single()
      assertThat(result.alias).isEqualTo("\"$overLongName\"")
      assertThat(result.alias!!.endsWith("\"")).isTrue()
    }
  }

  /** The token after an explicit `AS`, as [parseOutputItemsWithAlias] reports it in `alias`. */
  @Nested
  inner class ExplicitAliasToken {

    @Test
    fun `a doubled-quote escape inside a quoted alias is not mistaken for its closing quote`() {
      val result = parseOutputItemsWithAlias("SELECT x AS \"He\"\"llo\" FROM t").single()
      assertThat(result.alias).isEqualTo("\"He\"\"llo\"")
    }

    @Test
    fun `a bare unquoted alias is captured whole`() {
      val result = parseOutputItemsWithAlias("SELECT x AS ux FROM t").single()
      assertThat(result.alias).isEqualTo("ux")
    }

    @Test
    fun `an alias token starting with a digit is rejected by PostgreSQL, so there are no items`() {
      assertThat(parseOutputItemsWithAlias("SELECT x AS 1x FROM t")).isEmpty()
    }

    @Test
    fun `an unterminated quoted alias is rejected by PostgreSQL, so there are no items`() {
      assertThat(parseOutputItemsWithAlias("SELECT x AS \"abc FROM t")).isEmpty()
    }

    @Test
    fun `trailing text after the alias token is rejected by PostgreSQL, so there are no items`() {
      assertThat(parseOutputItemsWithAlias("SELECT x AS ux zz FROM t")).isEmpty()
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  internal inner class ItemExtent {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `each item keeps its whole expression and its alias`(testCase: ItemExtentCase) {
      val items = parseOutputItemsWithAlias(testCase.sql).map {
        ExpectedItem(it.selectItem.expression, it.alias, it.isAliasExplicit)
      }
      assertThat(items).isEqualTo(testCase.expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gluedShapes")
    fun `an item with no whitespace around it keeps its whole expression`(shape: GluedShape) {
      assertThat(extents(shape.sql)).isEqualTo(shape.expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gluedShapes")
    fun `the same item after a comma gives the same result`(shape: GluedShape) {
      assertThat(extents(shape.commaSql)).isEqualTo(shape.commaExpected)
    }

    private fun extents(sql: String): List<ExpectedItem> = parseOutputItemsWithAlias(sql).map {
      ExpectedItem(it.selectItem.expression, it.alias, it.isAliasExplicit)
    }

    fun gluedShapes(): List<GluedShape> = listOf(
      GluedShape("SELECT", "(a + b)", " AS total FROM t", "total", true),
      GluedShape("SELECT DISTINCT", "(a)", " FROM t"),
      GluedShape("UPDATE t SET a = 1 RETURNING", "(a + 1)", ""),
      GluedShape("SELECT", "\"a\"", " FROM t"),
      GluedShape("SELECT", "'x'", " FROM t"),
      GluedShape("SELECT", "-a", " FROM t"),
      GluedShape("SELECT", "*", "FROM t", isStar = true),
      GluedShape("SELECT", "(a)", "FROM t"),
      GluedShape("SELECT", "'x'", "FROM t"),
      GluedShape("SELECT", "\"a\"", "FROM t"),
      GluedShape("SELECT ", "a.*", "FROM t", isStar = true),
      GluedShape("UPDATE t SET a = 1 RETURNING", "*", "", isStar = true),
      GluedShape("SELECT DISTINCT ON (a)", "b", " FROM t"),
      GluedShape("SELECT ALL", "(a)", " FROM t"),
      GluedShape("SELECT", "(a)", " FROM t; SELECT 2"),
      GluedShape("(SELECT", "(a)", " FROM t)"),
      GluedShape("SELECT", "(a)", " UNION SELECT b"),
    )

    fun cases(): List<ItemExtentCase> {
      val withoutTimeZone = "now()::timestamp without time zone"
      return listOf(
        ItemExtentCase("SELECT id, $withoutTimeZone FROM t", ExpectedItem("id"), ExpectedItem(withoutTimeZone)),
        ItemExtentCase("SELECT $withoutTimeZone, id FROM t", ExpectedItem(withoutTimeZone), ExpectedItem("id")),
        ItemExtentCase(
          "SELECT id, now()::time without time zone FROM t",
          ExpectedItem("id"),
          ExpectedItem("now()::time without time zone"),
        ),
        ItemExtentCase(
          "SELECT now()::time without time zone, id FROM t",
          ExpectedItem("now()::time without time zone"),
          ExpectedItem("id"),
        ),
        ItemExtentCase(
          "SELECT id, x::timestamp(3) without time zone FROM t",
          ExpectedItem("id"),
          ExpectedItem("x::timestamp(3) without time zone"),
        ),
        ItemExtentCase(
          "SELECT x::timestamp(3) without time zone, id FROM t",
          ExpectedItem("x::timestamp(3) without time zone"),
          ExpectedItem("id"),
        ),
        ItemExtentCase("SELECT $withoutTimeZone AS x FROM t", ExpectedItem(withoutTimeZone, "x", true)),
        ItemExtentCase("SELECT $withoutTimeZone x FROM t", ExpectedItem(withoutTimeZone, "x", false)),
        ItemExtentCase("UPDATE t SET a = 1 RETURNING $withoutTimeZone", ExpectedItem(withoutTimeZone)),
        ItemExtentCase("UPDATE t SET a = 1 RETURNING $withoutTimeZone AS x", ExpectedItem(withoutTimeZone, "x", true)),
        ItemExtentCase("UPDATE t SET a = 1 RETURNING $withoutTimeZone x", ExpectedItem(withoutTimeZone, "x", false)),
        ItemExtentCase(
          "SELECT id, U&'abc' UESCAPE '!' FROM t",
          ExpectedItem("id"),
          ExpectedItem("U&'abc' UESCAPE '!'"),
        ),
        ItemExtentCase(
          "SELECT U&'abc' UESCAPE '!', id FROM t",
          ExpectedItem("U&'abc' UESCAPE '!'"),
          ExpectedItem("id"),
        ),
        ItemExtentCase("SELECT U&'abc' UESCAPE '!' AS y FROM t", ExpectedItem("U&'abc' UESCAPE '!'", "y", true)),
        ItemExtentCase("SELECT U&'abc' UESCAPE '!' y FROM t", ExpectedItem("U&'abc' UESCAPE '!'", "y", false)),
        ItemExtentCase(
          "SELECT id, x::double precision FROM t",
          ExpectedItem("id"),
          ExpectedItem("x::double precision"),
        ),
        ItemExtentCase(
          "SELECT id, x::character varying(10) FROM t",
          ExpectedItem("id"),
          ExpectedItem("x::character varying(10)"),
        ),
        ItemExtentCase(
          "SELECT id, x::interval day to second FROM t",
          ExpectedItem("id"),
          ExpectedItem("x::interval day to second"),
        ),
        ItemExtentCase("SELECT id, x::int ARRAY[3] FROM t", ExpectedItem("id"), ExpectedItem("x::int ARRAY[3]")),
        ItemExtentCase("SELECT id, x::text[] FROM t", ExpectedItem("id"), ExpectedItem("x::text[]")),
        ItemExtentCase(
          "SELECT id, x LIKE 'a' ESCAPE '!' FROM t",
          ExpectedItem("id"),
          ExpectedItem("x LIKE 'a' ESCAPE '!'"),
        ),
        ItemExtentCase(
          "SELECT id, ts AT TIME ZONE tz FROM t",
          ExpectedItem("id"),
          ExpectedItem("ts AT TIME ZONE tz"),
        ),
        ItemExtentCase(
          "SELECT id, ts AT TIME ZONE tz z FROM t",
          ExpectedItem("id"),
          ExpectedItem("ts AT TIME ZONE tz", "z", false),
        ),
        ItemExtentCase("SELECT id, a + b c FROM t", ExpectedItem("id"), ExpectedItem("a + b", "c", false)),
        ItemExtentCase(
          "SELECT id, x IS DISTINCT FROM y FROM t",
          ExpectedItem("id"),
          ExpectedItem("x IS DISTINCT FROM y"),
        ),
        ItemExtentCase("SELECT id, x IS DISTINCT FROM y", ExpectedItem("id"), ExpectedItem("x IS DISTINCT FROM y")),
        ItemExtentCase(
          "SELECT id, count(*) FILTER (WHERE x) FROM t",
          ExpectedItem("id"),
          ExpectedItem("count(*) FILTER (WHERE x)"),
        ),
        ItemExtentCase(
          "SELECT id, sum(x) OVER (ORDER BY y) FROM t",
          ExpectedItem("id"),
          ExpectedItem("sum(x) OVER (ORDER BY y)"),
        ),
        ItemExtentCase("SELECT id, a \"b\" FROM t", ExpectedItem("id"), ExpectedItem("a", "\"b\"", false)),
        ItemExtentCase("SELECT id, a;", ExpectedItem("id"), ExpectedItem("a")),
        ItemExtentCase("(SELECT a) UNION (SELECT b)", ExpectedItem("a")),
        ItemExtentCase("SELECT id, a --c\nFROM t", ExpectedItem("id"), ExpectedItem("a")),
        ItemExtentCase("SELECT id, a /*c*/ FROM t", ExpectedItem("id"), ExpectedItem("a")),
        ItemExtentCase("SELECT a FROM t FOR UPDATE", ExpectedItem("a")),
        ItemExtentCase("SELECT a FROM t WINDOW w AS (ORDER BY a)", ExpectedItem("a")),
        ItemExtentCase("SELECT a INTO x FROM t", ExpectedItem("a")),
        ItemExtentCase("SELECT *", ExpectedItem("*")),
        ItemExtentCase("SELECT t.* x FROM t", ExpectedItem("t.*", "x", false)),
        ItemExtentCase("SELECT interval\n'1 day' FROM t", ExpectedItem("interval\n'1 day'")),
        ItemExtentCase("SELECT timestamp '2020-01-01' FROM t", ExpectedItem("timestamp '2020-01-01'")),
        ItemExtentCase("SELECT a, ?", ExpectedItem("a"), ExpectedItem("?")),
        ItemExtentCase("SELECT a, b; SELECT c, d", ExpectedItem("a"), ExpectedItem("b")),
      )
    }
  }
}
