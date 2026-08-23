package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlUtilsTest {

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
    fun `parenthesized main query following a CTE clause yields no items`() {
      // Documented fail-safe: a top-level search inside the window cannot see into the
      // parenthesized main query, so this falls back to an empty list rather than guessing wrong.
      val result = parseSelectItems("WITH c AS (SELECT 1 AS a) (SELECT a FROM c)")
      assertThat(result).isEmpty()
    }

    @Test
    fun `parenthesized main query with no WITH clause yields no items`() {
      // Fail-safe pin, NOT a bug reproduction: this passes on the current code by construction —
      // there is no fix being verified here. HEAD's naive String.indexOf-based search did not
      // track paren depth, so it found "SELECT a" inside the parentheses regardless of depth and
      // resolved it to the correct [a]. The depth-0 search this function replaced it with cannot
      // see a SELECT sitting at depth 1, so it now returns emptyList() instead — a KNOWN, accepted
      // loss of precision (never a WRONG answer, just a lost one), documented on parseSelectItems.
      val result = parseSelectItems("(SELECT a FROM x)")
      assertThat(result).isEmpty()
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
    fun `star alongside another item returns empty list to avoid shifting later items`() {
      val result = parseSelectItems("SELECT *, id FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `items strictly before a later star are kept, not discarded`() {
      // Regression guard: an earlier version of the star guard discarded the ENTIRE list whenever
      // a star shared it with another item, even for an item like "id" here, whose mapping is
      // positionally exact regardless of the star's own unknown expansion width.
      val result = parseSelectItems("SELECT id AS ident, t.* FROM t")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `RETURNING items strictly before a later star are kept, not discarded`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING id AS ident, t.*")
      assertThat(result).containsExactly(
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `parenthesized star as the first item drops the whole list`() {
      // Verified against real PostgreSQL: "(tgt.*)" is valid syntax, equivalent to "tgt.*". The
      // star is the FIRST item here, so nothing precedes it to keep.
      val result = parseSelectItems("SELECT (tgt.*), id AS ident FROM tgt")
      assertThat(result).isEmpty()
    }

    @Test
    fun `star with a trailing block comment as the first item drops the whole list`() {
      val result = parseSelectItems("SELECT tgt.* /*c*/, id AS ident FROM tgt")
      assertThat(result).isEmpty()
    }

    @Test
    fun `star with a trailing line comment as the first item drops the whole list`() {
      val result = parseSelectItems("SELECT tgt.* -- c\n, id AS ident FROM tgt")
      assertThat(result).isEmpty()
    }

    @Test
    fun `star with whitespace around the qualifying dot as the first item drops the whole list`() {
      val result = parseSelectItems("SELECT tgt . *, id AS ident FROM tgt")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an aliased star still triggers the star guard`() {
      // Verified against real PostgreSQL 18: "SELECT t.* AS whatever, 7 AS id FROM t" returns
      // 3 columns (a, b, id) for a two-column "t". Before this guard checked the alias-stripped
      // expression rather than the raw item text, the star's own "AS whatever" alias defeated
      // recognition entirely, so the guard never fired and the later "id" item stayed in the
      // list — positionally misattributed to whichever real column followed t's expansion. This
      // is the #212 failure mode itself, not merely the truncation trade-off documented on
      // parseSelectItems: a WRONG mapping survives, rather than degrading to no mapping at all.
      val result = parseSelectItems("SELECT t.* AS whatever, 7 AS id FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an aliased star in RETURNING still triggers the star guard`() {
      val result = parseSelectItems("UPDATE t SET x = 1 RETURNING t.* AS whatever, 7 AS id")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a trailing line comment outside a wrapping parenthesis still triggers the star guard`() {
      // Verified against real PostgreSQL 18.4: valid syntax, returns every column of "tgt". An
      // earlier version of isStarItem stripped wrapping parentheses BEFORE stripping comments, so
      // this comment — sitting OUTSIDE the parentheses, on the far side of the closing ")" — was
      // never removed, "(tgt.*) -- c" never reduced to "tgt.*", and the guard never fired: the
      // #212 failure mode (a later item shifted onto the wrong ResultSetMetaData column) survived
      // for exactly this spelling.
      val result = parseSelectItems("SELECT (tgt.*) -- c\n, id AS ident FROM tgt")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a trailing block comment outside a wrapping parenthesis still triggers the star guard`() {
      // Verified against real PostgreSQL 18.4: valid syntax, same defect as the line-comment case
      // above.
      val result = parseSelectItems("SELECT (tgt.*) /*c*/, id AS ident FROM tgt")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a comment between the dot and the star still triggers the star guard`() {
      // Verified against real PostgreSQL 18.4: valid syntax. An earlier version of isStarItem
      // only collapsed WHITESPACE around the dot, not a comment sitting between the dot and the
      // star, so "tgt./*c*/ *" was never recognized.
      val result = parseSelectItems("SELECT tgt./*c*/ *, id AS ident FROM tgt")
      assertThat(result).isEmpty()
    }

    @Test
    fun `count star is not mistaken for the star guard even with internal whitespace`() {
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
      val result = parseSelectItems("SELECT count( * ) AS total, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("count( * )", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a cast on a star-shaped expression is not mistaken for the star guard`() {
      // Regression guard: "t.*::text" ends in neither "*" nor ".*" after normalization (it ends
      // in "text"), so it must keep its current classification as a computed expression, not a
      // star, regardless of how aggressively comments/whitespace are stripped elsewhere in it.
      val result = parseSelectItems("SELECT t.*::text AS whatever, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("t.*::text", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `an implicit alias on a star still triggers the star guard`() {
      // An implicit alias (no "AS") is valid PostgreSQL syntax on a star ("u.* whatever" —
      // identical in meaning to "u.* AS whatever"; "u" aliases "users" to make the star
      // unambiguous). "SELECT u.* whatever, preferences FROM users u" returns every column of
      // "users" plus a trailing "preferences" item. Before
      // candidate 2 (the expression minus its trailing whitespace-separated token) was checked,
      // "u.* whatever" as a whole does not end in ".*", so the guard never fired and "preferences"
      // (a later, unrelated item) stayed in the list — positionally misattributed to whichever
      // real column followed u's expansion, applying a real column-level type override meant for
      // a different column entirely. This is the #212 failure mode itself.
      val result = parseSelectItems("SELECT u.* whatever, preferences FROM users u")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an explicit AS alias on a star keeps working alongside the implicit-alias check`() {
      val result = parseSelectItems("SELECT u.* AS whatever, preferences FROM users u")
      assertThat(result).isEmpty()
    }

    @Test
    fun `count star with an implicit alias is not mistaken for the star guard`() {
      // The shape most likely to be broken by a looser implicit-alias check: candidate 2 for
      // "count(*) total" is "count(*)", which still does not end in ".*" (it ends in ")"), so
      // this must NOT be treated as a star.
      val result = parseSelectItems("SELECT count(*) total, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("count(*) total", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a cast on a star with no whitespace is unaffected by the implicit-alias check`() {
      // "t.*::text" has no top-level whitespace, so candidate 1 and candidate 2 are identical —
      // classification is unchanged from before the implicit-alias check existed.
      val result = parseSelectItems("SELECT t.*::text, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("t.*::text", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a cast on a star with a comment immediately before it is not mistaken for the star guard`() {
      // Regression guard: stripCommentsAndWhitespace removes the comment OUTRIGHT, with nothing
      // put in its place, so "t.*/*c*/::text" normalizes to "t.*::text" — not "t.* ::text" or
      // anything else with a boundary in it. isStarItem's alias search finds "text" as the trailing
      // segment (a valid identifier), leaving the prefix "t.*::" — which does NOT end in the
      // literal ".*" (it ends in "::"), so this is correctly rejected as not a star.
      val result = parseSelectItems("SELECT t.*/*c*/::text, a FROM t")
      assertThat(result).containsExactly(
        SelectItem("t.*/*c*/::text", null, null),
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a whitespace-separated cast on a star is not mistaken for an implicit alias`() {
      // Verified against a real PostgreSQL 18 container: "SELECT t.* ::text, a FROM t" is valid
      // and returns 2 columns (t, a), with NO star expansion. stripCommentsAndWhitespace removes
      // ALL whitespace, so this normalizes to the same "t.*::text" as the comment-abutting case
      // above, and is rejected the same way (prefix "t.*::" does not end in ".*").
      val result = parseSelectItems("SELECT t.* ::text, a FROM t")
      assertThat(result).containsExactly(
        SelectItem("t.* ::text", null, null),
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a whitespace-separated array cast on a star is not mistaken for an implicit alias`() {
      // A different spaced-cast shape from the string-text case above, to confirm the "::" check
      // is not narrowly tied to one particular cast target type.
      val result = parseSelectItems("SELECT t.* ::integer[], a FROM t")
      assertThat(result).containsExactly(
        SelectItem("t.* ::integer[]", null, null),
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a line comment between a star and its cast is not mistaken for an implicit alias`() {
      // Verified against a real PostgreSQL 18 container: "SELECT t.*-- c\n::text, a FROM t" is
      // valid and returns 2 columns (t, a), with NO star expansion. This already resolved
      // correctly before this round's "::" fix — skipLineComment consumes the whole "-- c\n" span
      // (including its terminating newline) as ONE opaque token, so none of the three candidates
      // ever finds a top-level whitespace boundary to strip a trailing token at here at all; "::"
      // stays attached to "t.*" regardless. It had no dedicated test pinning it, though.
      val result = parseSelectItems("SELECT t.*-- c\n::text, a FROM t")
      assertThat(result).containsExactly(
        SelectItem("t.*-- c\n::text", null, null),
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a quoted implicit alias on a star still triggers the star guard`() {
      // Verified against real PostgreSQL 18.4: a double-quoted implicit alias is valid syntax.
      // The whitespace INSIDE the quoted identifier ("My Col") must not be mistaken for the
      // boundary before it — only the whitespace between "u.*" and the quoted identifier counts.
      val result = parseSelectItems("""SELECT u.* "My Col", preferences FROM users u""")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an implicit alias followed by a line comment still triggers the star guard`() {
      // "u.* whatever -- c" is valid PostgreSQL syntax (an implicit alias on a star, with a
      // trailing comment). stripCommentsAndWhitespace removes both the whitespace AND the comment
      // outright, normalizing this to "u.*whatever" — the same shape as the zero-separator case,
      // which isStarItem's structural check already recognizes as a star (qualifier "u.", implicit
      // alias "whatever").
      val result = parseSelectItems("SELECT u.* whatever -- c\n, preferences FROM users u")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an implicit alias followed by a block comment still triggers the star guard`() {
      // Same defect as the line-comment case above, block-comment form.
      val result = parseSelectItems("SELECT u.* whatever /*c*/, preferences FROM users u")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an implicit alias followed by a comment with no following item is unaffected by truncation`() {
      // A single item short-circuits the star guard entirely (there is nothing after it to
      // shift), so this is unaffected by the ordering fix either way — included to confirm the
      // whole clause still parses correctly rather than the comment swallowing anything past it.
      val result = parseSelectItems("SELECT u.* whatever -- c\nFROM users u")
      assertThat(result).containsExactly(
        SelectItem("u.* whatever -- c", null, null),
      )
    }

    @Test
    fun `a comment abutting an implicit alias with no surrounding whitespace now triggers the star guard`() {
      // Fixes #215 shape 2: verified against a real PostgreSQL 18.4 container, "SELECT
      // u.*/*c*/whatever, preferences FROM users u" returns 5 columns against a 4-column "users"
      // (the star's own expansion plus "preferences") — the comment is just as much a
      // non-separator as no separator at all. isStarItem's structural check finds the star, its
      // qualifier ("u."), and its implicit alias ("whatever") purely by walking identifier/quote
      // structure once stripCommentsAndWhitespace has removed the comment outright, so it no
      // longer matters that there was never any whitespace boundary to find.
      val result = parseSelectItems("SELECT u.*/*c*/whatever, preferences FROM users u")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a line comment abutting an implicit alias now triggers the star guard`() {
      // Fixes #215 shape 2, line-comment form: verified against a real PostgreSQL 18 container,
      // "SELECT t.*-- c\nwhatever, id FROM t" against a 3-column "t" is valid and returns 4 real
      // columns (t's own 3 plus "id") — a star. The old text-only guard needed a literal
      // whitespace run outside any comment to find a trailing-token boundary at all, which a line
      // comment's own terminating newline (consumed as part of the comment token) can never
      // leave behind; the new structural check needs no such boundary.
      val result = parseSelectItems("SELECT t.*-- c\nwhatever, id FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a zero-separator implicit alias abutting a star now triggers the star guard`() {
      // Fixes #215 shape 1: verified against a real PostgreSQL 18.4 container, "SELECT
      // u.*whatever, preferences FROM users u" returns 5 columns against a 4-column "users" — the
      // star's own expansion plus "preferences" — with the implicit alias "whatever" ignored.
      val result = parseSelectItems("SELECT u.*whatever, preferences FROM users u")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a star qualified by a double-quoted identifier containing a literal star character is found lexically`() {
      // Regression guard: the double-quoted identifier "my*table" (a table literally named that)
      // contains a "*" character that is NOT the star item's own "*" token. isStarItem's alias
      // search recognizes the WHOLE quoted identifier as one lexical segment (via skipLexicalToken)
      // when walking forward, so it is never mistaken for two separate tokens split around the
      // internal "*", and the star immediately after the qualifying "." is found correctly.
      // Verified against a real PostgreSQL 18.4 container: `SELECT "my*table".*z, c FROM
      // "my*table"` is valid and returns 3 columns (the star's own 2 plus "c"), implicit alias "z"
      // ignored.
      val result = parseSelectItems("""SELECT "my*table".*z, c FROM "my*table"""")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a star qualified by a double-quoted identifier containing a literal star character, no alias`() {
      // Same regression guard as above, without the trailing implicit alias.
      val result = parseSelectItems("""SELECT "my*table".*, c FROM "my*table"""")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a digit-leading qualifier before a star is arithmetic, not a star`() {
      // Regression guard: PostgreSQL identifiers cannot start with a digit, so "2." is not a valid
      // qualifier — this is ordinary multiplication ("2 * 3"), with "3 lbl" an implicit alias on
      // the numeric literal "3" (verified against a real PostgreSQL 18.4 container: "SELECT
      // 2.*3 lbl, a FROM t" returns 2 columns). A qualifier check based on `\w` (ASCII word
      // characters) rather than "letter or underscore, then identifier characters" would wrongly
      // accept "2." as a qualifier here.
      val result = parseSelectItems("SELECT 2.*3 lbl, a FROM t")
      assertThat(result).containsExactly(
        SelectItem("2.*3 lbl", null, null),
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a digit-leading qualifier before a star with explicit whitespace is arithmetic, not a star`() {
      val result = parseSelectItems("SELECT 2. * 3 AS x, a FROM t")
      assertThat(result).containsExactly(
        SelectItem("2. * 3", null, null),
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a DISTINCT ON prefix before a qualified star still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: "SELECT DISTINCT ON (a) t.*, a FROM t"
      // returns 3 columns (t's own 2 plus "a") against a 2-column "t" — a star, with no implicit
      // alias here. parseSelectItems' skipOptionalSetQuantifier strips "DISTINCT ON (a) " before
      // isStarItem ever sees the item text, so isStarItem is handed the plain "t.*" — ending in the
      // literal ".*" — needing no special understanding of DISTINCT ON at all.
      val result = parseSelectItems("SELECT DISTINCT ON (a) t.*, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a parenthesized composite expansion still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: "SELECT (t).*, a FROM t" returns 3
      // columns (t's own 2 plus "a") — PostgreSQL's composite-value-expansion idiom, distinct from
      // the wrapping-parenthesis case ("(tgt.*)") this guard already handled: here only "(t)" is
      // parenthesized, not the whole item, so the wrapping-paren unwrap loop never fires and the
      // item's full text ends in ".*" directly, matching the plain pre-existing suffix check.
      val result = parseSelectItems("SELECT (t).*, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a parenthesized composite expansion of a function call still triggers the star guard`() {
      // Same idiom as above, with a function call instead of a bare relation alias — verified
      // against a real PostgreSQL 18.4 container: "SELECT (f2(1)).*, a FROM t" returns 3 columns
      // ("f2" is a real function returning a row of "t"'s shape).
      val result = parseSelectItems("SELECT (f2(1)).*, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a parenthesized composite expansion in RETURNING still triggers the star guard`() {
      val result = parseSelectItems("UPDATE t SET a = a RETURNING (t).*, a")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a Unicode-escape quoted identifier qualifying a star still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: `SELECT U&"my*table".*, c FROM
      // "my*table"` returns 3 columns (the star's own 2 plus "c") — the "U&" Unicode-escape prefix
      // on the quoted identifier is ordinary PostgreSQL syntax, not something this guard needs to
      // understand specially: the item's full text ends in ".*" directly (the quoted identifier
      // itself is copied through verbatim by stripCommentsAndWhitespace), matching the plain
      // pre-existing suffix check.
      val result = parseSelectItems("""SELECT U&"my*table".*, c FROM "my*table"""")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an implicit alias on a parenthesized composite expansion still triggers the star guard`() {
      // Unlike the no-alias form above, "(t).*x" does NOT end in the literal ".*" (it ends in
      // "x"), so path 1 fails and this only reaches path 2: isStarItem finds "x" as the trailing
      // alias segment, leaving the prefix "(t).*" — which DOES end in ".*", with a qualifier
      // "(t)." whose character immediately before the final "." is ")", not an identifier
      // character at all, so isStarQualifierAcceptable accepts it (an EMPTY identifier run before
      // the dot is always accepted — see its KDoc).
      val result = parseSelectItems("SELECT (t).*x, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an implicit alias on a DISTINCT ON qualified star still triggers the star guard`() {
      // Same as the no-alias DISTINCT ON case above: skipOptionalSetQuantifier strips
      // "DISTINCT ON (a) " before isStarItem ever runs, so isStarItem is handed the plain "t.*x" —
      // the same ordinary qualified-star-with-implicit-alias shape as "u.*whatever", needing no
      // special understanding of DISTINCT ON.
      val result = parseSelectItems("SELECT DISTINCT ON (a) t.*x, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a Unicode-escape quoted identifier with an implicit alias still triggers the star guard`() {
      val result = parseSelectItems("""SELECT U&"my*table".*z, c FROM "my*table"""")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a digit-leading qualifier before a star with an implicit alias remains arithmetic, not a star`() {
      // Negative guard confirming isStarQualifierAcceptable's digit-leading rejection still fires
      // regardless of whether an implicit alias follows the star: "2." is rejected because the
      // identifier-character run immediately before its final "." is "2", and that run's first
      // character is a digit. Verified against a real PostgreSQL 18.4 container: "SELECT 2.*a lbl,
      // a FROM t" is valid arithmetic ("2 * a AS lbl"), returning 2 columns.
      val result = parseSelectItems("SELECT 2.*a lbl, a FROM t")
      assertThat(result).containsExactly(
        SelectItem("2.*a lbl", null, null),
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a star qualifier that itself contains a star via a parenthesized cast still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: "SELECT (t.*::t).* whatever, a FROM t"
      // returns 3 columns (t's own 2 plus "a") against a 2-column "t". The qualifier "(t.*::t)."
      // contains its OWN star (inside the cast expression, a leftover "t.*" that Postgres accepts
      // as an argument to the composite cast) — isStarItem's alias search is not misled by it: it
      // finds "whatever" as the trailing segment regardless of how many "*" characters sit earlier
      // in the text, since it walks FORWARD tracking only the LAST segment, never searching for a
      // "*" character directly at all.
      val result = parseSelectItems("SELECT (t.*::t).* whatever, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a star qualifier that itself contains a star via an ARRAY subscript still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: "SELECT (ARRAY[t.*])[1].* whatever, a
      // FROM t" returns 3 columns (t's own 2 plus "a"). The qualifier "(ARRAY[t.*])[1]." contains
      // its own star inside the ARRAY literal, harmless to the alias search (see the cast case
      // above); the array-subscript close "]" immediately before the qualifier's final "." is not
      // an identifier character, so isStarQualifierAcceptable accepts the (empty) run before it.
      val result = parseSelectItems("SELECT (ARRAY[t.*])[1].* whatever, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a star qualifier containing a star via multiplication inside a function call still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: "SELECT (f(a*2)).* z, a FROM t" returns
      // 3 columns (t's own 2 plus "a") — "f" is a real function returning a row of "t"'s shape. The
      // qualifier "(f(a*2))." contains an unrelated multiplication star inside the function call's
      // own argument.
      val result = parseSelectItems("SELECT (f(a*2)).* z, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a zero-separator implicit alias on a star qualifier containing its own star still triggers the star guard`() {
      // Same shape as the ARRAY-subscript case above, with no separator before the implicit alias
      // — issue shape 1 for this qualifier. Verified against a real PostgreSQL 18.4 container:
      // "SELECT (ARRAY[t.*])[1].*whatever, a FROM t" returns the same 3 columns.
      val result = parseSelectItems("SELECT (ARRAY[t.*])[1].*whatever, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an aggregate star multiplied by a constant is not mistaken for the star guard`() {
      // Negative guard confirming the alias-first design did not break the ordinary case where an
      // EARLIER part of the expression contains a star ("count(*)"). Verified against a real
      // PostgreSQL 18.4 container: "SELECT count(*) * 2 tot, (SELECT 1) AS id FROM t" returns 2
      // columns (tot, id) — isStarItem finds "tot" as the trailing alias segment, leaving the
      // prefix "count(*)*2", which does not end in the literal ".*" (it ends in "*2"), so this is
      // correctly rejected without needing to reason about the "*" inside "count(*)" at all.
      val result = parseSelectItems("SELECT count(*) * 2 tot, (SELECT 1) AS id FROM t")
      assertThat(result).containsExactly(
        SelectItem("count(*) * 2 tot", null, null),
        SelectItem("(SELECT 1)", null, null),
      )
    }

    @Test
    fun `a star wrapped only up to its implicit alias still triggers the star guard`() {
      // Fixes a regression: the old wrapping-parenthesis unwrap loop required the ENTIRE
      // normalized text to end in ")", so "(u.*)whatever" (only "u.*" is parenthesized, not the
      // trailing alias) never unwrapped and was missed. Verified against a real PostgreSQL 18.4
      // container: "SELECT (u.*) whatever, preferences FROM users u" returns 5 columns against a
      // 4-column "users" (the star's own expansion plus "preferences"). isStarItem's alias search
      // finds "whatever" as the trailing segment, leaving the prefix "(u.*)", which unwraps to
      // "u.*" and matches the plain suffix check.
      val result = parseSelectItems("SELECT (u.*) whatever, preferences FROM users u")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a doubly-wrapped star with an implicit alias still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: "SELECT ((t.*)) x, a FROM t" returns 3
      // columns (t's own 2 plus "a") against a 2-column "t". unwrapWrappingParentheses strips BOTH
      // layers of the prefix "((t.*))" in two passes, down to "t.*".
      val result = parseSelectItems("SELECT ((t.*)) x, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a wrapped star with an implicit alias in RETURNING still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: "UPDATE t SET a = 1 RETURNING (t.*) x,
      // a" returns 3 columns (t's own 2 plus "a").
      val result = parseSelectItems("UPDATE t SET a = 1 RETURNING (t.*) x, a")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a Unicode-escape identifier as the implicit alias itself still triggers the star guard`() {
      // The U&-lookahead ORDERING GUARD: this is the exact shape that breaks if the bare-identifier
      // rule is tried before the Unicode-escape rule when searching for the trailing alias segment
      // — a naive forward walk would match "U" alone as a one-character identifier segment, then
      // separately match "\"a\"" as a later segment, making the alias appear to end at the quoted
      // identifier while leaving a dangling "U&" attached to the prefix (`u.*U&`), which is not
      // star-shaped at all. Trying the Unicode-escape rule FIRST consumes "U&\"a\"" as ONE segment,
      // so the prefix correctly reduces to "u.*". Verified against a real PostgreSQL 18.4
      // container: `SELECT u.* U&"a", preferences FROM users u` returns 5 columns against a
      // 4-column "users".
      val result = parseSelectItems("""SELECT u.* U&"a", preferences FROM users u""")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a Unicode-escape alias with a UESCAPE clause still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: `SELECT t.* U&"d!0061t" UESCAPE '!', a
      // FROM t` returns 3 columns (t's own 2 plus "a") against a 2-column "t" — PostgreSQL merges
      // the Unicode-escape identifier, the UESCAPE keyword, and its single-quoted escape-character
      // string into ONE lexical unit (here spelling the same identifier "U&\"data\"" would, via the
      // custom "!" escape character), so the whole thing is the implicit alias.
      val result = parseSelectItems("""SELECT t.* U&"d!0061t" UESCAPE '!', a FROM t""")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a Unicode-escape qualifier with a UESCAPE clause still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: `SELECT U&"!0074" UESCAPE '!'.* x, a
      // FROM t` returns 3 columns (t's own 2 plus "a"). Here the UESCAPE-extended Unicode-escape
      // identifier is the QUALIFIER, not the alias — its closing "'" (from the escape-character
      // string) sits immediately before the star's qualifying ".", which is not an identifier
      // character, so isStarQualifierAcceptable accepts the (empty) run before it.
      val result = parseSelectItems("""SELECT U&"!0074" UESCAPE '!'.* x, a FROM t""")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an ALL quantifier before a qualified star with an implicit alias still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: "SELECT ALL t.* x, a FROM t" returns 3
      // columns (t's own 2 plus "a"). parseSelectItems' skipOptionalSetQuantifier strips "ALL "
      // before splitting the clause into items, so isStarItem is handed the plain "t.* x" — never
      // seeing the quantifier fused onto it.
      val result = parseSelectItems("SELECT ALL t.* x, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a star on a table whose name starts with the letters of the ALL quantifier still triggers the star guard`() {
      // Verified against a real PostgreSQL 18.4 container: "SELECT all2.* a, p FROM all2" returns 3
      // columns (all2's own 2 plus "p") against a real table named "all2". skipOptionalKeyword's own
      // word-boundary check is what keeps "ALL" from matching the first 3 letters of "all2" — the
      // character immediately after ("2") is still an identifier character, so it isn't a real
      // standalone "ALL" keyword — leaving "all2.* a" as the untouched item text.
      val result = parseSelectItems("SELECT all2.* a, p FROM all2")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an ALL quantifier fused with a digit-leading qualifier is still arithmetic, not a star`() {
      // Negative guard for the point where skipOptionalSetQuantifier matters most: after
      // isStarItem's own whitespace-stripping, "ALL 2.*a" and a genuine star on a table named
      // "all2" would normalize IDENTICALLY ("all2.*a") if the quantifier were stripped inside
      // isStarItem itself rather than beforehand in parseSelectItems, using the still-whitespace-
      // intact clause text. Verified against a real PostgreSQL 18.4 container: "SELECT ALL 2.*a
      // lbl, b FROM t" is valid arithmetic ("ALL 2 * a AS lbl"), returning 2 columns.
      val result = parseSelectItems("SELECT ALL 2.*a lbl, b FROM t")
      assertThat(result).containsExactly(
        SelectItem("2.*a lbl", null, null),
        SelectItem("b", "b", null),
      )
    }

    @Test
    fun `a DISTINCT quantifier with no ON clause is stripped from the first item`() {
      // Pins the point-4 behavior change: previously the quantifier text was glued onto the first
      // item's expression. Verified against a real PostgreSQL 18.4 container: "SELECT DISTINCT a, b
      // FROM t" returns 2 columns (a, b) — a plain DISTINCT with no ON (...) clause. The first
      // item's expression/columnName must now be the bare "a", not "DISTINCTa".
      val result = parseSelectItems("SELECT DISTINCT a, b FROM t")
      assertThat(result).containsExactly(
        SelectItem("a", "a", null),
        SelectItem("b", "b", null),
      )
    }

    @Test
    fun `an implicit alias written in NFD with a combining mark still triggers the star guard`() {
      // Written as a Kotlin unicode escape (\u0301, COMBINING ACUTE ACCENT) so the intent survives
      // any editor, and so a precomposed character (which already works and would not exercise this
      // fix) never sneaks in: the alias is "p", "r", "e", COMBINING ACUTE ACCENT (U+0301), "f", "s"
      // -- the NFD spelling of "prefs" with an accent on the "e" -- NOT the single precomposed
      // "e-acute" codepoint. PostgreSQL's lexer accepts any byte >= 0x80 in an unquoted identifier;
      // a combining mark (Unicode category Mn) is one such byte, but is not a Unicode "letter"
      // (Char.isLetter() rejects it). Verified against a real PostgreSQL 18.4 container (via
      // psql -f, to avoid shell/encoding mangling the input): this exact alias (NFD) on "users"
      // returns 5 columns against a 4-column "users".
      val result = parseSelectItems("SELECT u.* pre\u0301fs, preferences FROM users u")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an implicit alias containing a currency symbol still triggers the star guard`() {
      // Written as a Kotlin unicode escape (\u20AC, the Euro sign). Verified against a real
      // PostgreSQL 18.4 container: this exact query returns 3 columns (t's own 2 plus "a") against
      // a 2-column "t" -- a currency sign is Unicode category Sc (symbol), not a letter, but
      // PostgreSQL's lexer still accepts it (any byte >= 0x80) as an unquoted identifier character.
      val result = parseSelectItems("SELECT t.* \u20ACtotal, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an implicit alias that is a bare symbol still triggers the star guard`() {
      // Written as a Kotlin unicode escape (\u00A9, the copyright sign). Verified against a real
      // PostgreSQL 18.4 container: this exact query returns 3 columns (t's own 2 plus "a") -- the
      // copyright sign (Unicode category So, symbol/other) is, on its own, a complete legal
      // unquoted identifier in PostgreSQL.
      val result = parseSelectItems("SELECT t.* \u00A9, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an implicit alias that is a supplementary-plane character still triggers the star guard`() {
      // Written as a Kotlin unicode escape surrogate pair (\uD83D\uDE80, the rocket emoji,
      // U+1F680 -- a supplementary-plane character). Verified against a real PostgreSQL 18.4
      // container: this exact query returns 5 columns against a 4-column "users". A
      // supplementary-plane character is a SURROGATE PAIR in a Kotlin/UTF-16 String -- both code
      // units are >= 0x80, so no special surrogate-aware handling is needed for the per-character
      // loop to consume it correctly.
      val result = parseSelectItems("SELECT u.* \uD83D\uDE80, preferences FROM users u")
      assertThat(result).isEmpty()
    }

    @Test
    fun `an implicit alias containing a currency symbol in RETURNING still triggers the star guard`() {
      // Same alias as the SELECT currency-symbol case above, RETURNING form. Verified against a
      // real PostgreSQL 18.4 container: this exact statement returns 3 columns (t's own 2 plus
      // "a").
      val result = parseSelectItems("UPDATE t SET a = 1 RETURNING t.* \u20ACtotal, a")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a zero-separator NFD alias still triggers the star guard`() {
      // Issue #215 shape 1 for a non-ASCII alias -- unfixed in BOTH the pre-existing code and every
      // earlier round of this fix until now, since none of them recognized a combining mark as an
      // identifier character at all, separator or not. Written as a Kotlin unicode escape
      // (\u0301, COMBINING ACUTE ACCENT): the alias is "c", "a", "f", "e", COMBINING ACUTE ACCENT
      // (U+0301) -- the NFD spelling of "cafe" with an accent on the "e" -- NOT the precomposed
      // codepoint. Verified against a real PostgreSQL 18.4 container: this exact query returns 3
      // columns (t's own 2 plus "a").
      val result = parseSelectItems("SELECT t.*cafe\u0301, a FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a digit-leading qualifier before a star with an NFD implicit alias remains arithmetic, not a star`() {
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
      val result = parseSelectItems("SELECT 2.*x\u0301 lbl, a FROM t")
      assertThat(result).containsExactly(
        SelectItem("2.*x\u0301 lbl", null, null),
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a qualifier starting with a non-ASCII digit still triggers the star guard`() {
      // Regression guard: isStarQualifierAcceptable used to reject via Char.isDigit(), which is
      // Unicode-aware and therefore ALSO matched non-ASCII digits (Unicode category Nd) -- but
      // PostgreSQL numeric literals use ASCII digits exclusively, so a non-ASCII digit starting a
      // qualifier's run can only be an identifier's first character, never a numeral. The table
      // here is literally named \u0663 (ARABIC-INDIC DIGIT THREE), written as a Kotlin unicode
      // escape. Verified against a real PostgreSQL 18.4 container with a real table named \u0663:
      // "SELECT \u0663.* x, a FROM \u0663" returns 3 columns (\u0663's own 2 plus "a").
      val result = parseSelectItems("SELECT \u0663.* x, a FROM \u0663")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a zero-separator alias on a qualifier starting with a non-ASCII digit still triggers the star guard`() {
      // Same regression guard as above, zero-separator implicit-alias form. Verified against a
      // real PostgreSQL 18.4 container: "SELECT \u0663.*x, a FROM \u0663" returns the same 3
      // columns.
      val result = parseSelectItems("SELECT \u0663.*x, a FROM \u0663")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a qualifier containing a currency symbol followed by an ASCII digit still triggers the star guard`() {
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
      val result = parseSelectItems("SELECT x\u20AC9.* w, preferences FROM users x\u20AC9")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a qualifier containing a superscript digit followed by an ASCII digit still triggers the star guard`() {
      // Same regression guard as above, with a different >= 0x80-but-not-letter-or-digit character:
      // "\u00B9" (SUPERSCRIPT ONE, Unicode category No -- number, other -- still not a letter or a
      // digit per isIdentifierChar's isLetterOrDigit()). The table alias here is "x", "\u00B9",
      // "9", written as Kotlin unicode escapes. Verified against a real PostgreSQL 18.4 container:
      // "SELECT x¹9.* w, a FROM t x¹9" returns 3 columns (t's own 2 plus "a").
      val result = parseSelectItems("SELECT x\u00B99.* w, a FROM t x\u00B99")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a qualifier written in NFD followed by an ASCII digit still triggers the star guard`() {
      // Same regression guard again, with a combining mark instead of a symbol: the qualifier is
      // "c", "a", "f", "e", COMBINING ACUTE ACCENT (\u0301), "2" -- the NFD spelling of "cafe" with
      // an accent on the "e", followed by an ASCII digit, written as Kotlin unicode escapes.
      // Verified against a real PostgreSQL 18.4 container: "SELECT café2.* w, a FROM t café2" (NFD)
      // returns 3 columns (t's own 2 plus "a").
      val result = parseSelectItems("SELECT cafe\u03012.* w, a FROM t cafe\u03012")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a multiplication expression with a trailing line comment is not mistaken for the star guard`() {
      val result = parseSelectItems("SELECT a * b -- c\n, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("a * b -- c", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a multiplication expression with an implicit alias and internal block comment is not mistaken as a star`() {
      val result = parseSelectItems("SELECT 2 * x /*c*/ total, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("2 * x /*c*/ total", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a function-call multiplication expression with a trailing line comment is not mistaken for the star guard`() {
      val result = parseSelectItems("SELECT sum(x) * 2 -- c\n, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("sum(x) * 2 -- c", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a cast on a star with a trailing line comment is not mistaken for the star guard`() {
      val result = parseSelectItems("SELECT t.*::text -- c\n, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("t.*::text -- c", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `count star with an implicit alias and a trailing line comment is not mistaken for the star guard`() {
      val result = parseSelectItems("SELECT count(*) total -- c\n, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("count(*) total -- c", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a multiplication expression split across a line comment and a newline is not mistaken for the star guard`() {
      val result = parseSelectItems("SELECT a * -- c\n b, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("a * -- c\n b", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a parenthesized cast with an implicit alias is not mistaken for the star guard`() {
      val result = parseSelectItems("SELECT (a*b)::int lbl, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("(a*b)::int lbl", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a string literal containing a star with an implicit alias is not mistaken for the star guard`() {
      val result = parseSelectItems("SELECT 'lit *' foo, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("'lit *' foo", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `an array-indexed multiplication expression with an implicit alias is not mistaken for the star guard`() {
      val result = parseSelectItems("SELECT arr[1] * 2 tot, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("arr[1] * 2 tot", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `a lone star with no implicit alias is unaffected by the implicit-alias check`() {
      val result = parseSelectItems("SELECT * FROM t")
      assertThat(result).containsExactly(
        SelectItem("*", null, null),
      )
    }

    @Test
    fun `a lone qualified star with no implicit alias is unaffected by the implicit-alias check`() {
      val result = parseSelectItems("SELECT t.* FROM t")
      assertThat(result).containsExactly(
        SelectItem("t.*", null, null),
      )
    }

    @Test
    fun `an implicit alias on a non-star column is not mistaken for the star guard, but loses its column name`() {
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
      val result = parseSelectItems("SELECT preferences prefs, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("preferences prefs", null, null),
        SelectItem("id", "id", null),
      )
    }

    @Test
    fun `WITH clause main query with no FROM clause`() {
      // columnName is null for the bare literal "5", not "5" itself: PostgreSQL would never lex a
      // digit-leading token as an identifier (it's a numeric literal), so COLUMN_REFERENCE's
      // leading-character restriction correctly rejects it — see COLUMN_REFERENCE's own KDoc.
      val result = parseSelectItems("WITH c AS (SELECT 1) SELECT 5, 'x'")
      assertThat(result).containsExactly(
        SelectItem("5", null, null),
        SelectItem("'x'", null, null),
      )
    }

    @Test
    fun `UNION picks the first branch's columns`() {
      // Regression guard, not a #212 reproduction: this already passed before the fix — the old
      // implementation's naive first-SELECT-anywhere search happens to find the same, correct
      // SELECT here, since there is no WITH clause and this branch's SELECT is the first in the
      // statement either way.
      val result = parseSelectItems("SELECT a FROM x UNION SELECT b FROM y")
      assertThat(result).containsExactly(
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a typed-literal call with a standalone E before a backslash-quote does not manufacture a star item`() {
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
      val result = parseSelectItems("SELECT (f(x€ E'a\\'b')).* y, id FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a negative literal subtraction inside a ROW composite star qualifier does not manufacture a --comment`() {
      // "1 - -1" (two independently-lexed "-" tokens, genuinely separated by a space) strips to
      // "1--1", which skipLexicalToken would otherwise read as a "--" line comment that was never
      // in the query — skipLineComment then jumps to the end of the stripped text, so no segment
      // ends exactly at text.length, findTrailingImplicitAliasStart returns null, and isStarItem
      // answers false — the DANGEROUS direction (a later item silently shifts onto the wrong
      // ResultSetMetaData column). Verified against a real PostgreSQL 18.4 container: this query
      // returns 4 columns (f1, f2, id, name) against a 2-column "t" — the star and everything
      // before it must be dropped, since two items come after it.
      val result = parseSelectItems("SELECT (ROW(1, 2 - -1)).* y, id, name FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a negative literal subtraction inside a function-call star qualifier does not manufacture a --comment`() {
      // Same fusion class as the ROW case above, inside a function call's own argument instead of
      // a ROW constructor. Verified against a real PostgreSQL 18.4 container: this query returns 3
      // columns (f1, f2, id) — f(int) returns a 2-column composite.
      val result = parseSelectItems("SELECT (f(1 - -1)).* y, id FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a negative literal subtraction inside a CAST star qualifier does not manufacture a --comment`() {
      // Same fusion class, inside a CAST's ROW argument. Verified against a real PostgreSQL 18.4
      // container: this query returns 3 columns (f1, f2, id).
      val result = parseSelectItems("SELECT (CAST(ROW(1 - -1, 2) AS pair)).* y, id FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a negative literal subtraction inside an array-subscript star qualifier does not manufacture a --comment`() {
      // Same fusion class, inside an ARRAY subscript. Verified against a real PostgreSQL 18.4
      // container: this query returns 3 columns (id, name, id) — "t.*" expands to id/name, then
      // the trailing bare "id" is a separate item after the star.
      val result = parseSelectItems("SELECT (ARRAY[t])[1 - -1].* y, id FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a space-separated E-string typed literal is not fused into a standalone-E escape string`() {
      // "xq E'...'" ("xq" — a domain over text — used as a type name in PostgreSQL's own
      // "typename Sconst" typed-literal form, genuinely separated from the following E-string by a
      // space) strips to "xqE'...'". Without gating the standalone-E lookback on original
      // adjacency, the "q" immediately before "E" would look like it continues "xq" into the "E",
      // wrongly denying the escape string its backslash-escape processing and letting the
      // backslash-quote inside it terminate the string early, garbling everything scanned after.
      // Verified against a real PostgreSQL 18.4 container: this query returns 3 columns (f1, f2,
      // id) — f(xq) returns a 2-column composite.
      val result = parseSelectItems("SELECT (f(xq E'a\\'b')).* y, id FROM t")
      assertThat(result).isEmpty()
    }

    @Test
    fun `a space-separated dollar-quoted typed literal is not fused into the identifier before it`() {
      // "xq \$q\$...\$q\$" strips to "xq\$q\$...\$q\$" — without gating the dollar-quote identifier
      // lookback on original adjacency, the "q" immediately before "$" would look like it
      // continues the "xq" identifier (PostgreSQL allows "$" inside an unquoted identifier), so the
      // "$" would never be recognized as opening a dollar-quoted string at all, and its body —
      // including the "'" it contains — would be scanned as ordinary SQL. Verified against a real
      // PostgreSQL 18.4 container: this query returns 3 columns (f1, f2, id).
      val result = parseSelectItems("SELECT (f(xq \$q\$a'b\$q\$)).* y, id FROM t")
      assertThat(result).isEmpty()
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

  @Test
  fun `splits simple comma-separated items`() {
    val result = splitAtTopLevel("a, b, c", ',')
    assertThat(result).containsExactly("a", " b", " c")
  }

  @Test
  fun `preserves content inside parentheses`() {
    val result = splitAtTopLevel("func(a, b), c", ',')
    assertThat(result).containsExactly("func(a, b)", " c")
  }

  @Test
  fun `handles nested parentheses`() {
    val result = splitAtTopLevel("outer(inner(a, b), c), d", ',')
    assertThat(result).containsExactly("outer(inner(a, b), c)", " d")
  }

  @Test
  fun `returns single item when no delimiter`() {
    val result = splitAtTopLevel("no delimiters here", ',')
    assertThat(result).containsExactly("no delimiters here")
  }

  @Test
  fun `returns single item for empty string`() {
    val result = splitAtTopLevel("", ',')
    assertThat(result).hasSize(1)
    assertThat(result).containsExactly("")
  }

  @Test
  fun `works with non-comma delimiters`() {
    val result = splitAtTopLevel("a;b;c", ';')
    assertThat(result).containsExactly("a", "b", "c")
  }

  @Test
  fun `handles multiple expressions with nested function calls`() {
    val result = splitAtTopLevel("EXISTS(SELECT 1 FROM t) AS valid, COUNT(*) AS total, name", ',')
    assertThat(result).containsExactly("EXISTS(SELECT 1 FROM t) AS valid", " COUNT(*) AS total", " name")
  }

  @Test
  fun `handles deeply nested parentheses`() {
    val result = splitAtTopLevel("encode(digest(hmac(a, b, c), d), e), f", ',')
    assertThat(result).containsExactly("encode(digest(hmac(a, b, c), d), e)", " f")
  }

  @Test
  fun `splits correctly around a column name containing two dollar signs`() {
    // Behavior CHANGE from wiring splitAtTopLevel through skipLexicalToken: before the dollar-
    // quote identifier fix, the "$" between "b" and "c" was misread as opening a "$b$"-tagged
    // dollar-quote, swallowing everything after it (including the real commas) as unterminated
    // string content — yielding ONE item instead of three. This is the corrected, intended
    // behavior, not a regression: "a$b$c" is an ordinary PostgreSQL identifier.
    val result = splitAtTopLevel("a\$b\$c, id, name", ',')
    assertThat(result).containsExactly("a\$b\$c", " id", " name")
  }

  @Test
  fun `preserves content inside square brackets`() {
    // Regression guard: square brackets were not tracked as a nesting level at all, so
    // ARRAY[1, 2]'s internal comma split the item in two — verified against real PostgreSQL to
    // corrupt oldOrNewReturningColumns's item count, and (worse) able to numerically CANCEL OUT
    // an unrelated star-caused split error, defeating its real-column-count cross-check entirely.
    val result = splitAtTopLevel("ARRAY[1, 2] AS arr, name", ',')
    assertThat(result).containsExactly("ARRAY[1, 2] AS arr", " name")
  }

  @Test
  fun `preserves content inside nested square brackets and parentheses together`() {
    val result = splitAtTopLevel("func(ARRAY[a, b], c), ARRAY[func(d, e), f], g", ',')
    assertThat(result).containsExactly("func(ARRAY[a, b], c)", " ARRAY[func(d, e), f]", " g")
  }

  @Nested
  inner class ReplaceParameterPlaceholders {

    @Test
    fun `replaces single placeholder`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE id = ?")
      assertThat(result).isEqualTo("SELECT * FROM t WHERE id = NULL")
    }

    @Test
    fun `replaces multiple placeholders`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE a = ? AND b = ?")
      assertThat(result).isEqualTo("SELECT * FROM t WHERE a = NULL AND b = NULL")
    }

    @Test
    fun `preserves question mark inside single-quoted string`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE note = 'really?' AND id = ?")
      assertThat(result).isEqualTo("SELECT * FROM t WHERE note = 'really?' AND id = NULL")
    }

    @Test
    fun `preserves question mark inside escaped string literal`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t WHERE note = 'it''s a ? mark' AND id = ?")
      assertThat(result).isEqualTo("SELECT * FROM t WHERE note = 'it''s a ? mark' AND id = NULL")
    }

    @Test
    fun `preserves question mark inside line comment`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t -- why?\nWHERE id = ?")
      assertThat(result).isEqualTo("SELECT * FROM t -- why?\nWHERE id = NULL")
    }

    @Test
    fun `preserves question mark inside block comment`() {
      val result = replaceParameterPlaceholders("SELECT * FROM t /* what? */ WHERE id = ?")
      assertThat(result).isEqualTo("SELECT * FROM t /* what? */ WHERE id = NULL")
    }

    @Test
    fun `no placeholders returns unchanged`() {
      val sql = "SELECT * FROM department"
      val result = replaceParameterPlaceholders(sql)
      assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `unclosed string literal preserves content without replacing`() {
      // Valid SQL never has unclosed literals, but the function should not crash
      val result = replaceParameterPlaceholders("SELECT '?")
      assertThat(result).isEqualTo("SELECT '?")
    }

    @Test
    fun `unclosed block comment preserves content without replacing`() {
      val result = replaceParameterPlaceholders("SELECT /* ?")
      assertThat(result).isEqualTo("SELECT /* ?")
    }
  }

  @Nested
  inner class ReplaceParameterPlaceholdersWithSentinels {

    @Test
    fun `replaces each placeholder with corresponding sentinel`() {
      val result = replaceParameterPlaceholdersWithSentinels(
        "SELECT digest(?, ?)",
        listOf("'\\x00'::bytea", "''::text"),
      )
      assertThat(result).isEqualTo("SELECT digest('\\x00'::bytea, ''::text)")
    }

    @Test
    fun `falls back to NULL when sentinels exhausted`() {
      val result = replaceParameterPlaceholdersWithSentinels(
        "SELECT ?, ?",
        listOf("0::int4"),
      )
      assertThat(result).isEqualTo("SELECT 0::int4, NULL")
    }

    @Test
    fun `skips placeholders in string literals`() {
      val result = replaceParameterPlaceholdersWithSentinels(
        "SELECT '?' || ?",
        listOf("''::text"),
      )
      assertThat(result).isEqualTo("SELECT '?' || ''::text")
    }

    @Test
    fun `skips placeholders in line comments`() {
      val result = replaceParameterPlaceholdersWithSentinels(
        "SELECT -- ?\n?",
        listOf("0::int4"),
      )
      assertThat(result).isEqualTo("SELECT -- ?\n0::int4")
    }

    @Test
    fun `returns original when no placeholders`() {
      val sql = "SELECT 1"
      val result = replaceParameterPlaceholdersWithSentinels(sql, listOf("0::int4"))
      assertThat(result).isEqualTo("SELECT 1")
    }
  }

  @Nested
  inner class SkipWhitespaceAndComments {

    @Test
    fun `lands on first non-whitespace character after a plain block comment`() {
      val result = skipWhitespaceAndComments("/* a */ SELECT", 0)
      assertThat(result).isEqualTo("/* a */ ".length)
    }

    @Test
    fun `lands on first non-whitespace character after a nested block comment`() {
      // Postgres block comments nest: the inner "/*" opens a second level, so the comment
      // only closes at the "*/" that brings the nesting depth back to zero.
      val sql = "/* a /* b */ */ SELECT"
      val result = skipWhitespaceAndComments(sql, 0)
      assertThat(result).isEqualTo("/* a /* b */ */ ".length)
      assertThat(sql.substring(result)).isEqualTo("SELECT")
    }

    @Test
    fun `lands on first non-whitespace character after a line comment`() {
      val sql = "-- comment\nSELECT"
      val result = skipWhitespaceAndComments(sql, 0)
      assertThat(result).isEqualTo("-- comment\n".length)
      assertThat(sql.substring(result)).isEqualTo("SELECT")
    }
  }

  @Nested
  inner class NonNullSentinel {

    @Test
    fun `integer types produce zero`() {
      assertThat(nonNullSentinel("int4")).isEqualTo("0::int4")
      assertThat(nonNullSentinel("int8")).isEqualTo("0::int8")
    }

    @Test
    fun `text types produce empty string`() {
      assertThat(nonNullSentinel("text")).isEqualTo("''::text")
      assertThat(nonNullSentinel("varchar")).isEqualTo("''::varchar")
    }

    @Test
    fun `boolean produces false`() {
      assertThat(nonNullSentinel("bool")).isEqualTo("false::bool")
    }

    @Test
    fun `bytea produces non-null value`() {
      assertThat(nonNullSentinel("bytea")).isEqualTo("'\\x00'::bytea")
    }

    @Test
    fun `array types produce empty array`() {
      assertThat(nonNullSentinel("_int4")).isEqualTo("ARRAY[]::_int4")
      assertThat(nonNullSentinel("_text")).isEqualTo("ARRAY[]::_text")
    }

    @Test
    fun `unknown types fall back to NULL with cast`() {
      assertThat(nonNullSentinel("custom_type")).isEqualTo("NULL::custom_type")
    }

    @Test
    fun `temporal types produce valid literals`() {
      assertThat(nonNullSentinel("date")).isEqualTo("'2000-01-01'::date")
      assertThat(nonNullSentinel("timestamp")).isEqualTo("'2000-01-01'::timestamp")
      assertThat(nonNullSentinel("uuid")).isEqualTo("'00000000-0000-0000-0000-000000000000'::uuid")
    }
  }

  @Nested
  inner class ParseCteClauseTest {

    @Test
    fun `WITH RECURSIVE is flagged as recursive`() {
      val result = parseCteClause("WITH RECURSIVE counter(n) AS (SELECT 1) SELECT n FROM counter")
      assertThat(result!!.isRecursive).isTrue()
    }

    @Test
    fun `plain WITH is not flagged as recursive`() {
      val result = parseCteClause("WITH c AS (SELECT 1) SELECT * FROM c")
      assertThat(result!!.isRecursive).isFalse()
    }

    @Test
    fun `CTE named recursive_cte is not misread as the RECURSIVE keyword`() {
      // "RECURSIVE" must be matched at a word boundary — a CTE literally named "recursive_cte"
      // must not cause isRecursive to be incorrectly set.
      val result = parseCteClause("WITH recursive_cte AS (SELECT 1) SELECT * FROM recursive_cte")
      assertThat(result!!.isRecursive).isFalse()
    }

    @Test
    fun `plain unquoted name has an identical name and rawName`() {
      val result = parseCteClause("WITH c AS (SELECT 1) SELECT * FROM c")
      assertThat(result!!.definitions[0].name).isEqualTo("c")
      assertThat(result.definitions[0].rawName).isEqualTo("c")
    }

    @Test
    fun `quoted name strips quotes for name but keeps them verbatim in rawName`() {
      val result = parseCteClause("""WITH "MyCte" AS (SELECT 1) SELECT * FROM "MyCte"""")
      assertThat(result!!.definitions[0].name).isEqualTo("MyCte")
      assertThat(result.definitions[0].rawName).isEqualTo("\"MyCte\"")
    }

    @Test
    fun `mixed-case unquoted name is preserved as-is in both name and rawName`() {
      // Unquoted identifiers are case-INsensitive to PostgreSQL (folded to lowercase), but
      // parseCteClause does no folding of its own — it must return exactly what the user wrote
      // in both properties, leaving any folding decision to PostgreSQL itself.
      val result = parseCteClause("WITH MyCte AS (SELECT 1) SELECT * FROM MyCte")
      assertThat(result!!.definitions[0].name).isEqualTo("MyCte")
      assertThat(result.definitions[0].rawName).isEqualTo("MyCte")
    }

    @Test
    fun `CTE body containing a closing parenthesis inside a string literal parses correctly`() {
      // Latent bug fixed alongside the DML lexer work: findMatchingCloseParenthesis previously
      // counted the ')' inside 'closing )' as if it closed the CTE body, truncating it and
      // corrupting everything parsed after — including a SECOND CTE that follows. With lexical
      // awareness, the literal's ')' is skipped as part of the string token, so the CTE body's
      // TRUE closing paren (the one right before the comma) is what's found.
      val sql = """
        WITH note AS (
          SELECT 'closing )'::TEXT AS msg
        ),
        counted AS (
          SELECT length(msg) AS len FROM note
        )
        SELECT len FROM counted
      """.trimIndent()
      val result = parseCteClause(sql)
      assertThat(result!!.definitions).hasSize(2)
      assertThat(result.definitions[0].name).isEqualTo("note")
      assertThat(result.definitions[1].name).isEqualTo("counted")
      val noteBody = sql.substring(
        result.definitions[0].bodyOpenParenthesis + 1,
        result.definitions[0].bodyCloseParenthesis,
      ).trim()
      assertThat(noteBody).isEqualTo("SELECT 'closing )'::TEXT AS msg")
    }

    @Test
    fun `CTE body containing a column named with two dollar signs parses correctly`() {
      // Behavior CHANGE from the dollar-quote identifier fix: before it, the "$" between "b" and
      // "c" in "a$b$c" was misread as opening a "$b$"-tagged dollar-quote, swallowing the CTE
      // body's own closing ")" (and everything after) as unterminated string content — parseCteClause
      // would have found only ONE (corrupted) definition, or none at all. This is the corrected,
      // intended behavior: "a$b$c" is an ordinary identifier, not a dollar-quoted string.
      val sql = """
        WITH renamed AS (
          SELECT a${'$'}b${'$'}c AS msg FROM note
        ),
        counted AS (
          SELECT length(msg) AS len FROM renamed
        )
        SELECT len FROM counted
      """.trimIndent()
      val result = parseCteClause(sql)
      assertThat(result!!.definitions).hasSize(2)
      assertThat(result.definitions[0].name).isEqualTo("renamed")
      assertThat(result.definitions[1].name).isEqualTo("counted")
    }

    @Test
    fun `CTE name containing a non-ASCII character parses correctly`() {
      // Verified against a real PostgreSQL 18.4: "WITH data€x AS (SELECT 1 AS inner_name) SELECT
      // inner_name AS outer_name FROM data€x" is accepted -- "data€x" is an ordinary unquoted
      // identifier (PostgreSQL's lexer admits any byte >= 0x80 inside one). Before the fix, the
      // CTE-name run used the narrow letter/digit/underscore class, which stopped at "€", leaving
      // "x AS (SELECT 1 AS inner_name) SELECT inner_name AS outer_name FROM data€x" where an "AS"
      // keyword was expected -- so parsing failed and this returned null instead of one definition.
      val result = parseCteClause(
        "WITH data€x AS (SELECT 1 AS inner_name) SELECT inner_name AS outer_name FROM data€x",
      )
      assertThat(result).isNotNull()
      assertThat(result!!.definitions).hasSize(1)
      assertThat(result.definitions[0].name).isEqualTo("data€x")
    }

    @Test
    fun `a dollar-led CTE name is not recognized, since PostgreSQL itself rejects one`() {
      // Issue #219 follow-up: parseSingleCteDefinition's unquoted-name run originally used
      // isIdentifierChar -- the CONTINUATION predicate -- for the name's FIRST character too, so
      // a leading "$" was wrongly accepted as starting a CTE name. Verified against a real
      // PostgreSQL 18.4: "WITH $x AS (SELECT 1) SELECT a FROM x" is a syntax error ("at or near
      // $") -- "$" may only continue an identifier, never start one. With the first character
      // correctly gated by isIdentifierStartChar, no CTE name is found here at all, so this
      // returns null exactly as it does on main.
      val result = parseCteClause("WITH \$x AS (SELECT 1) SELECT a FROM x")
      assertThat(result).isNull()
    }
  }

  @Nested
  inner class ResolveCteOutputExpressionTest {

    @Test
    fun `resolves the issue #229 shape, an unqualified reference into a CTE-wrapped DML RETURNING expression`() {
      val sql = """
        WITH deleted_parent AS (
          DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
        )
        SELECT id, name, description_upper FROM deleted_parent
      """.trimIndent()
      val selectItem = SelectItem(expression = "description_upper", columnName = "description_upper", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(description)")
    }

    @Test
    fun `resolves a qualified reference to the single CTE`() {
      val sql = """
        WITH deleted_parent AS (
          DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
        )
        SELECT id, name, deleted_parent.description_upper FROM deleted_parent
      """.trimIndent()
      val selectItem = SelectItem(
        expression = "deleted_parent.description_upper",
        columnName = "description_upper",
        tableName = "deleted_parent",
      )

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(description)")
    }

    @Test
    fun `an unqualified reference to the single CTE resolves`() {
      val sql = """
        WITH a AS (SELECT UPPER(name) AS ux FROM parent)
        SELECT ux FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(name)")
    }

    @Test
    fun `a CTE body whose own nested WITH precedes the RETURNING or SELECT resolves correctly`() {
      val sql = """
        WITH deleted_parent AS (
          WITH inner_cte AS (SELECT 1 AS ignored)
          SELECT UPPER(description) AS description_upper FROM parent
        )
        SELECT description_upper FROM deleted_parent
      """.trimIndent()
      val selectItem = SelectItem(expression = "description_upper", columnName = "description_upper", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(description)")
    }

    @Test
    fun `a line comment inside the body's item list is stripped from the returned expression`() {
      // Regression test: the comment text used to leak verbatim into the returned expression
      // (a two-line "-- LOWER(name) AS ux\n UPPER(name)") instead of being stripped, because
      // extractAlias's own text-slicing keeps everything before the real "AS" -- including a
      // comment that happens to sit there -- and nothing downstream used to remove it.
      val sql = """
        WITH a AS (
          SELECT id, -- LOWER(name) AS ux
          UPPER(name) AS ux FROM parent
        )
        SELECT ux FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(name)")
    }

    @Test
    fun `a comment right after an opening parenthesis leaves no cosmetic whitespace in the returned expression`() {
      // stripComments replaces the comment with a single space (correctly, to avoid fusing "UPPER("
      // and "a" into one token) -- but embedding that space verbatim in generated KDoc would read
      // "UPPER( a)" instead of "UPPER(a)". collapseCosmeticWhitespace is what removes it afterward.
      val cosmeticOpenSql = """
        WITH c AS (SELECT UPPER(/* x */a) AS ux FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val cosmeticSelectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(cosmeticOpenSql, cosmeticSelectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a comment right before a closing parenthesis leaves no cosmetic whitespace in the returned expression`() {
      val cosmeticCloseSql = """
        WITH c AS (SELECT UPPER(a/* x */) AS ux FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val cosmeticSelectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(cosmeticCloseSql, cosmeticSelectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `no WITH clause at all returns null`() {
      val sql = "SELECT UPPER(description) AS description_upper FROM parent"
      val selectItem = SelectItem(expression = "description_upper", columnName = "description_upper", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `two or more declared CTEs returns null, even when only one is a real FROM source`() {
      val sql = """
        WITH a AS (SELECT UPPER(name) AS ux FROM parent), b AS (SELECT LOWER(name) AS ux FROM parent)
        SELECT ux FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `the main query's FROM naming a relation other than the CTE returns null`() {
      val sql = """
        WITH a AS (SELECT UPPER(name) AS ux FROM parent)
        SELECT ux FROM some_view
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `the main query's FROM naming the CTE alongside another source returns null`() {
      val sql = """
        WITH a AS (SELECT UPPER(name) AS ux FROM parent)
        SELECT ux FROM a, other_table
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `WITH RECURSIVE returns null, since the seed branch's expression misdescribes recursive rows`() {
      val sql = """
        WITH RECURSIVE a AS (
          SELECT UPPER(description) AS description_upper FROM parent WHERE id = 1
        )
        SELECT description_upper FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "description_upper", columnName = "description_upper", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a CTE declared with an explicit column list returns null, since positions are renamed`() {
      val sql = """
        WITH a(id, name, description_upper) AS (
          DELETE FROM parent WHERE id = ? RETURNING id, name, UPPER(description) AS description_upper
        )
        SELECT id, name, description_upper FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "description_upper", columnName = "description_upper", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a set operation in the CTE body returns null, not the first branch's value`() {
      val sql = """
        WITH a AS (
          SELECT UPPER(name) AS ux FROM t1
          UNION
          SELECT LOWER(name) AS ux FROM t2
        )
        SELECT ux FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a DML main query returns null, even though it names the CTE via its target relation`() {
      val sql = """
        WITH x AS (SELECT UPPER(name) AS ux FROM parent)
        DELETE FROM x WHERE id = 1 RETURNING ux
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a body item with an implicit alias (no AS) never matches`() {
      val sql = """
        WITH a AS (SELECT LOWER(name) ux FROM parent)
        SELECT ux FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a body item with no alias at all never matches`() {
      val sql = """
        WITH a AS (SELECT LOWER(name) FROM parent)
        SELECT lower FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "lower", columnName = "lower", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a matched body item that is itself a bare column reference returns null, a chained pass-through`() {
      val sql = """
        WITH a AS (SELECT description AS description_upper FROM parent)
        SELECT description_upper FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "description_upper", columnName = "description_upper", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `two body items folding to the same name returns null, ambiguous within the body itself`() {
      val sql = """
        WITH a AS (SELECT UPPER(name) AS ux, LOWER(name) AS "ux" FROM parent)
        SELECT ux FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a quoted body alias does not fold to match a differently-cased unquoted outer reference`() {
      // AS "UX" is compared EXACTLY (quoted, case preserved) -- it must not fold-match the outer
      // unquoted "ux" (which folds to lowercase), even though a naive case-insensitive comparison
      // would wrongly consider them equal.
      val sql = """
        WITH a AS (SELECT UPPER(name) AS "UX" FROM parent)
        SELECT ux FROM a
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a quoted CTE name referenced unquoted returns null, a different relation in PostgreSQL`() {
      // Regression test: the CTE is declared as "Foo" (quoted), but "FROM foo" (unquoted, folds
      // to lowercase "foo") names a DIFFERENT relation than the CTE "Foo" -- comparing via
      // CteDefinition.name (already quote-stripped) instead of rawName would wrongly fold both to
      // the same thing and resolve anyway.
      val sql = """
        WITH "Foo" AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM foo
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a quoted CTE name referenced with the identical quoted form resolves`() {
      val sql = """
        WITH "Foo" AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM "Foo"
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a quoted CTE name containing a space referenced quoted resolves`() {
      val sql = """
        WITH "My Cte" AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM "My Cte"
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `an unquoted CTE name referenced in a different case still resolves, via folding`() {
      val sql = """
        WITH foo AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM FOO
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `an unquoted qualifier never matches a quoted CTE name of the same letters`() {
      val sql = """
        WITH "Foo" AS (SELECT UPPER(a) AS ux FROM t)
        SELECT foo.ux FROM "Foo"
      """.trimIndent()
      val selectItem = SelectItem(expression = "foo.ux", columnName = "ux", tableName = "foo")

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a top-level UNION ALL in the main query returns null, not the first branch's value`() {
      // Regression test: SELECT_FROM_TERMINATOR_KEYWORDS includes UNION, so the FROM-region
      // search alone stops at "c" and wrongly looks like a clean match -- the explicit
      // hasTopLevelSetOperation check on the main query window is what actually rejects this.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM c UNION ALL SELECT b FROM other
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a top-level INTERSECT in the main query returns null, not the first branch's value`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM c INTERSECT SELECT b FROM other
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `an unquoted bare reference to the niladic keyword user returns null, not the aliased column`() {
      // Regression test, verified against a live PostgreSQL 18: "WITH c AS (SELECT UPPER(name)
      // AS user FROM parent) SELECT user FROM c" returns the session user (e.g. "postgres") in a
      // column labelled "user" -- PostgreSQL resolves the bare, unquoted keyword "user" to that
      // value, never to the body's own "AS user" column, no matter how confidently everything
      // else about this shape lines up.
      val sql = """
        WITH c AS (SELECT UPPER(name) AS user FROM parent)
        SELECT user FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "user", columnName = "user", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("user"))).isNull()
    }

    @Test
    fun `an unquoted bare reference to current_date returns null`() {
      val sql = """
        WITH c AS (SELECT UPPER(name) AS current_date FROM parent)
        SELECT current_date FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "current_date", columnName = "current_date", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("current_date"))).isNull()
    }

    @Test
    fun `an unquoted bare reference to CURRENT_USER returns null, case-insensitively`() {
      val sql = """
        WITH c AS (SELECT UPPER(name) AS CURRENT_USER FROM parent)
        SELECT CURRENT_USER FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "CURRENT_USER", columnName = "CURRENT_USER", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("current_user"))).isNull()
    }

    // No test for a quoted `"user"` outer reference resolving: parseColumnReference (via
    // COLUMN_REFERENCE) never produces a quoted SelectItem.columnName from real SQL -- a quoted
    // outer reference makes columnName null end-to-end, so resolveCteOutputExpression already
    // returns null via that unrelated, earlier check, never via the reserved-keyword guard. No
    // reachable input exercises this guard's own quoted-identifier branch at all.

    @Test
    fun `an unquoted bare reference to system_user returns null, a keyword added in PostgreSQL 16`() {
      // Regression test, verified against a live PostgreSQL 18.4: system_user resolves to the
      // keyword (the session's system-level identity), never to the CTE column, even though the
      // result column is labelled "system_user" (matching the property name exactly).
      val sql = """
        WITH c AS (SELECT UPPER(a) AS system_user FROM t)
        SELECT system_user FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "system_user", columnName = "system_user", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("system_user"))).isNull()
    }

    @Test
    fun `an unquoted bare reference to SYSTEM_USER in mixed case returns null, via folding`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS SYSTEM_USER FROM t)
        SELECT SYSTEM_USER FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "SYSTEM_USER", columnName = "SYSTEM_USER", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("system_user"))).isNull()
    }

    @Test
    fun `an unquoted bare reference to the literal true returns null, a boolean value not a column`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS true FROM t)
        SELECT true FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "true", columnName = "true", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("true"))).isNull()
    }

    @Test
    fun `an unquoted bare reference to the literal false returns null`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS false FROM t)
        SELECT false FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "false", columnName = "false", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("false"))).isNull()
    }

    @Test
    fun `an unquoted bare reference to the literal null returns null`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS null FROM t)
        SELECT null FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "null", columnName = "null", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("null"))).isNull()
    }

    @Test
    fun `an unquoted bare reference to the non-niladic reserved word select returns null`() {
      // "select" can never appear as a bare, unaliased select-list item in real PostgreSQL (a
      // syntax error), so this exact query could never reach this function in practice -- but the
      // guard covers the whole reserved set for free, per resolveCteOutputExpression's own
      // reservedWords parameter doc, and must not be fooled by an alias/reference that merely
      // LOOKS like this shape.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "select" FROM t)
        SELECT select FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "select", columnName = "select", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("select"))).isNull()
    }

    @Test
    fun `an unquoted bare reference to the non-niladic reserved word join returns null`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "join" FROM t)
        SELECT join FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "join", columnName = "join", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("join"))).isNull()
    }

    @Test
    fun `an unquoted bare reference to localtime returns null`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS localtime FROM t)
        SELECT localtime FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "localtime", columnName = "localtime", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("localtime"))).isNull()
    }

    @Test
    fun `a top-level EXCEPT in the main query returns null, not the first branch's value`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM c EXCEPT SELECT b FROM other
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a trailing line comment after the alias no longer silences resolution`() {
      // Regression test: the alias used to be extracted as the raw, unbounded remainder of the
      // item text after AS, so "ux -- comment" became the "alias" verbatim and never fold-matched
      // the outer "ux" at all.
      val sql = """
        WITH c AS (
          SELECT UPPER(a) AS ux -- comment
          FROM t
        )
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a trailing block comment after the alias no longer silences resolution`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux /* comment */ FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a block comment between AS and the alias no longer silences resolution`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS /* comment */ ux FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a block comment between the expression and AS already resolved and still does`() {
      // Not a regression, since the comment sits in the EXPRESSION half (already stripped by the
      // existing stripComments call on the returned value), not the alias half -- kept as an
      // explicit regression guard for this specific comment position, per the four required shapes.
      val sql = """
        WITH c AS (SELECT UPPER(a) /* comment */ AS ux FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a comment containing a comma right after the alias does not leak into the alias or split early`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux /* note, with a comma */, id FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a single-quoted string literal after AS is never mistaken for an alias`() {
      // "AS 'ux'" is not legal PostgreSQL syntax for an alias at all (a string literal, not an
      // identifier) -- must stay null, never wrongly resolve as if 'ux' matched the outer ux.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS 'ux' FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a comment merely mentioning the outer name elsewhere does not cause a false match`() {
      val sql = """
        WITH c AS (SELECT LOWER(a) AS uy /* this is not ux */ FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a quoted alias with no separating space after AS resolves`() {
      // "AS"ux"" -- a quoted identifier needs no whitespace before it. Already correctly handled
      // by extractAlias's own AS-keyword-boundary detection (see its KDoc); this locks in that the
      // alias text extracted from that position is the clean quoted token, not a fluke.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS"ux" FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a no-space quoted alias containing a space, with a matching quoted outer reference, now resolves`() {
      // Previously reported as unreachable: a quoted outer reference used to always yield
      // columnName == null (parseColumnReference/COLUMN_REFERENCE never matched a quoted shape at
      // all), so this exact combination could never reach this function with a usable name. Now
      // that quoted references parse to their logical value (see SelectItem's own KDoc), the
      // outer item is built via the REAL parser (parseSelectItems), not constructed by hand, to
      // prove this is genuinely reachable end-to-end, not merely an artificial SelectItem.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS"My Col" FROM t)
        SELECT "My Col" FROM c
      """.trimIndent()
      val mainQuery = "SELECT \"My Col\" FROM c"
      val selectItem = parseSelectItems(mainQuery).single()

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a quoted reference to the word user is a legitimate column reference, never punted`() {
      // Regression test for the reserved-keyword guard's quote-awareness: a quoted "user" is an
      // ordinary column reference in PostgreSQL, unlike the bare, unquoted keyword -- it must
      // resolve normally, not be silently punted the way an unquoted "user" correctly still is.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS"user" FROM t)
        SELECT "user" FROM c
      """.trimIndent()
      val selectItem = parseSelectItems("SELECT \"user\" FROM c").single()

      assertThat(resolveCteOutputExpression(sql, selectItem, reservedWords = setOf("user"))).isEqualTo("UPPER(a)")
    }

    @Test
    fun `an escaped double-quote inside a body alias does not truncate it into a shorter, colliding name`() {
      // Regression test, the exact P0 repro: before this round's fixes, parseAliasToken stopped
      // at the FIRST internal quote (escape-unaware) AND had no trailing-content check at all, so
      // "zz""q" was wrongly extracted as just "zz" -- which collided with the OTHER item's
      // implicit alias "zz" and made this resolve to "UPPER(a)" (verified directly against that
      // original, doubly-unfixed state). Reverting ONLY the quoted-scan back to escape-unaware
      // while KEEPING the later trailing-content check instead reproduces `null`: the truncated
      // scan leaves `q"` unconsumed after the fake "closing" quote, and the trailing-content check
      // added alongside the `AS ux zz` fix rejects that leftover text, so this specific single-line
      // mutation does not reproduce the wrong "UPPER(a)" answer on its own -- both fixes together
      // are what closed the bug. The correct answer is null: the escape-aware scan correctly reads
      // the full name "zz\"q", which does not collide with "zz" at all, but
      // this function still does not support matching an IMPLICIT alias (item 2's "zz" has no
      // AS), so there is no candidate left to match the outer "zz" against -- a real PostgreSQL
      // server resolves this to "LOWER(b)" via that implicit alias, which this function correctly
      // declines to guess, rather than emitting either PostgreSQL's real answer or a wrong one.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "zz""q", LOWER(b) zz FROM t)
        SELECT zz FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "zz", columnName = "zz", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `an escaped double-quote alias does not cross-match an unrelated, explicitly-aliased name`() {
      // Same body shape as the P0 repro, but item 2 now has an EXPLICIT alias "zz" (not implicit)
      // -- isolating specifically that the escaped-quote item ("zz\"q") does NOT wrongly match
      // the outer "zz" reference, independent of the implicit-alias limitation above. Resolves
      // correctly through item 2 alone.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "zz""q", LOWER(b) AS zz FROM t)
        SELECT zz FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "zz", columnName = "zz", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("LOWER(b)")
    }

    @Test
    fun `a body alias with an escaped double-quote resolves when the outer reference matches exactly`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "He""llo" FROM t)
        SELECT "He""llo" FROM c
      """.trimIndent()
      val selectItem = parseSelectItems("SELECT \"He\"\"llo\" FROM c").single()

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `an unquoted non-ASCII outer reference folds ASCII-only, matching the differently-cased alias`() {
      // Regression test, verified against a live PostgreSQL 18.4: PostgreSQL's own downcase_identifier
      // folds ONLY plain ASCII A-Z, never a non-ASCII letter -- "SELECT Ü FROM (SELECT 1 AS "ü") s"
      // fails with "column "Ü" does not exist" in real PostgreSQL, so the bare "Ü" must NOT be
      // treated as matching a body alias quoted as "ü". Here "ü" and "Ü" are two DIFFERENT aliases,
      // and the bare outer "Ü" must match the one actually named "Ü", not the one named "ü".
      // Verified directly: using Kotlin's String.lowercase() here instead reproduces the WRONG,
      // swapped answer ("UPPER(a)") on this exact input.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "ü", LOWER(b) AS "Ü" FROM t)
        SELECT Ü FROM c
      """.trimIndent()
      val selectItem = parseSelectItems("SELECT Ü FROM c").single()

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("LOWER(b)")
    }

    @Test
    fun `the reverse non-ASCII arrangement resolves through the other alias`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "Ü", LOWER(b) AS "ü" FROM t)
        SELECT Ü FROM c
      """.trimIndent()
      val selectItem = parseSelectItems("SELECT Ü FROM c").single()

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a single non-ASCII quoted alias differing only by non-ASCII case from the outer reference is null`() {
      // Only "ü" is defined; the bare outer "Ü" must not be folded down to match it -- PostgreSQL
      // itself would report "column \"Ü\" does not exist" for this exact shape, so null is not
      // merely a safe punt here, it agrees with what a real server would do.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "ü" FROM t)
        SELECT Ü FROM c
      """.trimIndent()
      val selectItem = parseSelectItems("SELECT Ü FROM c").single()

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a quoted uppercase alias does not fold-match a differently-quoted unquoted lowercase outer reference`() {
      // ASCII control (negative half): AS "UX" is a quoted alias, addressable ONLY by its exact
      // case -- an unquoted "ux" folds to lowercase "ux", which does not equal "UX" at all. This
      // must stay null regardless of the ASCII-only fold fix (quoting still means exact compare).
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "UX" FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = parseSelectItems("SELECT ux FROM c").single()

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `an unquoted uppercase outer reference still folds to match an unquoted lowercase alias`() {
      // ASCII control (positive half): both "ux" (body alias) and "UX" (outer reference) are
      // UNQUOTED, so both fold via plain ASCII case-folding to "ux" -- proving the ASCII-only fold
      // fix did not also break ordinary ASCII case-insensitivity for unquoted identifiers.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux FROM t)
        SELECT UX FROM c
      """.trimIndent()
      val selectItem = parseSelectItems("SELECT UX FROM c").single()

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a second bare word after the alias token discards nothing -- the whole alias is rejected`() {
      // "AS ux zz" is not legal PostgreSQL (a second bare word cannot follow a completed alias),
      // so unreachable in practice -- but must not silently keep "ux" and discard "zz" the way the
      // escape-unaware truncation bug above did; verified directly: without the trailing-garbage
      // check, this wrongly resolves to "UPPER(a)" via a truncated "ux" alias.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux zz FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isNull()
    }

    @Test
    fun `a no-space quoted alias combined with a trailing comment also resolves`() {
      val sql = """
        WITH c AS (SELECT UPPER(a) AS"ux" /* comment */ FROM t)
        SELECT ux FROM c
      """.trimIndent()
      val selectItem = SelectItem(expression = "ux", columnName = "ux", tableName = null)

      assertThat(resolveCteOutputExpression(sql, selectItem)).isEqualTo("UPPER(a)")
    }
  }

  @Nested
  inner class FindTopLevelKeywordTest {

    @Test
    fun `does not match a keyword inside a single-quoted string literal`() {
      val result = findTopLevelKeyword("UPDATE t SET name = 'copied from source' WHERE id = 1", "FROM")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `does not match a keyword inside an E-string with a backslash-escaped quote`() {
      // "\\'" inside an E-string is an escaped quote, not the string's terminator. A scanner
      // that (wrongly) treats it as the terminator would see the string as ending right there,
      // exposing " FROM here'" as ordinary top-level text — including a false-positive "FROM".
      val result = findTopLevelKeyword(E_STRING_WITH_ESCAPED_QUOTE_CONTAINING_FROM, "FROM")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `does not match a keyword inside a dollar-quoted string`() {
      val result = findTopLevelKeyword("UPDATE t SET name = \$\$copied from source\$\$ WHERE id = 1", "FROM")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `does not match a keyword inside a tagged dollar-quoted string`() {
      val result = findTopLevelKeyword("UPDATE t SET name = \$tag\$copied from source\$tag\$ WHERE id = 1", "FROM")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `does not match a keyword inside a double-quoted identifier`() {
      val result = findTopLevelKeyword("""UPDATE t SET "from column" = 1 WHERE id = 2""", "FROM")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `does not match a keyword inside a line comment`() {
      val result = findTopLevelKeyword("UPDATE t SET name = 'x' -- copied FROM elsewhere\nWHERE id = 1", "FROM")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `does not match a keyword inside a block comment`() {
      val result = findTopLevelKeyword("UPDATE t /* set FROM the caller */ SET name = 'x'", "FROM")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `still finds a real top-level keyword after a string literal containing the keyword text`() {
      val sql = "UPDATE t SET name = 'copied from source' FROM a WHERE id = 1"
      val result = findTopLevelKeyword(sql, "FROM")
      assertThat(result).isEqualTo(sql.indexOf("FROM a"))
    }

    @Test
    fun `does not match WHEN NOT MATCHED BY SOURCE inside a string literal`() {
      // The literal keyword text 'when not matched by source ' must not be mistaken for the
      // real clause — this is exactly the input hasWhenNotMatchedBySourceClause scans, via
      // repeated findTopLevelKeyword("WHEN", ...) calls.
      val result = findTopLevelKeyword("SET tname = 'when not matched by source '", "WHEN")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `does not match FROM inside the identifier valid_from`() {
      // Before the word-boundary fix, "_" did not count as an identifier character, so the
      // character after "FROM" in "valid_from" ("_" is not letterOrDigit) was wrongly treated as
      // a valid word boundary, matching "FROM" inside the column name itself.
      val sql = "UPDATE t SET valid_from = 'x' FROM a"
      val result = findTopLevelKeyword(sql, "FROM")
      assertThat(result).isEqualTo(sql.indexOf("FROM a"))
    }

    @Test
    fun `does not match FROM inside the identifier from_date`() {
      val sql = "UPDATE t SET from_date = 'x' FROM a"
      val result = findTopLevelKeyword(sql, "FROM")
      assertThat(result).isEqualTo(sql.indexOf("FROM a"))
    }

    @Test
    fun `does not match SET inside the identifier data_set`() {
      // "data_set" as a TABLE name ends in "_set" — the character before "set" is "_", which
      // must count as an identifier character so the real "SET" keyword afterward is what's found.
      val sql = "UPDATE data_set SET x = 1"
      val result = findTopLevelKeyword(sql, "SET")
      assertThat(result).isEqualTo(sql.indexOf("SET x"))
    }

    @Test
    fun `does not match RETURNING inside the identifier returning_note`() {
      val sql = "UPDATE t SET returning_note = 'x' RETURNING id"
      val result = findTopLevelKeyword(sql, "RETURNING")
      assertThat(result).isEqualTo(sql.indexOf("RETURNING id"))
    }

    @Test
    fun `does not match USING inside the identifier using_note`() {
      val sql = "DELETE FROM using_note USING a"
      val result = findTopLevelKeyword(sql, "USING")
      assertThat(result).isEqualTo(sql.indexOf("USING a"))
    }

    @Test
    fun `does not match SET as a standalone keyword when it is only the prefix of set_at`() {
      val result = findTopLevelKeyword("SELECT set_at FROM t", "SET")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `does not match FROM inside an identifier containing a dollar sign`() {
      // PostgreSQL allows "$" inside (not at the start of) an unquoted identifier — it must
      // count as an identifier character for word-boundary purposes, the same as "_".
      val sql = "UPDATE t SET x\$from = 1 FROM a"
      val result = findTopLevelKeyword(sql, "FROM")
      assertThat(result).isEqualTo(sql.indexOf("FROM a"))
    }

    @Test
    fun `does not match FROM when immediately followed by a non-ASCII identifier-continuation character`() {
      // Issue #219: PostgreSQL's lexer admits any byte >= 0x80 inside an unquoted identifier, so
      // "from€" is a single identifier, not the keyword FROM followed by a stray "€".
      val result = findTopLevelKeyword("SELECT * FROM€ t", "FROM")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `does not match FROM when immediately preceded by a non-ASCII identifier-continuation character`() {
      // Same fact, leading side: "€from" is a single identifier, not a stray "€" followed by the
      // keyword FROM.
      val result = findTopLevelKeyword("SELECT * €FROM t", "FROM")
      assertThat(result).isEqualTo(-1)
    }

    @Test
    fun `bails to not-found once an unmatched closing parenthesis drives depth negative`() {
      // A bare ")" with no matching "(" before it means the input is not the well-formed,
      // balanced text this scan assumes. Without a floor, depth stays negative until a LATER
      // unmatched "(" happens to bring it back to exactly 0 — at which point a keyword genuinely
      // inside that malformed region would wrongly be treated as top-level. Bailing to -1 at the
      // first negative dip is the safe direction: an honest "not found" over a confidently wrong
      // match.
      val result = findTopLevelKeyword(") (x FROM t", "FROM")
      assertThat(result).isEqualTo(-1)
    }
  }

  @Nested
  inner class FindTopLevelReturningKeywordTest {

    @Test
    fun `a trailing non-ASCII character on a returning-shaped identifier is not mistaken for the bare keyword`() {
      // The exact shape from #219: "returning€" is a legal PostgreSQL column name (PostgreSQL's
      // lexer admits any byte >= 0x80 inside an unquoted identifier), so this must not be split
      // into the bare word "returning" plus a stray "€".
      val sql = "INSERT INTO users (email, age, preferences) " +
        "SELECT returning€, age, preferences FROM src RETURNING id, email"
      val result = findTopLevelReturningKeyword(sql)
      assertThat(result).isEqualTo(sql.indexOf("RETURNING id, email"))
    }

    @Test
    fun `a supplementary-plane character abutting the keyword prevents a false match`() {
      // A surrogate pair's two UTF-16 code units are each >= 0x80 -- no special surrogate
      // handling is needed, the same per-character loop that handles "€" handles this correctly.
      val sql = "INSERT INTO users (email) " +
        "SELECT returning🚀, email FROM src RETURNING id, email"
      val result = findTopLevelReturningKeyword(sql)
      assertThat(result).isEqualTo(sql.indexOf("RETURNING id, email"))
    }

    @Test
    fun `a standalone non-breaking space before a returning-shaped word is not split off as a separator`() {
      // Kotlin's Char.isWhitespace() is true for U+00A0 (no-break space), but PostgreSQL's lexer
      // does not treat it as whitespace at all -- it's an ordinary (>= 0x80) identifier
      // character. If the whitespace branch were checked before the identifier branch, the
      // U+00A0 would be consumed as a plain separator, leaving "returning" to stand alone right
      // after it and be misread as the bare keyword.
      val sql = "SELECT a, " + "\u00A0returning, b FROM t RETURNING id"
      val result = findTopLevelReturningKeyword(sql)
      assertThat(result).isEqualTo(sql.indexOf("RETURNING id"))
    }

    @Test
    fun `still finds the real keyword when it is not preceded by AS`() {
      // NOT demonstrative of any fix in this file — the AS-preceded-alias exclusion predates this
      // branch's >= 0x80 boundary work (it's the #212-era fix). Kept as a regression guard that
      // the ordinary, already-working case (a real RETURNING clause with no AS before it) still
      // matches.
      val sql = "DELETE FROM t WHERE id = 1 RETURNING id"
      val result = findTopLevelReturningKeyword(sql)
      assertThat(result).isEqualTo(sql.indexOf("RETURNING id"))
    }

    @Test
    fun `does not match an AS-preceded column alias`() {
      // NOT demonstrative of any fix in this file — same reasoning as above: the AS-preceded
      // exclusion is pre-existing behavior, not part of this branch's >= 0x80 boundary work. Kept
      // as a regression guard against that exclusion becoming overly broad or narrow.
      val sql = "SELECT email AS returning, x FROM t"
      val result = findTopLevelReturningKeyword(sql)
      assertThat(result).isEqualTo(-1)
    }
  }

  @Nested
  inner class FindMatchingCloseParenthesisTest {

    @Test
    fun `does not match a closing parenthesis inside a string literal`() {
      val text = "(regexp_replace(name, '\\(', ''))"
      val result = findMatchingCloseParenthesis(text, 0)
      assertThat(result).isEqualTo(text.length - 1)
    }
  }

  @Nested
  inner class SkipOptionalKeywordTest {

    @Test
    fun `does not consume OUTER when immediately followed by a non-ASCII identifier-continuation character`() {
      // Issue #219: "OUTER€" is a single identifier, not the keyword OUTER followed by a stray
      // "€" -- the position must be left unchanged, exactly as for any other non-matching text.
      val sql = "OUTER€ JOIN b"
      assertThat(skipOptionalKeyword(sql, 0, "OUTER")).isEqualTo(0)
    }

    @Test
    fun `still consumes a real keyword followed by ordinary whitespace`() {
      // NOT demonstrative of any fix in this file — a positive control confirming the ordinary,
      // already-working case (a real keyword followed by plain whitespace) still advances past
      // it. Kept as a regression guard against the non-ASCII boundary check above becoming overly
      // broad and rejecting this too.
      val sql = "OUTER JOIN b"
      assertThat(skipOptionalKeyword(sql, 0, "OUTER")).isEqualTo(sql.indexOf("JOIN"))
    }
  }

  @Nested
  inner class DollarQuoteIdentifierTest {

    @Test
    fun `does not match FROM inside a SET-clause column named a-dollar-b-dollar-c`() {
      // The historical bug: the "$" between "b" and "c" was read as opening a "$b$"-tagged
      // dollar-quote, swallowing the rest of the string looking for a closing "$b$" that never
      // comes — so the real "FROM a" was never found at all.
      val sql = "UPDATE t SET a\$b\$c = 'x' FROM a"
      val result = findTopLevelKeyword(sql, "FROM")
      assertThat(result).isEqualTo(sql.indexOf("FROM a"))
    }

    @Test
    fun `does not match FROM inside a SET-clause column named x-dollar-dollar-y`() {
      val sql = "UPDATE t SET x\$\$y = 'x' FROM a"
      val result = findTopLevelKeyword(sql, "FROM")
      assertThat(result).isEqualTo(sql.indexOf("FROM a"))
    }

    @Test
    fun `still matches a keyword inside a genuine bare dollar-quoted string`() {
      // A "$" preceded by whitespace (not an identifier character) legitimately opens a
      // dollar-quote — this must keep working.
      val sql = "UPDATE t SET name = \$\$copied from source\$\$ FROM a"
      val result = findTopLevelKeyword(sql, "FROM")
      assertThat(result).isEqualTo(sql.indexOf("FROM a"))
    }

    @Test
    fun `still matches a keyword inside a genuine tagged dollar-quoted string`() {
      val sql = "UPDATE t SET name = \$tag\$copied from source\$tag\$ FROM a"
      val result = findTopLevelKeyword(sql, "FROM")
      assertThat(result).isEqualTo(sql.indexOf("FROM a"))
    }

    @Test
    fun `a dollar-sign positional-parameter-style marker is not treated as a dollar quote`() {
      // "$1" has no closing "$1" tag anywhere, so it must be treated as ordinary text (already
      // the behavior pre-fix too — this guards it doesn't regress alongside the identifier fix).
      val sql = "SELECT \$1 FROM a"
      val result = findTopLevelKeyword(sql, "FROM")
      assertThat(result).isEqualTo(sql.indexOf("FROM a"))
    }

    @Test
    fun `does not open a dollar quote immediately after a non-ASCII identifier-continuation character`() {
      // Issue #219: under PostgreSQL's flex longest-match, "x€\$\$y\$\$" is ONE identifier, since
      // "€" (>= 0x80) is itself a legal identifier-continuation character -- so the "\$" right
      // after it must not be treated as opening a dollar-quoted string.
      val sql = "x€\$\$y\$\$"
      val dollarIndex = sql.indexOf('$')
      assertThat(skipLexicalToken(sql, dollarIndex)).isEqualTo(dollarIndex)
    }

    @Test
    fun `a dollar-quote tag containing a non-ASCII character is recognized as a tag`() {
      // scan.l's dolq_start/dolq_cont admit any byte >= 0x80, same as an ordinary identifier's
      // ident_start/ident_cont -- so "€" is a legal (one-character) tag. Before the fix, the tag
      // run used the narrow letter/digit/underscore class, which doesn't include "€", so the run
      // ended immediately (empty tag), the closing delimiter search looked for a bare "$$" instead
      // of "$€$", and never found one before the real one -- swallowing everything after it.
      val sql = "\$€\$x,\$€\$ AS lbl"
      val result = skipLexicalToken(sql, 0)
      assertThat(result).isEqualTo(sql.indexOf(" AS lbl"))
    }

    @Test
    fun `a digit-leading dollar-quote tag is not recognized as a tag`() {
      // Verified against PostgreSQL 18.4: scan.l's dolq_start excludes digits (unlike dolq_cont,
      // which admits them after the first character) -- "$1$foo$1$" is not a dollar-quoted
      // string at all; "$1" is left as an ordinary "$"-prefixed token. Before the fix, the tag
      // run's start character used the same letter/digit/underscore class as its continuation, so
      // a leading digit was wrongly accepted as if it opened a valid tag.
      val sql = "\$1\$foo\$1\$"
      assertThat(skipLexicalToken(sql, 0)).isEqualTo(0)
    }
  }

  @Nested
  inner class SkipSingleQuotedStringTest {

    @Test
    fun `a non-ASCII character before a standalone E means it is not standalone, matching PostgreSQL`() {
      // Real PostgreSQL treats "x€E" as ONE identifier -- "€" is a legal identifier-continuation
      // character -- so the "E" immediately before the quote is NOT a standalone E'...'
      // escape-string marker here. With no escape-string mode, the backslash before the closing
      // quote is an ordinary character, not an escape: the first bare "'" after it -- not a
      // doubled "''" -- ends the string.
      //
      // Before OriginalAdjacency existed, this lookback used a narrow letter/digit/underscore-only
      // class that did not recognize "€" as an identifier character at all, wrongly treating "E"
      // as standalone here and accepting this as a deliberate, KNOWN deviation from PostgreSQL's
      // lexer (issue #222). Now that the lookback can distinguish a genuinely fused character from
      // one a stripped-away separator merely left adjacent (see OriginalAdjacency's KDoc), it is
      // safe to use the full isIdentifierChar class -- and for this RAW, never-stripped call
      // (using the default ALL_ADJACENT, correct here since every neighbour genuinely IS
      // adjacent), that makes this call match PostgreSQL's real answer instead of deviating from
      // it.
      val sql = "x€E'a\\' FROM t"
      val quoteIndex = sql.indexOf('\'')
      val result = skipLexicalToken(sql, quoteIndex)
      assertThat(sql.substring(quoteIndex, result)).isEqualTo("'a\\'")
    }

    @Test
    fun `a genuinely separate string immediately after an E-string is not swallowed by a stripped-away separator`() {
      // Point 3 of issue #223's fix: fusion composes with escape-string mode. "E'a'" (a complete
      // escape string) followed by a real separator, then another, entirely independent "'\'"
      // strips (the separator is plain whitespace, which stripCommentsAndWhitespace removes the
      // same as a comment) to "E'a''\'", where the fused "''" reads as a doubled-quote escape and
      // the "\" that follows reads as escaping the final "'" -- overrunning to the end of the
      // text, which would defeat findTrailingImplicitAliasStart's "last segment must end exactly
      // at text.length" anchor in the dangerous direction (see isStarItem's KDoc).
      //
      // NOT achievable as valid SQL end to end, so this is a defensive gate, not a repro of a
      // reachable bug: verified against a real PostgreSQL 18.4 container, PostgreSQL only
      // concatenates two adjacent bare string literals when the whitespace between them contains
      // an actual newline ("SELECT 'a' 'b'" and "SELECT 'a'/*c*/'b'" are both syntax errors --
      // neither a plain space nor a comment alone licenses concatenation), and a genuine
      // newline-separated concatenation is a different construct with its own surprising semantics
      // -- "SELECT E'a'\n'\'" is ITSELF "unterminated" on real PostgreSQL, because the escape-string
      // mode is inherited across the concatenation boundary. No PostgreSQL-valid SELECT item was
      // found that exercises this specific composition end to end; the gate is kept anyway, for
      // the same structural reason every other multi-character adjacency decision in this file is
      // gated (see OriginalAdjacency's KDoc), not because a concrete wrong-answer case was found.
      val stripped = stripCommentsAndWhitespace("E'a' '\\'")
      // Without the gate, an ungated re-scan of stripCommentsAndWhitespace's own OUTPUT (simulated
      // here via ALL_ADJACENT, i.e. pretending every neighbour really was adjacent) overruns to the
      // end of the stripped text.
      assertThat(skipLexicalToken(stripped.asPlainString(), 1, ALL_ADJACENT)).isEqualTo(stripped.length)
      // Gated on real adjacency, the scan correctly stops right after the first string's own
      // closing quote instead.
      assertThat(stripped.skipLexicalToken(1)).isEqualTo(4)
    }
  }

  @Nested
  inner class OriginalAdjacencyGateTest {

    @Test
    fun `a stripped-away separator does not let two independently-lexed dashes read as a line comment`() {
      // "1 - -1" (two separate "-" tokens, genuinely separated by a space) strips to "1--1" -- see
      // OriginalAdjacency's own KDoc. Without the gate, skipLexicalToken would read the fused "--"
      // as a line-comment opener that was never in the original query, jumping all the way to the
      // end of the (stripped) text.
      val stripped = stripCommentsAndWhitespace("1 - -1")
      assertThat(stripped.skipLexicalToken(1)).isEqualTo(1)
    }

    @Test
    fun `a stripped-away separator does not let two independently-lexed characters read as a block comment`() {
      // "a / *b" (a "/" and a "*", genuinely separated by a space) strips to "a/*b", which would
      // otherwise look like a block-comment opener that was never in the original query.
      val stripped = stripCommentsAndWhitespace("a / *b")
      assertThat(stripped.skipLexicalToken(1)).isEqualTo(1)
    }

    @Test
    fun `a stripped-away separator still lets a dollar-quote open, even though an identifier character now abuts it`() {
      // "x $q$a$q$" (an identifier "x", genuinely separated from a dollar-quoted string by a
      // space) strips to "x$q$a$q$" -- the "x" now directly abuts the "$", which would otherwise
      // look like "x" continuing into the dollar-quote tag (PostgreSQL allows "$" inside an
      // unquoted identifier), hiding the real, separate dollar-quoted string and scanning its body
      // as ordinary SQL instead.
      val stripped = stripCommentsAndWhitespace("x \$q\$a\$q\$")
      assertThat(stripped.skipLexicalToken(1)).isEqualTo(stripped.length)
    }
  }

  @Nested
  inner class FunctionCallStartTest {

    @Test
    fun `captures the whole dollar-containing function name, not just the run after the dollar sign`() {
      // FUNCTION_CALL_START previously used a bare "\w+", which excludes "$" -- "\w+" cannot match
      // "my$fn" as one run, so findAll instead matched the shorter run "fn" immediately before the
      // "(", handing SqlParameterInferrer.extractFunctionCalls the WRONG function name. Verified
      // against a real PostgreSQL 18.4: "my$fn" is a legal unquoted function name (CREATE FUNCTION
      // "my$fn"(...) and the unquoted call my$fn(...) resolve to the same function).
      val match = FUNCTION_CALL_START.find("SELECT my\$fn(?)")
      assertThat(match!!.groupValues[1]).isEqualTo("my\$fn")
    }

    @Test
    fun `captures a function name continuing with a non-ASCII character`() {
      // "\w+" is ASCII-only, so it also excludes any ">= 0x80" character -- verified against a
      // real PostgreSQL 18.4: an unquoted function named "fn€" is legal and callable unquoted.
      val match = FUNCTION_CALL_START.find("SELECT fn€(?)")
      assertThat(match!!.groupValues[1]).isEqualTo("fn€")
    }

    @Test
    fun `does not capture a digit as the start of a function name`() {
      // Verified against a real PostgreSQL 18.4: "2fn(...)" is rejected outright ("trailing junk
      // after numeric literal") -- a digit may never START an identifier. The regex's own
      // leading-character restriction means a match beginning with "2" is impossible; the only
      // match found here starts at "f", the same "two positions collapsed onto one class" mistake
      // issue #219 fixed for COLUMN_REFERENCE.
      val match = FUNCTION_CALL_START.find("SELECT 2fn(?)")
      assertThat(match!!.groupValues[1]).isEqualTo("fn")
    }
  }

  @Nested
  inner class LexicalTokenParityTest {

    /**
     * Walks [sql] position by position, collecting the text of every OPAQUE lexical token
     * [skipLexicalToken] recognizes (a string literal, a quoted identifier, a dollar-quoted
     * string) as one list entry, and every other non-whitespace character as its own
     * single-character entry. A recognized comment is dropped entirely, matching what
     * [stripCommentsAndWhitespace] itself deletes. This is exactly the token sequence
     * [stripCommentsAndWhitespace]'s output SHOULD still contain, in order — see
     * [lexicalTokensOfStripped] and this class's own test.
     */
    private fun lexicalTokensOfOriginal(sql: String): List<String> {
      val tokens = mutableListOf<String>()
      var i = 0
      while (i < sql.length) {
        if (sql[i].isWhitespace()) {
          i++
          continue
        }
        val afterToken = skipLexicalToken(sql, i)
        if (afterToken != i) {
          val tokenText = sql.substring(i, afterToken)
          if (!tokenText.startsWith("--") && !tokenText.startsWith("/*")) tokens.add(tokenText)
          i = afterToken
        } else {
          tokens.add(sql[i].toString())
          i++
        }
      }
      return tokens
    }

    /**
     * The same walk as [lexicalTokensOfOriginal], over [sql] after [stripCommentsAndWhitespace].
     * No whitespace-skip or comment-filter is needed here: stripping already removed every
     * top-level whitespace character and comment, and [StrippedText.skipLexicalToken] — correctly
     * gated on [OriginalAdjacency] — must never recognize a `--`/`/* */` opener that only exists
     * because stripping fused two characters together.
     */
    private fun lexicalTokensOfStripped(sql: String): List<String> {
      val stripped = stripCommentsAndWhitespace(sql)
      val plain = stripped.asPlainString()
      val tokens = mutableListOf<String>()
      var i = 0
      while (i < stripped.length) {
        val afterToken = stripped.skipLexicalToken(i)
        if (afterToken != i) {
          tokens.add(plain.substring(i, afterToken))
          i = afterToken
        } else {
          tokens.add(plain[i].toString())
          i++
        }
      }
      return tokens
    }

    @Test
    fun `a stripped-away separator does not fuse unrelated dollar signs and a tag run into a dollar-quote`() {
      // Verifier-supplied regression: "$q  b $/ /" strips (removing the two spaces after "q" and
      // the one after "b" and each "/") to "$qb$//", whose fused "$qb$" would otherwise be read as
      // an opening dollar-quote delimiter with tag "qb", and the following "//" as its (unterminated)
      // body -- one manufactured token in place of six independent original ones ("$", "q", "$",
      // "/", "/", plus the surrounding words), the same "danger" direction as every other fusion
      // class in this file (see OriginalAdjacency's KDoc): skipDollarQuotedString's OPENING-delimiter
      // adjacency gate (see its KDoc) must refuse this fusion. (The closing delimiter needs, and
      // has, no gate of its own -- see that function's KDoc for why.)
      //
      // No PostgreSQL-valid input is known to reach this composition: verified against a real
      // PostgreSQL 18.4 container, even the simpler "SELECT \$q FROM t" is rejected outright
      // ("syntax error at or near "\$"") -- a bare "\$" not immediately followed by a digit (a
      // positional parameter) or a genuine dollar-quote tag is not valid PostgreSQL syntax at all.
      // Like the "''"/E-string composition gate above, this closes the class structurally rather
      // than fixing an observed user-visible bug.
      val input = "SELECT \$q  b \$/ / FROM t"
      assertThat(lexicalTokensOfStripped(input)).isEqualTo(lexicalTokensOfOriginal(input))
    }

    @Test
    fun `stripping never changes what skipLexicalToken sees, for every issue 223 fusion class and every star shape`() {
      // The invariant issue #223 relies on: walking the ORIGINAL text and the STRIPPED text with
      // skipLexicalToken must find the SAME lexical tokens, in the SAME order — if stripping ever
      // fuses two characters into a token that was never in the original query (or hides one that
      // WAS there), this comparison catches it directly, rather than relying on each individual
      // gate's own, narrower unit test. Includes every fusion-class input from the issue, plus a
      // representative sample of the star shapes already covered elsewhere in this file (line and
      // block comments, string literals, dollar-quoted strings, double-quoted identifiers
      // containing a literal star, a Unicode-escape identifier with a UESCAPE clause, and
      // non-ASCII qualifiers).
      val inputs = listOf(
        "SELECT (ROW(1, 2 - -1)).* y, id, name FROM t",
        "SELECT (f(1 - -1)).* y, id FROM t",
        "SELECT (CAST(ROW(1 - -1, 2) AS pair)).* y, id FROM t",
        "SELECT (ARRAY[t])[1 - -1].* y, id FROM t",
        "SELECT (f(xq E'a\\'b')).* y, id FROM t",
        "SELECT (f(xq \$q\$a'b\$q\$)).* y, id FROM t",
        "SELECT (f(x€ E'a\\'b')).* y, id FROM t",
        "SELECT \$q  b \$/ / FROM t",
        "SELECT t.*-- c\n::text, a FROM t",
        "SELECT u.* whatever /*c*/, preferences FROM users u",
        "SELECT u.*/*c*/whatever, preferences FROM users u",
        """SELECT "my*table".*z, c FROM "my*table"""",
        """SELECT U&"my*table".*, c FROM "my*table"""",
        """SELECT t.* U&"d!0061t" UESCAPE '!', a FROM t""",
        "SELECT (t.*::t).* whatever, a FROM t",
        "SELECT (ARRAY[t.*])[1].* whatever, a FROM t",
        "SELECT (f(a*2)).* z, a FROM t",
        "SELECT t.* €total, a FROM t",
        "SELECT x€9.* w, preferences FROM users x€9",
        "SELECT 'lit *' foo, id FROM t",
      )
      for (input in inputs) {
        assertThat(lexicalTokensOfStripped(input)).isEqualTo(lexicalTokensOfOriginal(input))
      }
    }
  }

  private companion object {
    // E'text \' FROM here' — the \' is an escaped quote (part of the string content, not a
    // terminator), so "FROM" is inside the string the whole way through to the real closing '.
    const val E_STRING_WITH_ESCAPED_QUOTE_CONTAINING_FROM = "UPDATE t SET name = E'text \\' FROM here' WHERE id = 1"
  }
}
