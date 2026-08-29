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

/** One SQL input that must still trip [isStarItem]'s star guard, dropping to an empty list. */
internal data class StarGuardCase(val description: String, val sql: String) {
  override fun toString() = description
}

/** One SQL input that must NOT trip the star guard, resolving to [expected] instead. */
internal data class NotStarGuardCase(val description: String, val sql: String, val expected: List<SelectItem>) {
  override fun toString() = description
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
        SelectItem("*", null, null),
      )
    }

    @Test
    fun `extractAlias bails on an unmatched closing parenthesis instead of splitting off a wrong alias`() {
      // ") (bogus AS alias" has a stray ")" before any "(" — depth dips negative. Without a floor,
      // the subsequent "(" brings depth back to exactly 0, so the later "AS" is (wrongly) treated
      // as a top-level alias boundary, splitting into expression=") (bogus", alias="alias". Bailing
      // at the first negative dip instead returns the whole item unsplit, with no alias stripped —
      // an honest "could not parse this" over a confidently wrong split.
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id, ) (bogus AS alias")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
        SelectItem(") (bogus AS alias", null, null),
      )
    }

    @Test
    fun `RETURNING WITH OLD-NEW alias prologue is stripped before the first item`() {
      // PostgreSQL 18's `RETURNING WITH (OLD AS o, NEW AS n) o.x, n.x` — verified live to return
      // 2 columns. Without stripping the prologue, the first item's expression becomes
      // "WITH (OLD AS o, NEW AS n) o.x", which parseColumnReference cannot make sense of
      // (columnName/tableName null), and that unparsed text would be embedded verbatim in
      // generated KDoc.
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
      // Also a regression guard for the star-truncation guard: a LONE star has nothing after it
      // to shift, so the guard leaves it exactly as parseColumnReference already handles it —
      // this already passed before the fix.
      val result = parseSelectItems("SELECT * FROM users")
      assertThat(result).containsExactly(
        SelectItem("*", null, null),
      )
    }

    @Test
    fun `no SELECT or RETURNING returns empty list`() {
      val result = parseSelectItems("CALL my_proc()")
      assertThat(result).isEmpty()
    }

    @Test
    fun `WITH RECURSIVE CTE body SELECT is not mistaken for the main query's SELECT`() {
      // Reproduces #212: the CTE body's own SELECT ("SELECT label FROM parent_name") used to be
      // picked over the main query's SELECT ("SELECT id, name FROM new_parent") because the old
      // implementation searched for the first SELECT anywhere in the statement.
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
      // Regression guard for a defect this very fix introduced: RETURNING is NOT a reserved word
      // in PostgreSQL — it is legal as a column alias in a SELECT's target list — verified valid
      // syntax on PostgreSQL 18.4 ("CREATE TABLE t (returning int)" and "FROM users AS returning"
      // ARE rejected, so the alias position specifically is the hole). Before the main query's
      // OWN leading keyword was checked, this alias was mistaken for the RETURNING clause
      // keyword, misreading everything after it (the real "FROM users") as a single bogus item —
      // the same lost column-level-override failure class #212 exists to fix.
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
      // Fixes #215 shape 3: verified against a real PostgreSQL 18.4 container, "INSERT INTO users
      // (email, preferences) SELECT email AS returning, preferences FROM users RETURNING id,
      // email, preferences" returns exactly 3 columns (id, email, preferences). A plain
      // findTopLevelKeyword search returns the FIRST "returning"-shaped match, which is this
      // alias, not the real clause — mis-locating the RETURNING list entirely.
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
      // The symmetric case a naive "last match wins" rule would break: here the alias FOLLOWS the
      // real keyword, so findTopLevelReturningKeyword must still return the FIRST RETURNING (not
      // AS-preceded), not skip past it looking for a later one.
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
      // Verified against a real PostgreSQL 18.4 container: "SELECT 1 AS/*c*/returning" is valid
      // syntax — a comment is a legal separator between "AS" and its alias.
      val result = parseSelectItems("INSERT INTO t (a) SELECT 1 AS/*c*/returning RETURNING a, b")
      assertThat(result).containsExactly(
        SelectItem("a", "a", null),
        SelectItem("b", "b", null),
      )
    }

    @Test
    fun `a line comment between AS and its returning alias does not disturb keyword recognition`() {
      // Verified against a real PostgreSQL 18.4 container: "SELECT 1 AS--x" followed by a newline
      // then "returning" is valid syntax, same as the block-comment case above.
      val result = parseSelectItems("INSERT INTO t (a) SELECT 1 AS--x\nreturning RETURNING a, b")
      assertThat(result).containsExactly(
        SelectItem("a", "a", null),
        SelectItem("b", "b", null),
      )
    }

    @Test
    fun `a source column named with a trailing non-ASCII character is not mistaken for an AS returning alias`() {
      // #219: verified against a real PostgreSQL 18.4 container against "users(id, email, age,
      // preferences)" and a source table "src": "INSERT INTO users (email, age, preferences)
      // SELECT returning€, age, preferences FROM src RETURNING id, email" returns exactly 2
      // columns (id, email) -- "returning€" is a legal column name (PostgreSQL's lexer admits any
      // byte >= 0x80 inside an unquoted identifier), not the RETURNING keyword's alias position.
      // Before the fix, the word scan stopped at "€", saw the bare word "returning", and mistook
      // the source SELECT's implicit alias position for the real RETURNING clause -- producing 4
      // mis-paired items instead of the real 2.
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
      // Follow-up to #219: verified against a real PostgreSQL 18.4 -- "SELECT $€$x,$€$ AS lbl,
      // age, email FROM users" returns exactly 3 columns (lbl, age, email); "$€$x,$€$" is a single
      // dollar-quoted string literal tagged "€" (a legal tag character -- PostgreSQL's scan.l
      // admits any byte >= 0x80 in a dollar-quote tag, same as in an ordinary identifier). Before
      // the fix, the tag run used the narrow letter/digit/underscore class, which doesn't include
      // "€", so the tag was never recognized, the dollar-quoted string was never skipped as one
      // lexical unit, and its contents ("x", ",", the second "$€$") were scanned as if they were
      // ordinary SQL -- producing 4 mis-paired items instead of the real 3.
      val result = parseSelectItems("SELECT \$€\$x,\$€\$ AS lbl, age, email FROM users")
      assertThat(result).containsExactly(
        SelectItem("\$€\$x,\$€\$", null, null),
        SelectItem("age", "age", null),
        SelectItem("email", "email", null),
      )
    }

    @Test
    fun `RETURNING a column with a trailing non-ASCII character keeps its original name`() {
      // Follow-up to #219: verified against a real PostgreSQL 18.4 container -- "CREATE TABLE
      // users(id int, email text, age int, preferences€ text)" then "INSERT INTO
      // users(id,email,age,preferences€) VALUES (1,'e',2,'p') RETURNING preferences€, email"
      // returns columns named exactly "preferences€" and "email" -- "preferences€" is a legal
      // unquoted PostgreSQL column name (the lexer admits any byte >= 0x80 inside an unquoted
      // identifier). Before the fix, COLUMN_REFERENCE's "\w+" character class is ASCII-only, so
      // matchEntire on "preferences€" failed (the trailing "€" is left over), and the item fell
      // back to columnName=null instead of the real name.
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING preferences€, email")
      assertThat(result).containsExactly(
        SelectItem("preferences€", "preferences€", null),
        SelectItem("email", "email", null),
      )
    }

    @Test
    fun `a column name written with a supplementary-plane character resolves via the widened regex`() {
      // A supplementary-plane character is a SURROGATE PAIR in a Kotlin/UTF-16 String -- both code
      // units are >= 0x80, so PostgreSQL's own lexer admits it in an unquoted identifier the same
      // as any other >= 0x80 byte (verified against PostgreSQL 18.4: "CREATE TABLE astral(id int,
      // x𝐀y text)" -- U+1D400 MATHEMATICAL BOLD CAPITAL A -- succeeds, and "SELECT
      // x𝐀y FROM astral" resolves it unquoted). COLUMN_REFERENCE's ">= 0x80" range is
      // written as a CODE-POINT range ("\\x{80}-\\x{10FFFF}"), not a per-Char one, specifically so
      // it matches the whole surrogate pair as one code point rather than requiring the range to
      // be repeated to cover each UTF-16 half.
      val result = parseSelectItems("SELECT x𝐀y, id FROM astral")
      assertThat(result).containsExactly(
        SelectItem("x𝐀y", "x𝐀y", null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a digit-leading fragment abutting a non-ASCII character is not mistaken for a column reference`() {
      // Widening COLUMN_REFERENCE's continuation class to admit ">= 0x80" characters must NOT also
      // let a digit-leading fragment match as a whole "identifier": verified against PostgreSQL
      // 18.4, "SELECT 2€" is rejected outright ("trailing junk after numeric literal") -- "2€" is
      // not a legal identifier PostgreSQL would ever lex, digit-leading or otherwise. Without
      // COLUMN_REFERENCE_IDENTIFIER_START's separate (narrower) leading-character class, a naive
      // widen would matchEntire "2€" as a single "identifier" once "€" became a legal continuation
      // character, handing back a bogus columnName of "2€" for something PostgreSQL itself would
      // never resolve to that name.
      val result = parseSelectItems("SELECT 2€, email FROM users")
      assertThat(result).containsExactly(
        SelectItem("2€", null, null),
        SelectItem("email", "email", null),
      )
    }

    @Test
    fun `an AS keyword directly abutting a closing parenthesis is recognized as an alias boundary`() {
      // Verified against PostgreSQL 18.4: "SELECT (1)AS b" returns column "b" -- "AS" here abuts
      // the closing ")" with no whitespace at all, yet is still the real keyword, because ")" is
      // not an identifier character. Before the fix, extractAlias's whitespace-only boundary check
      // required literal whitespace on both sides, so this "AS" was never recognized as the
      // keyword: the alias was never stripped, and the item's expression stayed as the full
      // "(age)AS b" (asserted below via extractAlias's effect on the expression field) rather than
      // the correctly-stripped "(age)".
      val result = parseSelectItems("SELECT (age)AS b FROM users")
      assertThat(result).containsExactly(
        SelectItem("(age)", null, null),
      )
    }

    @Test
    fun `an AS keyword directly abutting a closing parenthesis, followed by a quoted alias, is recognized`() {
      // Verified against PostgreSQL 18.4: "SELECT (1)AS"b"" returns column "b" -- same boundary as
      // the plain-alias case above, but with a double-quoted alias directly following "AS" (no
      // whitespace on that side either). Before the fix, the "after" whitespace check rejected
      // this for the same reason as the "before" side: a double quote is not whitespace, so the
      // keyword was never recognized and the expression stayed as the whole, unsplit item.
      val result = parseSelectItems("SELECT (age)AS\"b\" FROM users")
      assertThat(result).containsExactly(
        SelectItem("(age)", null, null),
      )
    }

    @Test
    fun `an identifier ending in the letters AS is not mistaken for the AS keyword`() {
      // Verified against PostgreSQL 18.4: "CREATE TABLE d1(dataAS text)" then "SELECT 1 dataAS"
      // returns column "dataas" -- "dataAS" lexes as ONE identifier, never as "data" followed by
      // the keyword "AS". extractAlias's boundary check must reject the "AS" inside "dataAS" as
      // not a real keyword occurrence on BOTH the old (whitespace) and new (isIdentifierChar) rule
      // -- included as a regression guard for the widened rule, not a reproduction of a bug.
      val result = parseSelectItems("SELECT dataAS b FROM users")
      assertThat(result).containsExactly(
        SelectItem("dataAS b", null, null),
      )
    }

    @Test
    fun `an AS keyword fused with a following non-ASCII character is not an alias boundary`() {
      // Verified against PostgreSQL 18.4: "SELECT 1 AS€b" returns column "as€b" -- "AS€b" lexes as
      // ONE implicit-alias identifier (€ is a legal identifier-continuation character), never as
      // the keyword "AS" followed by "€b". Regression guard for the widened rule: the old
      // whitespace-only check already rejected this ("€" is not whitespace either), so this
      // confirms the new isIdentifierChar-based check agrees for the right reason.
      val result = parseSelectItems("SELECT age AS€b FROM users")
      assertThat(result).containsExactly(
        SelectItem("age AS€b", null, null),
      )
    }

    @Test
    fun `an AS keyword fused with a preceding non-ASCII character is not an alias boundary`() {
      // Verified against PostgreSQL 18.4: "SELECT age x€AS b FROM users" is a syntax error --
      // "x€AS" lexes as ONE implicit-alias identifier (the same reasoning as the "AS€b" case
      // above, from the other side), leaving a stray extra token "b" with nothing to attach to.
      // Regression guard for the widened rule, same as the case above.
      val result = parseSelectItems("SELECT age x€AS FROM users")
      assertThat(result).containsExactly(
        SelectItem("age x€AS", null, null),
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
      // #238: stripRedundantOuterParentheses peels the one pair wrapping the WHOLE main query
      // before the depth-0 search runs, so this now resolves exactly like the unparenthesized form.
      val result = parseSelectItems("WITH c AS (SELECT 1 AS a) (SELECT a FROM c)")
      assertThat(result).containsExactly(SelectItem("a", "a", null))
    }

    @Test
    fun `a parenthesized main query with no WITH clause still resolves`() {
      // #238: same fix as above, with no leading WITH clause at all.
      val result = parseSelectItems("(SELECT a FROM x)")
      assertThat(result).containsExactly(SelectItem("a", "a", null))
    }

    @Test
    fun `parenthesized UNION branches with no WITH clause yield no items`() {
      // Same accepted fail-safe as above, UNION form: HEAD resolved this to [a] as well.
      val result = parseSelectItems("(SELECT a FROM x) UNION (SELECT b FROM y)")
      assertThat(result).isEmpty()
    }

    @Test
    fun `VALUES clause yields no items`() {
      // Regression guard, not a #212 reproduction: this already passed before the fix, since a
      // bare VALUES list has no top-level SELECT/RETURNING for either implementation to find.
      val result = parseSelectItems("VALUES (1, 2)")
      assertThat(result).isEmpty()
    }

    @Test
    fun `TABLE clause yields no items`() {
      // Regression guard, not a #212 reproduction: this already passed before the fix, for the
      // same reason as the VALUES case above.
      val result = parseSelectItems("TABLE t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a body wrapped in one redundant pair of parentheses still finds the top-level SELECT`() {
      // #238: a CTE body like `(SELECT ...)` slices to exactly this text. The extra, unmatched
      // leading "(" previously put the whole rest of the text at paren depth ONE, so
      // findTopLevelKeyword never found "SELECT" at depth zero and this returned empty.
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
    fun `two separately parenthesized set-operation branches are NOT treated as one redundant wrapping`() {
      // The first "(" here does NOT wrap the ENTIRE text -- its own matching ")" is followed by
      // " UNION (...)", not the end of the string -- so stripping must decline rather than peel it
      // off and misread only the first branch as the whole body.
      val result = parseSelectItems("(SELECT id FROM parent) UNION (SELECT id FROM child)")
      assertThat(result).isEmpty()
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
        // Verified against real PostgreSQL: "(tgt.*)" is valid syntax, equivalent to "tgt.*". The
        // star is the FIRST item here, so nothing precedes it to keep.
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
        // Verified against real PostgreSQL 18: "SELECT t.* AS whatever, 7 AS id FROM t" returns
        // 3 columns (a, b, id) for a two-column "t". Before this guard checked the alias-stripped
        // expression rather than the raw item text, the star's own "AS whatever" alias defeated
        // recognition entirely, so the guard never fired and the later "id" item stayed in the
        // list — positionally misattributed to whichever real column followed t's expansion. This
        // is the #212 failure mode itself, not merely the truncation trade-off documented on
        // parseSelectItems: a WRONG mapping survives, rather than degrading to no mapping at all.
        StarGuardCase(
          description = "an aliased star still triggers the star guard",
          sql = "SELECT t.* AS whatever, 7 AS id FROM t",
        ),
        StarGuardCase(
          description = "an aliased star in RETURNING still triggers the star guard",
          sql = "UPDATE t SET x = 1 RETURNING t.* AS whatever, 7 AS id",
        ),
        // Verified against real PostgreSQL 18.4: valid syntax, returns every column of "tgt". An
        // earlier version of isStarItem stripped wrapping parentheses BEFORE stripping comments, so
        // this comment — sitting OUTSIDE the parentheses, on the far side of the closing ")" — was
        // never removed, "(tgt.*) -- c" never reduced to "tgt.*", and the guard never fired: the
        // #212 failure mode (a later item shifted onto the wrong ResultSetMetaData column) survived
        // for exactly this spelling.
        StarGuardCase(
          description = "a trailing line comment outside a wrapping parenthesis still triggers the star guard",
          sql = "SELECT (tgt.*) -- c\n, id AS ident FROM tgt",
        ),
        // Verified against real PostgreSQL 18.4: valid syntax, same defect as the line-comment case
        // above.
        StarGuardCase(
          description = "a trailing block comment outside a wrapping parenthesis still triggers the star guard",
          sql = "SELECT (tgt.*) /*c*/, id AS ident FROM tgt",
        ),
        // Verified against real PostgreSQL 18.4: valid syntax. An earlier version of isStarItem
        // only collapsed WHITESPACE around the dot, not a comment sitting between the dot and the
        // star, so "tgt./*c*/ *" was never recognized.
        StarGuardCase(
          description = "a comment between the dot and the star still triggers the star guard",
          sql = "SELECT tgt./*c*/ *, id AS ident FROM tgt",
        ),
        // An implicit alias (no "AS") is valid PostgreSQL syntax on a star ("u.* whatever" —
        // identical in meaning to "u.* AS whatever"; "u" aliases "users" to make the star
        // unambiguous). "SELECT u.* whatever, preferences FROM users u" returns every column of
        // "users" plus a trailing "preferences" item. Before
        // candidate 2 (the expression minus its trailing whitespace-separated token) was checked,
        // "u.* whatever" as a whole does not end in ".*", so the guard never fired and "preferences"
        // (a later, unrelated item) stayed in the list — positionally misattributed to whichever
        // real column followed u's expansion, applying a real column-level type override meant for
        // a different column entirely. This is the #212 failure mode itself.
        StarGuardCase(
          description = "an implicit alias on a star still triggers the star guard",
          sql = "SELECT u.* whatever, preferences FROM users u",
        ),
        StarGuardCase(
          description = "an explicit AS alias on a star keeps working alongside the implicit-alias check",
          sql = "SELECT u.* AS whatever, preferences FROM users u",
        ),
        // Verified against real PostgreSQL 18.4: a double-quoted implicit alias is valid syntax.
        // The whitespace INSIDE the quoted identifier ("My Col") must not be mistaken for the
        // boundary before it — only the whitespace between "u.*" and the quoted identifier counts.
        StarGuardCase(
          description = "a quoted implicit alias on a star still triggers the star guard",
          sql = """SELECT u.* "My Col", preferences FROM users u""",
        ),
        // "u.* whatever -- c" is valid PostgreSQL syntax (an implicit alias on a star, with a
        // trailing comment). stripCommentsAndWhitespace removes both the whitespace AND the comment
        // outright, normalizing this to "u.*whatever" — the same shape as the zero-separator case,
        // which isStarItem's structural check already recognizes as a star (qualifier "u.", implicit
        // alias "whatever").
        StarGuardCase(
          description = "an implicit alias followed by a line comment still triggers the star guard",
          sql = "SELECT u.* whatever -- c\n, preferences FROM users u",
        ),
        // Same defect as the line-comment case above, block-comment form.
        StarGuardCase(
          description = "an implicit alias followed by a block comment still triggers the star guard",
          sql = "SELECT u.* whatever /*c*/, preferences FROM users u",
        ),
        // Fixes #215 shape 2: verified against a real PostgreSQL 18.4 container, "SELECT
        // u.*/*c*/whatever, preferences FROM users u" returns 5 columns against a 4-column "users"
        // (the star's own expansion plus "preferences") — the comment is just as much a
        // non-separator as no separator at all. isStarItem's structural check finds the star, its
        // qualifier ("u."), and its implicit alias ("whatever") purely by walking identifier/quote
        // structure once stripCommentsAndWhitespace has removed the comment outright, so it no
        // longer matters that there was never any whitespace boundary to find.
        StarGuardCase(
          description =
          "a comment abutting an implicit alias with no surrounding whitespace now triggers the star guard",
          sql = "SELECT u.*/*c*/whatever, preferences FROM users u",
        ),
        // Fixes #215 shape 2, line-comment form: verified against a real PostgreSQL 18 container,
        // "SELECT t.*-- c\nwhatever, id FROM t" against a 3-column "t" is valid and returns 4 real
        // columns (t's own 3 plus "id") — a star. The old text-only guard needed a literal
        // whitespace run outside any comment to find a trailing-token boundary at all, which a line
        // comment's own terminating newline (consumed as part of the comment token) can never
        // leave behind; the new structural check needs no such boundary.
        StarGuardCase(
          description = "a line comment abutting an implicit alias now triggers the star guard",
          sql = "SELECT t.*-- c\nwhatever, id FROM t",
        ),
        // Fixes #215 shape 1: verified against a real PostgreSQL 18.4 container, "SELECT
        // u.*whatever, preferences FROM users u" returns 5 columns against a 4-column "users" — the
        // star's own expansion plus "preferences" — with the implicit alias "whatever" ignored.
        StarGuardCase(
          description = "a zero-separator implicit alias abutting a star now triggers the star guard",
          sql = "SELECT u.*whatever, preferences FROM users u",
        ),
        // Regression guard: the double-quoted identifier "my*table" (a table literally named that)
        // contains a "*" character that is NOT the star item's own "*" token. isStarItem's alias
        // search recognizes the WHOLE quoted identifier as one lexical segment (via skipLexicalToken)
        // when walking forward, so it is never mistaken for two separate tokens split around the
        // internal "*", and the star immediately after the qualifying "." is found correctly.
        // Verified against a real PostgreSQL 18.4 container: `SELECT "my*table".*z, c FROM
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
        // Verified against a real PostgreSQL 18.4 container: "SELECT DISTINCT ON (a) t.*, a FROM t"
        // returns 3 columns (t's own 2 plus "a") against a 2-column "t" — a star, with no implicit
        // alias here. parseSelectItems' skipOptionalSetQuantifier strips "DISTINCT ON (a) " before
        // isStarItem ever sees the item text, so isStarItem is handed the plain "t.*" — ending in the
        // literal ".*" — needing no special understanding of DISTINCT ON at all.
        StarGuardCase(
          description = "a DISTINCT ON prefix before a qualified star still triggers the star guard",
          sql = "SELECT DISTINCT ON (a) t.*, a FROM t",
        ),
        // Verified against a real PostgreSQL 18.4 container: "SELECT (t).*, a FROM t" returns 3
        // columns (t's own 2 plus "a") — PostgreSQL's composite-value-expansion idiom, distinct from
        // the wrapping-parenthesis case ("(tgt.*)") this guard already handled: here only "(t)" is
        // parenthesized, not the whole item, so the wrapping-paren unwrap loop never fires and the
        // item's full text ends in ".*" directly, matching the plain pre-existing suffix check.
        StarGuardCase(
          description = "a parenthesized composite expansion still triggers the star guard",
          sql = "SELECT (t).*, a FROM t",
        ),
        // Same idiom as above, with a function call instead of a bare relation alias — verified
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
        // Verified against a real PostgreSQL 18.4 container: `SELECT U&"my*table".*, c FROM
        // "my*table"` returns 3 columns (the star's own 2 plus "c") -- the "U&" Unicode-escape prefix
        // on the quoted identifier is ordinary PostgreSQL syntax, not something this guard needs to
        // understand specially: the item's full text ends in ".*" directly (the quoted identifier
        // itself is copied through verbatim by stripCommentsAndWhitespace), matching the plain
        // pre-existing suffix check.
        StarGuardCase(
          description = "a Unicode-escape quoted identifier qualifying a star still triggers the star guard",
          sql = """SELECT U&"my*table".*, c FROM "my*table"""",
        ),
        // Unlike the no-alias form above, "(t).*x" does NOT end in the literal ".*" (it ends in
        // "x"), so path 1 fails and this only reaches path 2: isStarItem finds "x" as the trailing
        // alias segment, leaving the prefix "(t).*" — which DOES end in ".*", with a qualifier
        // "(t)." whose character immediately before the final "." is ")", not an identifier
        // character at all, so isStarQualifierAcceptable accepts it (an EMPTY identifier run before
        // the dot is always accepted — see its KDoc).
        StarGuardCase(
          description = "an implicit alias on a parenthesized composite expansion still triggers the star guard",
          sql = "SELECT (t).*x, a FROM t",
        ),
        // Same as the no-alias DISTINCT ON case above: skipOptionalSetQuantifier strips
        // "DISTINCT ON (a) " before isStarItem ever runs, so isStarItem is handed the plain "t.*x" —
        // the same ordinary qualified-star-with-implicit-alias shape as "u.*whatever", needing no
        // special understanding of DISTINCT ON.
        StarGuardCase(
          description = "an implicit alias on a DISTINCT ON qualified star still triggers the star guard",
          sql = "SELECT DISTINCT ON (a) t.*x, a FROM t",
        ),
        StarGuardCase(
          description = "a Unicode-escape quoted identifier with an implicit alias still triggers the star guard",
          sql = """SELECT U&"my*table".*z, c FROM "my*table"""",
        ),
        // Verified against a real PostgreSQL 18.4 container: "SELECT (t.*::t).* whatever, a FROM t"
        // returns 3 columns (t's own 2 plus "a") against a 2-column "t". The qualifier "(t.*::t)."
        // contains its OWN star (inside the cast expression, a leftover "t.*" that Postgres accepts
        // as an argument to the composite cast) — isStarItem's alias search is not misled by it: it
        // finds "whatever" as the trailing segment regardless of how many "*" characters sit earlier
        // in the text, since it walks FORWARD tracking only the LAST segment, never searching for a
        // "*" character directly at all.
        StarGuardCase(
          description =
          "a star qualifier that itself contains a star via a parenthesized cast still triggers the star guard",
          sql = "SELECT (t.*::t).* whatever, a FROM t",
        ),
        // Verified against a real PostgreSQL 18.4 container: "SELECT (ARRAY[t.*])[1].* whatever, a
        // FROM t" returns 3 columns (t's own 2 plus "a"). The qualifier "(ARRAY[t.*])[1]." contains
        // its own star inside the ARRAY literal, harmless to the alias search (see the cast case
        // above); the array-subscript close "]" immediately before the qualifier's final "." is not
        // an identifier character, so isStarQualifierAcceptable accepts the (empty) run before it.
        StarGuardCase(
          description =
          "a star qualifier that itself contains a star via an ARRAY subscript still triggers the star guard",
          sql = "SELECT (ARRAY[t.*])[1].* whatever, a FROM t",
        ),
        // Verified against a real PostgreSQL 18.4 container: "SELECT (f(a*2)).* z, a FROM t" returns
        // 3 columns (t's own 2 plus "a") — "f" is a real function returning a row of "t"'s shape. The
        // qualifier "(f(a*2))." contains an unrelated multiplication star inside the function call's
        // own argument.
        StarGuardCase(
          description = "a star qualifier containing a star via multiplication inside a function call " +
            "still triggers the star guard",
          sql = "SELECT (f(a*2)).* z, a FROM t",
        ),
        // Same shape as the ARRAY-subscript case above, with no separator before the implicit alias
        // — issue shape 1 for this qualifier. Verified against a real PostgreSQL 18.4 container:
        // "SELECT (ARRAY[t.*])[1].*whatever, a FROM t" returns the same 3 columns.
        StarGuardCase(
          description =
          "a zero-separator implicit alias on a star qualifier containing its own star still triggers the star guard",
          sql = "SELECT (ARRAY[t.*])[1].*whatever, a FROM t",
        ),
        // Fixes a regression: the old wrapping-parenthesis unwrap loop required the ENTIRE
        // normalized text to end in ")", so "(u.*)whatever" (only "u.*" is parenthesized, not the
        // trailing alias) never unwrapped and was missed. Verified against a real PostgreSQL 18.4
        // container: "SELECT (u.*) whatever, preferences FROM users u" returns 5 columns against a
        // 4-column "users" (the star's own expansion plus "preferences"). isStarItem's alias search
        // finds "whatever" as the trailing segment, leaving the prefix "(u.*)", which unwraps to
        // "u.*" and matches the plain suffix check.
        StarGuardCase(
          description = "a star wrapped only up to its implicit alias still triggers the star guard",
          sql = "SELECT (u.*) whatever, preferences FROM users u",
        ),
        // Verified against a real PostgreSQL 18.4 container: "SELECT ((t.*)) x, a FROM t" returns 3
        // columns (t's own 2 plus "a") against a 2-column "t". unwrapWrappingParentheses strips BOTH
        // layers of the prefix "((t.*))" in two passes, down to "t.*".
        StarGuardCase(
          description = "a doubly-wrapped star with an implicit alias still triggers the star guard",
          sql = "SELECT ((t.*)) x, a FROM t",
        ),
        // Verified against a real PostgreSQL 18.4 container: "UPDATE t SET a = 1 RETURNING (t.*) x,
        // a" returns 3 columns (t's own 2 plus "a").
        StarGuardCase(
          description = "a wrapped star with an implicit alias in RETURNING still triggers the star guard",
          sql = "UPDATE t SET a = 1 RETURNING (t.*) x, a",
        ),
        // The U&-lookahead ORDERING GUARD: this is the exact shape that breaks if the bare-identifier
        // rule is tried before the Unicode-escape rule when searching for the trailing alias segment
        // — a naive forward walk would match "U" alone as a one-character identifier segment, then
        // separately match "\"a\"" as a later segment, making the alias appear to end at the quoted
        // identifier while leaving a dangling "U&" attached to the prefix (`u.*U&`), which is not
        // star-shaped at all. Trying the Unicode-escape rule FIRST consumes "U&\"a\"" as ONE segment,
        // so the prefix correctly reduces to "u.*". Verified against a real PostgreSQL 18.4
        // container: `SELECT u.* U&"a", preferences FROM users u` returns 5 columns against a
        // 4-column "users".
        StarGuardCase(
          description = "a Unicode-escape identifier as the implicit alias itself still triggers the star guard",
          sql = """SELECT u.* U&"a", preferences FROM users u""",
        ),
        // Verified against a real PostgreSQL 18.4 container: `SELECT t.* U&"d!0061t" UESCAPE '!', a
        // FROM t` returns 3 columns (t's own 2 plus "a") against a 2-column "t" — PostgreSQL merges
        // the Unicode-escape identifier, the UESCAPE keyword, and its single-quoted escape-character
        // string into ONE lexical unit (here spelling the same identifier "U&\"data\"" would, via the
        // custom "!" escape character), so the whole thing is the implicit alias.
        StarGuardCase(
          description = "a Unicode-escape alias with a UESCAPE clause still triggers the star guard",
          sql = """SELECT t.* U&"d!0061t" UESCAPE '!', a FROM t""",
        ),
        // Verified against a real PostgreSQL 18.4 container: `SELECT U&"!0074" UESCAPE '!'.* x, a
        // FROM t` returns 3 columns (t's own 2 plus "a"). Here the UESCAPE-extended Unicode-escape
        // identifier is the QUALIFIER, not the alias — its closing "'" (from the escape-character
        // string) sits immediately before the star's qualifying ".", which is not an identifier
        // character, so isStarQualifierAcceptable accepts the (empty) run before it.
        StarGuardCase(
          description = "a Unicode-escape qualifier with a UESCAPE clause still triggers the star guard",
          sql = """SELECT U&"!0074" UESCAPE '!'.* x, a FROM t""",
        ),
        // Verified against a real PostgreSQL 18.4 container: "SELECT ALL t.* x, a FROM t" returns 3
        // columns (t's own 2 plus "a"). parseSelectItems' skipOptionalSetQuantifier strips "ALL "
        // before splitting the clause into items, so isStarItem is handed the plain "t.* x" — never
        // seeing the quantifier fused onto it.
        StarGuardCase(
          description =
          "an ALL quantifier before a qualified star with an implicit alias still triggers the star guard",
          sql = "SELECT ALL t.* x, a FROM t",
        ),
        // Verified against a real PostgreSQL 18.4 container: "SELECT all2.* a, p FROM all2" returns 3
        // columns (all2's own 2 plus "p") against a real table named "all2". skipOptionalKeyword's own
        // word-boundary check is what keeps "ALL" from matching the first 3 letters of "all2" — the
        // character immediately after ("2") is still an identifier character, so it isn't a real
        // standalone "ALL" keyword — leaving "all2.* a" as the untouched item text.
        StarGuardCase(
          description =
          "a star on a table whose name starts with the letters of the ALL quantifier still triggers the star guard",
          sql = "SELECT all2.* a, p FROM all2",
        ),
        // Written as a Kotlin unicode escape (\u0301, COMBINING ACUTE ACCENT) so the intent survives
        // any editor, and so a precomposed character (which already works and would not exercise this
        // fix) never sneaks in: the alias is "p", "r", "e", COMBINING ACUTE ACCENT (U+0301), "f", "s"
        // -- the NFD spelling of "prefs" with an accent on the "e" -- NOT the single precomposed
        // "e-acute" codepoint. PostgreSQL's lexer accepts any byte >= 0x80 in an unquoted identifier;
        // a combining mark (Unicode category Mn) is one such byte, but is not a Unicode "letter"
        // (Char.isLetter() rejects it). Verified against a real PostgreSQL 18.4 container (via
        // psql -f, to avoid shell/encoding mangling the input): this exact alias (NFD) on "users"
        // returns 5 columns against a 4-column "users".
        StarGuardCase(
          description = "an implicit alias written in NFD with a combining mark still triggers the star guard",
          sql = "SELECT u.* pre\u0301fs, preferences FROM users u",
        ),
        // Written as a Kotlin unicode escape (\u20AC, the Euro sign). Verified against a real
        // PostgreSQL 18.4 container: this exact query returns 3 columns (t's own 2 plus "a") against
        // a 2-column "t" -- a currency sign is Unicode category Sc (symbol), not a letter, but
        // PostgreSQL's lexer still accepts it (any byte >= 0x80) as an unquoted identifier character.
        StarGuardCase(
          description = "an implicit alias containing a currency symbol still triggers the star guard",
          sql = "SELECT t.* \u20ACtotal, a FROM t",
        ),
        // Written as a Kotlin unicode escape (\u00A9, the copyright sign). Verified against a real
        // PostgreSQL 18.4 container: this exact query returns 3 columns (t's own 2 plus "a") -- the
        // copyright sign (Unicode category So, symbol/other) is, on its own, a complete legal
        // unquoted identifier in PostgreSQL.
        StarGuardCase(
          description = "an implicit alias that is a bare symbol still triggers the star guard",
          sql = "SELECT t.* \u00A9, a FROM t",
        ),
        // Written as a Kotlin unicode escape surrogate pair (\uD83D\uDE80, the rocket emoji,
        // U+1F680 -- a supplementary-plane character). Verified against a real PostgreSQL 18.4
        // container: this exact query returns 5 columns against a 4-column "users". A
        // supplementary-plane character is a SURROGATE PAIR in a Kotlin/UTF-16 String -- both code
        // units are >= 0x80, so no special surrogate-aware handling is needed for the per-character
        // loop to consume it correctly.
        StarGuardCase(
          description = "an implicit alias that is a supplementary-plane character still triggers the star guard",
          sql = "SELECT u.* \uD83D\uDE80, preferences FROM users u",
        ),
        // Same alias as the SELECT currency-symbol case above, RETURNING form. Verified against a
        // real PostgreSQL 18.4 container: this exact statement returns 3 columns (t's own 2 plus
        // "a").
        StarGuardCase(
          description = "an implicit alias containing a currency symbol in RETURNING still triggers the star guard",
          sql = "UPDATE t SET a = 1 RETURNING t.* \u20ACtotal, a",
        ),
        // Issue #215 shape 1 for a non-ASCII alias -- unfixed in BOTH the pre-existing code and every
        // earlier round of this fix until now, since none of them recognized a combining mark as an
        // identifier character at all, separator or not. Written as a Kotlin unicode escape
        // (\u0301, COMBINING ACUTE ACCENT): the alias is "c", "a", "f", "e", COMBINING ACUTE ACCENT
        // (U+0301) -- the NFD spelling of "cafe" with an accent on the "e" -- NOT the precomposed
        // codepoint. Verified against a real PostgreSQL 18.4 container: this exact query returns 3
        // columns (t's own 2 plus "a").
        StarGuardCase(
          description = "a zero-separator NFD alias still triggers the star guard",
          sql = "SELECT t.*cafe\u0301, a FROM t",
        ),
        // Regression guard: isStarQualifierAcceptable used to reject via Char.isDigit(), which is
        // Unicode-aware and therefore ALSO matched non-ASCII digits (Unicode category Nd) -- but
        // PostgreSQL numeric literals use ASCII digits exclusively, so a non-ASCII digit starting a
        // qualifier's run can only be an identifier's first character, never a numeral. The table
        // here is literally named \u0663 (ARABIC-INDIC DIGIT THREE), written as a Kotlin unicode
        // escape. Verified against a real PostgreSQL 18.4 container with a real table named \u0663:
        // "SELECT \u0663.* x, a FROM \u0663" returns 3 columns (\u0663's own 2 plus "a").
        StarGuardCase(
          description = "a qualifier starting with a non-ASCII digit still triggers the star guard",
          sql = "SELECT \u0663.* x, a FROM \u0663",
        ),
        // Same regression guard as above, zero-separator implicit-alias form. Verified against a
        // real PostgreSQL 18.4 container: "SELECT \u0663.*x, a FROM \u0663" returns the same 3
        // columns.
        StarGuardCase(
          description =
          "a zero-separator alias on a qualifier starting with a non-ASCII digit still triggers the star guard",
          sql = "SELECT \u0663.*x, a FROM \u0663",
        ),
        // Regression guard, third instance of one root cause: isStarQualifierAcceptable used to scan
        // the run before the qualifying "." with isIdentifierChar alone, which rejects "\u20AC" (the
        // Euro sign, >= 0x80 but neither a letter nor a digit). Scanning backward from the ASCII
        // digit "9" therefore stopped AT "\u20AC", leaving "9" looking like the run's own start and
        // wrongly rejecting the whole qualifier as numeric -- when the real run is "x\u20AC9"
        // (letter-led, correctly acceptable). isIdentifierChar (shared with
        // matchTrailingAliasSegment) runs past "\u20AC" instead. The table alias here is "x",
        // "\u20AC", "9", written as Kotlin unicode escapes. Verified against a real PostgreSQL 18.4
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
        // "9", written as Kotlin unicode escapes. Verified against a real PostgreSQL 18.4 container:
        // "SELECT x¹9.* w, a FROM t x¹9" returns 3 columns (t's own 2 plus "a").
        StarGuardCase(
          description =
          "a qualifier containing a superscript digit followed by an ASCII digit still triggers the star guard",
          sql = "SELECT x\u00B99.* w, a FROM t x\u00B99",
        ),
        // Same regression guard again, with a combining mark instead of a symbol: the qualifier is
        // "c", "a", "f", "e", COMBINING ACUTE ACCENT (\u0301), "2" -- the NFD spelling of "cafe" with
        // an accent on the "e", followed by an ASCII digit, written as Kotlin unicode escapes.
        // Verified against a real PostgreSQL 18.4 container: "SELECT café2.* w, a FROM t café2" (NFD)
        // returns 3 columns (t's own 2 plus "a").
        StarGuardCase(
          description = "a qualifier written in NFD followed by an ASCII digit still triggers the star guard",
          sql = "SELECT cafe\u03012.* w, a FROM t cafe\u03012",
        ),
        // Regression guard for the fail-safe answer, now grounded in OriginalAdjacency rather than
        // an incidental character-class exclusion: "x€ E'a\'b'" (a real space between "€" and the
        // standalone "E") strips to "x€E'a\'b'", and skipSingleQuotedString's standalone-E lookback
        // gates its "was € really continuing an identifier into E" check on whether € and E were
        // ADJACENT in the original text -- they were not (the character stripping removed there was
        // a real separator) -- so E is correctly recognized as standalone regardless of what
        // character stripping happened to fuse in front of it (see that function's own KDoc). With E
        // correctly standalone, the whole "E'a\'b'" is scanned as ONE backslash-escaped string, so
        // isStarItem correctly reaches and recognizes the trailing ".* y" — this item IS star-shaped,
        // and parseSelectItems drops it and everything after it, giving the fail-safe emptyList()
        // this test asserts.
        //
        // Verified directly (by running this exact input against the pre-#223 code too): this query
        // already returned emptyList() before OriginalAdjacency existed, via the narrower
        // letter/digit/underscore-only class from issue #222 — which also happens to exclude "€" —
        // reaching the SAME star recognition for a narrower, coincidental reason. What changed is
        // that recognizing "E" as standalone here no longer depends on an accident of "€" failing
        // that narrow class; it depends on the actual structural fact that "€" was never truly
        // adjacent to "E" in the original query.
        StarGuardCase(
          description =
          "a typed-literal call with a standalone E before a backslash-quote does not manufacture a star item",
          sql = "SELECT (f(x€ E'a\\'b')).* y, id FROM t",
        ),
        // "1 - -1" (two independently-lexed "-" tokens, genuinely separated by a space) strips to
        // "1--1", which skipLexicalToken would otherwise read as a "--" line comment that was never
        // in the query — skipLineComment then jumps to the end of the stripped text, so no segment
        // ends exactly at text.length, findTrailingImplicitAliasStart returns null, and isStarItem
        // answers false — the DANGEROUS direction (a later item silently shifts onto the wrong
        // ResultSetMetaData column). Verified against a real PostgreSQL 18.4 container: this query
        // returns 4 columns (f1, f2, id, name) against a 2-column "t" — the star and everything
        // before it must be dropped, since two items come after it.
        StarGuardCase(
          description =
          "a negative literal subtraction inside a ROW composite star qualifier does not manufacture a --comment",
          sql = "SELECT (ROW(1, 2 - -1)).* y, id, name FROM t",
        ),
        // Same fusion class as the ROW case above, inside a function call's own argument instead of
        // a ROW constructor. Verified against a real PostgreSQL 18.4 container: this query returns 3
        // columns (f1, f2, id) — f(int) returns a 2-column composite.
        StarGuardCase(
          description =
          "a negative literal subtraction inside a function-call star qualifier does not manufacture a --comment",
          sql = "SELECT (f(1 - -1)).* y, id FROM t",
        ),
        // Same fusion class, inside a CAST's ROW argument. Verified against a real PostgreSQL 18.4
        // container: this query returns 3 columns (f1, f2, id).
        StarGuardCase(
          description = "a negative literal subtraction inside a CAST star qualifier does not manufacture a --comment",
          sql = "SELECT (CAST(ROW(1 - -1, 2) AS pair)).* y, id FROM t",
        ),
        // Same fusion class, inside an ARRAY subscript. Verified against a real PostgreSQL 18.4
        // container: this query returns 3 columns (id, name, id) — "t.*" expands to id/name, then
        // the trailing bare "id" is a separate item after the star.
        StarGuardCase(
          description =
          "a negative literal subtraction inside an array-subscript star qualifier does not manufacture a --comment",
          sql = "SELECT (ARRAY[t])[1 - -1].* y, id FROM t",
        ),
        // "xq E'...'" ("xq" — a domain over text — used as a type name in PostgreSQL's own
        // "typename Sconst" typed-literal form, genuinely separated from the following E-string by a
        // space) strips to "xqE'...'". Without gating the standalone-E lookback on original
        // adjacency, the "q" immediately before "E" would look like it continues "xq" into the "E",
        // wrongly denying the escape string its backslash-escape processing and letting the
        // backslash-quote inside it terminate the string early, garbling everything scanned after.
        // Verified against a real PostgreSQL 18.4 container: this query returns 3 columns (f1, f2,
        // id) — f(xq) returns a 2-column composite.
        StarGuardCase(
          description = "a space-separated E-string typed literal is not fused into a standalone-E escape string",
          sql = "SELECT (f(xq E'a\\'b')).* y, id FROM t",
        ),
        // "xq \$q\$...\$q\$" strips to "xq\$q\$...\$q\$" — without gating the dollar-quote identifier
        // lookback on original adjacency, the "q" immediately before "$" would look like it
        // continues the "xq" identifier (PostgreSQL allows "$" inside an unquoted identifier), so the
        // "$" would never be recognized as opening a dollar-quoted string at all, and its body —
        // including the "'" it contains — would be scanned as ordinary SQL. Verified against a real
        // PostgreSQL 18.4 container: this query returns 3 columns (f1, f2, id).
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
        // Regression guard: an earlier version of the star guard discarded the ENTIRE list whenever
        // a star shared it with another item, even for an item like "id" here, whose mapping is
        // positionally exact regardless of the star's own unknown expansion width.
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
        // Regression guard: a looser normalizer that strips ALL whitespace (needed to recognize
        // "tgt . *") must not also start recognizing "count( * )" as a star merely because it
        // contains the character "*" once whitespace is gone ("count(*)") — the trailing ")"
        // still rules it out. This is only true because "count(*)" does not ALSO look like a
        // wrapping parenthesis around a star: isStarItem's parenthesis-stripping loop only strips a
        // pair that wraps the ENTIRE remaining text (verified via findMatchingCloseParenthesis, not
        // merely the first/last characters), and "count(*)" does not start with "(" at all — its
        // first character is "c", and the real "(" is the 6th character (index 5) — so the loop's
        // own startsWith("(") check never fires and the trailing ")" is decisive. (A genuine wrapping
        // case like "(tgt.*)" normalizes to itself first — still ending in ")" — and only becomes a
        // recognized star AFTER the stripping loop removes that wrapping pair, leaving "tgt.*".)
        NotStarGuardCase(
          description = "count star is not mistaken for the star guard even with internal whitespace",
          sql = "SELECT count( * ) AS total, id FROM t",
          expected = listOf(
            SelectItem("count( * )", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        // Regression guard: "t.*::text" ends in neither "*" nor ".*" after normalization (it ends
        // in "text"), so it must keep its current classification as a computed expression, not a
        // star, regardless of how aggressively comments/whitespace are stripped elsewhere in it.
        NotStarGuardCase(
          description = "a cast on a star-shaped expression is not mistaken for the star guard",
          sql = "SELECT t.*::text AS whatever, id FROM t",
          expected = listOf(
            SelectItem("t.*::text", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        // The shape most likely to be broken by a looser implicit-alias check: candidate 2 for
        // "count(*) total" is "count(*)", which still does not end in ".*" (it ends in ")"), so
        // this must NOT be treated as a star.
        NotStarGuardCase(
          description = "count star with an implicit alias is not mistaken for the star guard",
          sql = "SELECT count(*) total, id FROM t",
          expected = listOf(
            SelectItem("count(*) total", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        // "t.*::text" has no top-level whitespace, so candidate 1 and candidate 2 are identical —
        // classification is unchanged from before the implicit-alias check existed.
        NotStarGuardCase(
          description = "a cast on a star with no whitespace is unaffected by the implicit-alias check",
          sql = "SELECT t.*::text, id FROM t",
          expected = listOf(
            SelectItem("t.*::text", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        // Regression guard: stripCommentsAndWhitespace removes the comment OUTRIGHT, with nothing
        // put in its place, so "t.*/*c*/::text" normalizes to "t.*::text" — not "t.* ::text" or
        // anything else with a boundary in it. isStarItem's alias search finds "text" as the trailing
        // segment (a valid identifier), leaving the prefix "t.*::" — which does NOT end in the
        // literal ".*" (it ends in "::"), so this is correctly rejected as not a star.
        NotStarGuardCase(
          description = "a cast on a star with a comment immediately before it is not mistaken for the star guard",
          sql = "SELECT t.*/*c*/::text, a FROM t",
          expected = listOf(
            SelectItem("t.*/*c*/::text", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        // Verified against a real PostgreSQL 18 container: "SELECT t.* ::text, a FROM t" is valid
        // and returns 2 columns (t, a), with NO star expansion. stripCommentsAndWhitespace removes
        // ALL whitespace, so this normalizes to the same "t.*::text" as the comment-abutting case
        // above, and is rejected the same way (prefix "t.*::" does not end in ".*").
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
        // Verified against a real PostgreSQL 18 container: "SELECT t.*-- c\n::text, a FROM t" is
        // valid and returns 2 columns (t, a), with NO star expansion. This already resolved
        // correctly before this round's "::" fix — skipLineComment consumes the whole "-- c\n" span
        // (including its terminating newline) as ONE opaque token, so none of the three candidates
        // ever finds a top-level whitespace boundary to strip a trailing token at here at all; "::"
        // stays attached to "t.*" regardless. It had no dedicated test pinning it, though.
        NotStarGuardCase(
          description = "a line comment between a star and its cast is not mistaken for an implicit alias",
          sql = "SELECT t.*-- c\n::text, a FROM t",
          expected = listOf(
            SelectItem("t.*-- c\n::text", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        // A single item short-circuits the star guard entirely (there is nothing after it to
        // shift), so this is unaffected by the ordering fix either way — included to confirm the
        // whole clause still parses correctly rather than the comment swallowing anything past it.
        NotStarGuardCase(
          description = "an implicit alias followed by a comment with no following item is unaffected by truncation",
          sql = "SELECT u.* whatever -- c\nFROM users u",
          expected = listOf(
            SelectItem("u.* whatever -- c", null, null),
          ),
        ),
        // Regression guard: PostgreSQL identifiers cannot start with a digit, so "2." is not a valid
        // qualifier — this is ordinary multiplication ("2 * 3"), with "3 lbl" an implicit alias on
        // the numeric literal "3" (verified against a real PostgreSQL 18.4 container: "SELECT
        // 2.*3 lbl, a FROM t" returns 2 columns). A qualifier check based on `\w` (ASCII word
        // characters) rather than "letter or underscore, then identifier characters" would wrongly
        // accept "2." as a qualifier here.
        NotStarGuardCase(
          description = "a digit-leading qualifier before a star is arithmetic, not a star",
          sql = "SELECT 2.*3 lbl, a FROM t",
          expected = listOf(
            SelectItem("2.*3 lbl", null, null),
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
        // Negative guard confirming isStarQualifierAcceptable's digit-leading rejection still fires
        // regardless of whether an implicit alias follows the star: "2." is rejected because the
        // identifier-character run immediately before its final "." is "2", and that run's first
        // character is a digit. Verified against a real PostgreSQL 18.4 container: "SELECT 2.*a lbl,
        // a FROM t" is valid arithmetic ("2 * a AS lbl"), returning 2 columns.
        NotStarGuardCase(
          description = "a digit-leading qualifier before a star with an implicit alias remains arithmetic, not a star",
          sql = "SELECT 2.*a lbl, a FROM t",
          expected = listOf(
            SelectItem("2.*a lbl", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        // Negative guard confirming the alias-first design did not break the ordinary case where an
        // EARLIER part of the expression contains a star ("count(*)"). Verified against a real
        // PostgreSQL 18.4 container: "SELECT count(*) * 2 tot, (SELECT 1) AS id FROM t" returns 2
        // columns (tot, id) — isStarItem finds "tot" as the trailing alias segment, leaving the
        // prefix "count(*)*2", which does not end in the literal ".*" (it ends in "*2"), so this is
        // correctly rejected without needing to reason about the "*" inside "count(*)" at all.
        NotStarGuardCase(
          description = "an aggregate star multiplied by a constant is not mistaken for the star guard",
          sql = "SELECT count(*) * 2 tot, (SELECT 1) AS id FROM t",
          expected = listOf(
            SelectItem("count(*) * 2 tot", null, null),
            SelectItem("(SELECT 1)", null, null),
          ),
        ),
        // Negative guard for the point where skipOptionalSetQuantifier matters most: after
        // isStarItem's own whitespace-stripping, "ALL 2.*a" and a genuine star on a table named
        // "all2" would normalize IDENTICALLY ("all2.*a") if the quantifier were stripped inside
        // isStarItem itself rather than beforehand in parseSelectItems, using the still-whitespace-
        // intact clause text. Verified against a real PostgreSQL 18.4 container: "SELECT ALL 2.*a
        // lbl, b FROM t" is valid arithmetic ("ALL 2 * a AS lbl"), returning 2 columns.
        NotStarGuardCase(
          description = "an ALL quantifier fused with a digit-leading qualifier is still arithmetic, not a star",
          sql = "SELECT ALL 2.*a lbl, b FROM t",
          expected = listOf(
            SelectItem("2.*a lbl", null, null),
            SelectItem("b", "b", null),
          ),
        ),
        // Pins the point-4 behavior change: previously the quantifier text was glued onto the first
        // item's expression. Verified against a real PostgreSQL 18.4 container: "SELECT DISTINCT a, b
        // FROM t" returns 2 columns (a, b) — a plain DISTINCT with no ON (...) clause. The first
        // item's expression/columnName must now be the bare "a", not "DISTINCTa".
        NotStarGuardCase(
          description = "a DISTINCT quantifier with no ON clause is stripped from the first item",
          sql = "SELECT DISTINCT a, b FROM t",
          expected = listOf(
            SelectItem("a", "a", null),
            SelectItem("b", "b", null),
          ),
        ),
        // This is a DESIGN regression guard, not coverage of any Unicode relaxation: it pins that
        // isStarQualifierAcceptable still rejects an ASCII-digit-led qualifier run ("2") at all --
        // i.e. that the digit check has not been silently dropped or weakened to "always accept" --
        // regardless of what the TRAILING alias is made of (here an "x" followed by a COMBINING ACUTE
        // ACCENT, \u0301, written as a Kotlin unicode escape). It does NOT discriminate between any
        // version of the qualifier-side fix, past or present: a non-ASCII digit like "\u0663" starting
        // the qualifier's OWN run (which Char.isDigit() used to wrongly reject, and which the shared
        // isIdentifierChar predicate now correctly runs past) is a DIFFERENT shape -- see the
        // "\u0663" tests below for that discriminating coverage. Verified against a
        // real PostgreSQL 18.4 container using a temporary table (this literal identifier isn't a
        // real column anywhere, so a plain SELECT against "t" can't execute to confirm a row, only a
        // parse): creating a temp table with an "a" column and a column named by this exact
        // identifier, then "SELECT 2.*<that identifier> lbl, a FROM temp_check" returns 2 columns
        // (lbl, a) -- ordinary arithmetic, not a star, matching the identically-shaped (ASCII)
        // "2.*a lbl, a FROM t" case already pinned above.
        NotStarGuardCase(
          description =
          "a digit-leading qualifier before a star with an NFD implicit alias remains arithmetic, not a star",
          sql = "SELECT 2.*x\u0301 lbl, a FROM t",
          expected = listOf(
            SelectItem("2.*x\u0301 lbl", null, null),
            SelectItem("a", "a", null),
          ),
        ),
        NotStarGuardCase(
          description = "a multiplication expression with a trailing line comment is not mistaken for the star guard",
          sql = "SELECT a * b -- c\n, id FROM t",
          expected = listOf(
            SelectItem("a * b -- c", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description =
          "a multiplication expression with an implicit alias and internal block comment is not mistaken as a star",
          sql = "SELECT 2 * x /*c*/ total, id FROM t",
          expected = listOf(
            SelectItem("2 * x /*c*/ total", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description =
          "a function-call multiplication expression with a trailing line comment is not mistaken for the star guard",
          sql = "SELECT sum(x) * 2 -- c\n, id FROM t",
          expected = listOf(
            SelectItem("sum(x) * 2 -- c", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "a cast on a star with a trailing line comment is not mistaken for the star guard",
          sql = "SELECT t.*::text -- c\n, id FROM t",
          expected = listOf(
            SelectItem("t.*::text -- c", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description =
          "count star with an implicit alias and a trailing line comment is not mistaken for the star guard",
          sql = "SELECT count(*) total -- c\n, id FROM t",
          expected = listOf(
            SelectItem("count(*) total -- c", null, null),
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
            SelectItem("(a*b)::int lbl", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "a string literal containing a star with an implicit alias is not mistaken for the star guard",
          sql = "SELECT 'lit *' foo, id FROM t",
          expected = listOf(
            SelectItem("'lit *' foo", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description =
          "an array-indexed multiplication expression with an implicit alias is not mistaken for the star guard",
          sql = "SELECT arr[1] * 2 tot, id FROM t",
          expected = listOf(
            SelectItem("arr[1] * 2 tot", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        NotStarGuardCase(
          description = "a lone star with no implicit alias is unaffected by the implicit-alias check",
          sql = "SELECT * FROM t",
          expected = listOf(
            SelectItem("*", null, null),
          ),
        ),
        NotStarGuardCase(
          description = "a lone qualified star with no implicit alias is unaffected by the implicit-alias check",
          sql = "SELECT t.* FROM t",
          expected = listOf(
            SelectItem("t.*", null, null),
          ),
        ),
        // "preferences prefs" is a real implicit alias (no "AS"), but NEITHER candidate 1
        // ("preferences prefs") NOR candidate 2 ("preferences") is star-shaped, so the guard does
        // not fire and both items survive. columnName is null for the FIRST item specifically
        // because parseColumnReference's own regex requires the ENTIRE (unstripped) expression to
        // match a bare or qualified identifier — "preferences prefs" contains a space, so the whole
        // match fails — NOT because this function mistakes it for something else. This is a LOST
        // name (parseColumnReference simply cannot see past the implicit alias it wasn't taught to
        // strip), not a WRONG one: the resulting `emptyList()`-style fallback in JdbcAnalyzer
        // degrades to metadata, exactly as documented on parseSelectItems for any expression this
        // function cannot resolve.
        NotStarGuardCase(
          description =
          "an implicit alias on a non-star column is not mistaken for the star guard, but loses its column name",
          sql = "SELECT preferences prefs, id FROM t",
          expected = listOf(
            SelectItem("preferences prefs", null, null),
            SelectItem("id", "id", null),
          ),
        ),
        // columnName is null for the bare literal "5", not "5" itself: PostgreSQL would never lex a
        // digit-leading token as an identifier (it's a numeric literal), so COLUMN_REFERENCE's
        // leading-character restriction correctly rejects it — see COLUMN_REFERENCE's own KDoc.
        NotStarGuardCase(
          description = "WITH clause main query with no FROM clause",
          sql = "WITH c AS (SELECT 1) SELECT 5, 'x'",
          expected = listOf(
            SelectItem("5", null, null),
            SelectItem("'x'", null, null),
          ),
        ),
        // Regression guard, not a #212 reproduction: this already passed before the fix — the old
        // implementation's naive first-SELECT-anywhere search happens to find the same, correct
        // SELECT here, since there is no WITH clause and this branch's SELECT is the first in the
        // statement either way.
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
      // Verified directly against a live PostgreSQL 18: ResultSetMetaData.getColumnName for
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
    fun `an empty quoted identifier yields no column name, not an empty string`() {
      // PostgreSQL rejects a zero-length delimited identifier outright ("SELECT """" FROM t" is a
      // syntax error), so this is unreachable from a query PostgreSQL itself accepted -- but an
      // empty non-null columnName would make JdbcAnalyzer.buildResultColumns' originalName fall to
      // "" instead of correctly falling back to ResultSetMetaData.getColumnName, so this must
      // still degrade to null rather than an empty string.
      val result = parseSelectItems("SELECT \"\" FROM c").single()
      assertThat(result.columnName).isNull()
      assertThat(result.tableName).isNull()
    }
  }
}
