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
      // Regression guard: an earlier attempt to close the "comment abuts an implicit alias with
      // no whitespace" gap made stripCommentsAndWhitespace emit a space in place of a comment when
      // keepWhitespace was true, so "t.*/*c*/::text" would normalize to "t.* ::text" — which,
      // after stripping the newly-introduced trailing-token boundary, wrongly recognized "t.*" as
      // a star and truncated "a" out of the result. That change was reverted specifically because
      // of this false positive; comments are removed outright with nothing put in their place, so
      // this must keep resolving to both items, exactly as it did before the implicit-alias check
      // (and the later, reverted comment-as-boundary attempt) existed.
      val result = parseSelectItems("SELECT t.*/*c*/::text, a FROM t")
      assertThat(result).containsExactly(
        SelectItem("t.*/*c*/::text", null, null),
        SelectItem("a", "a", null),
      )
    }

    @Test
    fun `a whitespace-separated cast on a star is not mistaken for an implicit alias`() {
      // Verified against a real PostgreSQL 18 container: "SELECT t.* ::text, a FROM t" is valid
      // and returns 2 columns (t, a), with NO star expansion. Before the trailing-token candidate
      // excluded tokens starting with "::", stripTrailingWhitespaceSeparatedToken treated "::text"
      // as a strippable implicit alias, reducing "t.* ::text" to "t.*" and wrongly triggering the
      // star guard — losing "a" out of the result, the same false-positive class round 7's
      // reverted comment-as-token-boundary change was reverted for, reached here through
      // whitespace instead of a comment.
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
      // trailing comment). Order matters here: stripTrailingWhitespaceSeparatedToken applied
      // DIRECTLY to "u.* whatever -- c" finds its LAST whitespace run right before the comment
      // (skipLexicalToken correctly treats the comment as an opaque token, not whitespace),
      // stripping the comment but leaving "whatever" attached ("u.* whatever") — not a star. Only
      // stripping the comment FIRST (via stripCommentsAndWhitespace(keepWhitespace = true).trim())
      // and THEN hunting for the trailing token recovers "u.*".
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
    fun `a comment abutting an implicit alias with no surrounding whitespace is a documented, unfixed limitation`() {
      // Pins a KNOWN, documented limitation (see parseSelectItems's KDoc) rather than a bug this
      // guard is meant to catch: an earlier attempt to close this via stripCommentsAndWhitespace
      // emitting a space in place of a comment (so "u.*/*c*/whatever" would normalize the same as
      // "u.* whatever") was reverted, because it introduced a DIFFERENT false positive
      // ("t.*/*c*/::text, a" wrongly truncated to a lost "a") that is strictly worse than this
      // accepted gap: none of the three candidates has any top-level whitespace to find a trailing
      // token boundary at, so the guard never fires and "preferences" survives at its raw list
      // position, silently misattributed if that position doesn't correspond to its real column.
      val result = parseSelectItems("SELECT u.*/*c*/whatever, preferences FROM users u")
      assertThat(result).containsExactly(
        SelectItem("u.*/*c*/whatever", null, null),
        SelectItem("preferences", "preferences", null),
      )
    }

    @Test
    fun `a line comment abutting an implicit alias is the same documented, unfixed limitation`() {
      // Verified against a real PostgreSQL 18 container: "SELECT t.*-- c\nwhatever, id FROM t"
      // against a 3-column "t" is valid and returns 4 real columns (t's own 3 plus "id"). A LINE
      // comment reaches the same unfixed gap as the block-comment case above even though it
      // contains a literal space character ("-- c"): skipLineComment consumes its own terminating
      // newline as part of ONE opaque comment token, so there is no whitespace left OUTSIDE that
      // token for any candidate to find a trailing-token boundary at — "whatever" stays attached,
      // the guard never fires, and "id" (the real 4th column) is misattributed to result column 2
      // (the real "t"'s second column) instead.
      val result = parseSelectItems("SELECT t.*-- c\nwhatever, id FROM t")
      assertThat(result).containsExactly(
        SelectItem("t.*-- c\nwhatever", null, null),
        SelectItem("id", "id", null),
      )
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
      val result = parseSelectItems("WITH c AS (SELECT 1) SELECT 5, 'x'")
      assertThat(result).containsExactly(
        SelectItem("5", "5", null),
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
  inner class ConvertDmlToSelectTest {

    @Test
    fun `returns null instead of throwing when independently-found indices are out of order`() {
      // Defensive test for convertDmlToSelect's index-ordering guard, independent of the
      // word-boundary fix: FROM and RETURNING are each found via a search anchored at the SAME
      // prior position (not at each other's match), so if they are ever returned in the wrong
      // relative order — by any bug, present or future, not only the historical
      // "returning_note"-style boundary bug — this must degrade to null rather than throw
      // StringIndexOutOfBoundsException. This SQL is not valid PostgreSQL syntax (RETURNING must
      // follow FROM, never precede it) — it exists purely to force that shape deterministically,
      // without depending on any particular scanner bug still existing.
      val result = convertDmlToSelect("UPDATE t SET x = 1 RETURNING id FROM a")
      assertThat(result).isNull()
    }

    @Test
    fun `does not throw for the historical returning_note boundary bug shape`() {
      // The exact shape that used to crash: "returning_note" (a SET-clause column) used to match
      // "RETURNING" as a keyword, making returningIndex point INSIDE the SET clause — earlier
      // than the join clause start computed from the (correctly found) later FROM — so
      // buildSelectFromDml's substring(joinClauseStart, returningIndex) threw
      // StringIndexOutOfBoundsException. The word-boundary fix means this SQL now converts
      // successfully instead of merely failing safely.
      val result = convertDmlToSelect(
        "UPDATE t SET returning_note = 'x' FROM a WHERE t.id = a.id RETURNING t.id",
      )
      assertThat(result).isNotNull()
    }
  }

  @Nested
  inner class HasWhenNotMatchedBySourceClauseTest {

    @Test
    fun `matches when a comment directly abuts NOT and MATCHED with no surrounding whitespace`() {
      // skipOptionalKeyword previously required LITERAL whitespace immediately after each
      // keyword, so a comment with no separating whitespace on either side ("NOT/*c*/MATCHED")
      // failed the boundary check entirely and the clause went undetected.
      val result = hasWhenNotMatchedBySourceClause("WHEN NOT/*c*/MATCHED BY SOURCE THEN DELETE")
      assertThat(result).isTrue()
    }

    @Test
    fun `matches when separated by a line comment`() {
      // NOT demonstrative of any fix: this shape already passed before the comment-adjacency
      // fix (skipWhitespaceAndComments already skipped a comment with surrounding whitespace on
      // both sides — only a comment with NO surrounding whitespace, tested above, was broken).
      // Kept as a regression guard for this already-working shape.
      val result = hasWhenNotMatchedBySourceClause("WHEN NOT -- c\n MATCHED BY SOURCE THEN DELETE")
      assertThat(result).isTrue()
    }

    @Test
    fun `matches when separated by a block comment with surrounding whitespace`() {
      // NOT demonstrative — same reasoning as the line-comment case above: whitespace on both
      // sides of the comment already worked. Kept as a regression guard.
      val result = hasWhenNotMatchedBySourceClause("WHEN /*c*/ NOT MATCHED BY SOURCE THEN DELETE")
      assertThat(result).isTrue()
    }

    @Test
    fun `matches when separated by newlines`() {
      // NOT demonstrative — newlines are whitespace, so this shape already worked. Kept as a
      // regression guard.
      val result = hasWhenNotMatchedBySourceClause("WHEN NOT\nMATCHED\nBY\nSOURCE THEN DELETE")
      assertThat(result).isTrue()
    }

    @Test
    fun `does not match a plain WHEN MATCHED clause`() {
      // NOT demonstrative — a negative control unaffected by any fix in this file. Kept as a
      // regression guard against detection becoming overly broad.
      val result = hasWhenNotMatchedBySourceClause("WHEN MATCHED THEN UPDATE SET x = 1")
      assertThat(result).isFalse()
    }

    @Test
    fun `convertDmlToSelect chooses RIGHT JOIN when the clause has a comment abutting NOT and MATCHED`() {
      // Exercises the same comment-adjacency fix through the full conversion path that actually
      // selects the join keyword, not just the boolean detector in isolation.
      val sql = "MERGE INTO tgt USING src ON tgt.id = src.id " +
        "WHEN NOT/*c*/MATCHED BY SOURCE THEN DELETE RETURNING tgt.id, src.sval"
      val result = convertDmlToSelect(sql)
      assertThat(result).isNotNull()
      assertThat(result!!).contains("RIGHT JOIN")
    }
  }

  @Nested
  inner class IsInsertBodyTest {

    @Test
    fun `recognizes a plain INSERT`() {
      assertThat(isInsertBody("INSERT INTO t(name) VALUES ('x') RETURNING id")).isTrue()
    }

    @Test
    fun `recognizes an INSERT behind its own nested WITH clause`() {
      val body = "WITH helper AS (SELECT 1 AS one) INSERT INTO t(name) SELECT 'x' FROM helper RETURNING id"
      assertThat(isInsertBody(body)).isTrue()
    }

    @Test
    fun `does not recognize UPDATE as INSERT`() {
      assertThat(isInsertBody("UPDATE t SET name = 'x' RETURNING id")).isFalse()
    }

    @Test
    fun `does not recognize DELETE as INSERT`() {
      assertThat(isInsertBody("DELETE FROM t WHERE id = 1 RETURNING id")).isFalse()
    }

    @Test
    fun `does not recognize MERGE as INSERT`() {
      assertThat(isInsertBody("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE RETURNING t.id")).isFalse()
    }
  }

  @Nested
  inner class ReferencesOldOrNewTest {

    @Test
    fun `detects a RETURNING OLD reference`() {
      assertThat(referencesOldOrNew("DELETE FROM t WHERE id = 1 RETURNING OLD.name")).isTrue()
    }

    @Test
    fun `detects a RETURNING NEW reference`() {
      assertThat(referencesOldOrNew("DELETE FROM t WHERE id = 1 RETURNING OLD.name, NEW.name")).isTrue()
    }

    @Test
    fun `does not match OLD or NEW inside a string literal`() {
      assertThat(referencesOldOrNew("UPDATE t SET name = 'the OLD.value and NEW.value' WHERE id = 1")).isFalse()
    }

    @Test
    fun `does not match OLD or NEW inside a comment`() {
      assertThat(referencesOldOrNew("UPDATE t SET name = 'x' -- OLD.name, NEW.name\nWHERE id = 1")).isFalse()
    }

    @Test
    fun `does not match a bare OLD or NEW with no qualifying dot`() {
      // "OLD"/"NEW" only mean the pseudo-relations when used as a qualifier (OLD.col); a column
      // or table merely NAMED "old" or "new" elsewhere must not trigger over-nullification.
      assertThat(referencesOldOrNew("SELECT old, new FROM t")).isFalse()
    }

    @Test
    fun `detects OLD nested inside a subquery`() {
      assertThat(referencesOldOrNew("UPDATE t SET x = (SELECT OLD.name) WHERE id = 1")).isTrue()
    }

    @Test
    fun `detects NEW with whitespace before the dot`() {
      // Verified against real PostgreSQL 18: `RETURNING NEW . name` is valid syntax and NEW is
      // NULL for a DELETE (no resulting row) exactly as with the immediately-adjacent form.
      assertThat(referencesOldOrNew("DELETE FROM t WHERE id = 1 RETURNING NEW . name")).isTrue()
    }

    @Test
    fun `detects NEW with a newline before the dot`() {
      assertThat(referencesOldOrNew("DELETE FROM t WHERE id = 1 RETURNING NEW\n.name")).isTrue()
    }

    @Test
    fun `detects NEW with a block comment before the dot`() {
      assertThat(referencesOldOrNew("DELETE FROM t WHERE id = 1 RETURNING NEW/*c*/.name")).isTrue()
    }

    @Test
    fun `detects a quoted lowercase new identifier`() {
      // Verified against real PostgreSQL 18: `"new".name` is valid syntax (the pseudo-relation's
      // underlying name is lowercase), and is NULL for a DELETE, same as the unquoted form.
      assertThat(referencesOldOrNew("DELETE FROM t WHERE id = 1 RETURNING \"new\".name")).isTrue()
    }

    @Test
    fun `does not match an uppercase-quoted NEW identifier`() {
      // Verified against real PostgreSQL 18: `"NEW".name` is INVALID syntax ("missing FROM-clause
      // entry for table \"NEW\"") — the quoted uppercase form does not refer to the pseudo-relation
      // at all, so matching it would be wrong, not merely imprecise.
      assertThat(referencesOldOrNew("DELETE FROM t WHERE id = 1 RETURNING \"NEW\".name")).isFalse()
    }

    @Test
    fun `detects a declared alias for NEW via the RETURNING WITH prologue`() {
      // Verified against real PostgreSQL 18: `RETURNING WITH (NEW AS n) n.name` is valid syntax,
      // and "n" refers to the NEW pseudo-relation exactly as if written unqualified.
      assertThat(
        referencesOldOrNew("n.name", additionalNames = setOf("n")),
      ).isTrue()
    }
  }

  @Nested
  inner class OldOrNewReturningColumnsTest {

    @Test
    fun `forces only the column whose own item references OLD, leaving an unrelated column alone`() {
      // Verified against real PostgreSQL 18: for `DELETE FROM t WHERE id = 1 RETURNING OLD.name,
      // t.id`, "name" is NOT NULL (OLD always exists for a DELETE's returned row) and "id" is NOT
      // NULL (untouched by OLD/NEW entirely) — but this asserts on the STRUCTURAL claim (which
      // column the OLD reference maps to), not the runtime nullability that follows from it.
      val result = oldOrNewReturningColumns("DELETE FROM t WHERE id = 1 RETURNING OLD.name, t.id")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = setOf(1)))
    }

    @Test
    fun `forces only the column whose own item references NEW`() {
      val result = oldOrNewReturningColumns("UPDATE t SET name = 'x' WHERE id = 1 RETURNING t.id, NEW.name")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = setOf(2)))
    }

    @Test
    fun `returns no forced columns when RETURNING has no OLD or NEW reference`() {
      val result = oldOrNewReturningColumns("UPDATE t SET name = 'x' WHERE id = 1 RETURNING id, name")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = emptySet()))
    }

    @Test
    fun `returns null forced columns when a recognized star item accompanies an OLD or NEW reference`() {
      // A star's expansion width is unknown without asking PostgreSQL, so the 1:1 item-to-column
      // mapping breaks down; a null forcedColumns signals the caller to fall back to forcing
      // every column — the mapping is KNOWN unreliable here, not merely unconfirmed, so this
      // doesn't need (though the caller applies it anyway, redundantly, for cases this text-only
      // recognition misses) the real-column-count cross-check to reach that conclusion.
      val result = oldOrNewReturningColumns("DELETE FROM t WHERE id = 1 RETURNING OLD.name, t.*")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = null))
    }

    @Test
    fun `recognizes a parenthesized star item accompanying an OLD or NEW reference`() {
      // Verified against real PostgreSQL 18: `RETURNING (tgt.*), OLD.tval AS oldv` is valid
      // syntax; "(tgt.*)" expands to every column of tgt, same as a bare "tgt.*".
      val result =
        oldOrNewReturningColumns("UPDATE tgt SET tval = 'x' WHERE id = 1 RETURNING (tgt.*), OLD.tval AS oldv")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = null))
    }

    @Test
    fun `recognizes a star item with a trailing block comment`() {
      val result =
        oldOrNewReturningColumns("UPDATE tgt SET tval = 'x' WHERE id = 1 RETURNING tgt.*/*c*/, OLD.tval AS oldv")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = null))
    }

    @Test
    fun `recognizes a star item with a trailing line comment`() {
      val result = oldOrNewReturningColumns(
        "UPDATE tgt SET tval = 'x' WHERE id = 1 RETURNING tgt.* -- comment\n, OLD.tval AS oldv",
      )
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = null))
    }

    @Test
    fun `recognizes a star item with whitespace around the qualifying dot`() {
      // Verified against real PostgreSQL: "tgt . *" is valid syntax, identical to "tgt.*".
      val result =
        oldOrNewReturningColumns("UPDATE tgt SET tval = 'x' WHERE id = 1 RETURNING tgt . *, OLD.tval AS oldv")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = null))
    }

    @Test
    fun `recognizes a star item with a trailing line comment outside a wrapping parenthesis`() {
      // Verified against real PostgreSQL 18.4: "(tgt.*) -- c" is valid syntax, identical to
      // "tgt.*". An earlier version of isStarItem stripped wrapping parentheses BEFORE comments,
      // so a comment OUTSIDE the parentheses was never removed and this spelling went
      // unrecognized.
      val result = oldOrNewReturningColumns(
        "UPDATE tgt SET tval = 'x' WHERE id = 1 RETURNING (tgt.*) -- c\n, OLD.tval AS oldv",
      )
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = null))
    }

    @Test
    fun `recognizes a star item with a trailing block comment outside a wrapping parenthesis`() {
      // Verified against real PostgreSQL 18.4: "(tgt.*) /*c*/" is valid syntax, same defect as
      // the line-comment case above.
      val result = oldOrNewReturningColumns(
        "UPDATE tgt SET tval = 'x' WHERE id = 1 RETURNING (tgt.*) /*c*/, OLD.tval AS oldv",
      )
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = null))
    }

    @Test
    fun `recognizes a star item with a comment between the dot and the star`() {
      // Verified against real PostgreSQL 18.4: "tgt./*c*/ *" is valid syntax. An earlier version
      // of isStarItem only collapsed WHITESPACE around the dot, not a comment sitting between the
      // dot and the star.
      val result = oldOrNewReturningColumns(
        "UPDATE tgt SET tval = 'x' WHERE id = 1 RETURNING tgt./*c*/ *, OLD.tval AS oldv",
      )
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = null))
    }

    @Test
    fun `does not recognize count-star as a star item even with internal whitespace stripped`() {
      val result = oldOrNewReturningColumns("UPDATE t SET name = 'x' WHERE id = 1 RETURNING count( * ), OLD.name")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = setOf(2)))
    }

    @Test
    fun `does not recognize a cast on a star-shaped expression as a star item`() {
      val result = oldOrNewReturningColumns("UPDATE t SET name = 'x' WHERE id = 1 RETURNING t.*::text, OLD.name")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = setOf(2)))
    }

    @Test
    fun `returns no forced columns for a star item with no OLD or NEW reference`() {
      val result = oldOrNewReturningColumns("UPDATE t SET name = 'x' WHERE id = 1 RETURNING *")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 1, forcedColumns = emptySet()))
    }

    @Test
    fun `honors a RETURNING WITH alias prologue mapping the alias to its declaring column`() {
      // Verified against real PostgreSQL 18: `RETURNING WITH (OLD AS o, NEW AS n) o.name AS
      // oldname, n.name AS newname` is valid syntax; "oldname" and "newname" are the two RETURNING
      // items, in that order.
      val result = oldOrNewReturningColumns(
        "UPDATE t SET name = 'x' WHERE id = 1 RETURNING WITH (OLD AS o, NEW AS n) " +
          "o.name AS oldname, n.name AS newname",
      )
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 2, forcedColumns = setOf(1, 2)))
    }

    @Test
    fun `returns no items and no forced columns when there is no RETURNING clause at all`() {
      val result = oldOrNewReturningColumns("DELETE FROM t WHERE id = 1")
      assertThat(result).isEqualTo(OldOrNewReturningAnalysis(itemCount = 0, forcedColumns = emptySet()))
    }
  }

  @Nested
  inner class ReferencesAnyNameTest {

    @Test
    fun `detects a reference to one of the given names`() {
      assertThat(referencesAnyName("MERGE INTO tgt USING pre ON pre.id = tgt.id", listOf("pre"))).isTrue()
    }

    @Test
    fun `does not match a name that only appears as a substring of a longer identifier`() {
      assertThat(referencesAnyName("SELECT * FROM presource", listOf("pre"))).isFalse()
    }

    @Test
    fun `does not match when none of the given names appear`() {
      assertThat(referencesAnyName("MERGE INTO tgt USING src ON src.id = tgt.id", listOf("pre"))).isFalse()
    }

    @Test
    fun `detects a name nested inside a subquery`() {
      assertThat(referencesAnyName("SELECT * FROM (SELECT * FROM pre) x", listOf("pre"))).isTrue()
    }

    @Test
    fun `detects a double-quoted reference with no requirement for a following dot`() {
      assertThat(
        referencesAnyName("MERGE INTO tgt USING \"pre\" ON \"pre\".id = tgt.id", listOf("pre")),
      ).isTrue()
    }

    @Test
    fun `does not match a double-quoted name that is not in the given names`() {
      assertThat(referencesAnyName("MERGE INTO tgt USING \"other\" ON \"other\".id = tgt.id", listOf("pre")))
        .isFalse()
    }

    @Test
    fun `does not match a name that only appears inside a single-quoted string literal`() {
      assertThat(referencesAnyName("SELECT 'pre' AS label", listOf("pre"))).isFalse()
    }

    @Test
    fun `does not match a name that only appears inside a comment`() {
      assertThat(referencesAnyName("SELECT 1 /* references pre here */", listOf("pre"))).isFalse()
    }

    @Test
    fun `does not match a name that only appears inside a dollar-quoted string`() {
      assertThat(referencesAnyName("SELECT \$\$pre\$\$ AS x", listOf("pre"))).isFalse()
    }

    @Test
    fun `does not match a name that only appears inside a tagged dollar-quoted string`() {
      assertThat(referencesAnyName("SELECT \$tag\$ pre \$tag\$ AS x", listOf("pre"))).isFalse()
    }
  }

  @Nested
  inner class HasOuterJoinTest {

    @Test
    fun `detects a top-level LEFT JOIN`() {
      assertThat(hasOuterJoin("UPDATE t SET x = 1 FROM a LEFT JOIN b ON b.id = a.id")).isTrue()
    }

    @Test
    fun `detects a LEFT JOIN nested inside a USING subquery`() {
      // The shape that defeated depth-0-only scanning: the join is inside the parenthesized
      // subquery, not at the top level of the MERGE statement.
      val body = "MERGE INTO tgt USING (SELECT src.sid, sx.xval FROM src LEFT JOIN sx ON sx.sid = src.sid) s " +
        "ON s.sid = tgt.id WHEN MATCHED THEN UPDATE SET tval = 'z' RETURNING tgt.id, s.xval"
      assertThat(hasOuterJoin(body)).isTrue()
    }

    @Test
    fun `does not misfire on the LEFT string function`() {
      assertThat(hasOuterJoin("UPDATE t SET name = LEFT(name, 5) WHERE id = 1")).isFalse()
    }

    @Test
    fun `does not match when there is no join at all`() {
      assertThat(hasOuterJoin("UPDATE t SET name = 'x' WHERE id = 1")).isFalse()
    }
  }

  @Nested
  inner class HasGroupingSetConstructTest {

    @Test
    fun `detects ROLLUP with an adjacent open parenthesis`() {
      assertThat(hasGroupingSetConstruct("SELECT id, count(*) FROM a GROUP BY ROLLUP(id)")).isTrue()
    }

    @Test
    fun `detects CUBE with a space before the open parenthesis`() {
      assertThat(hasGroupingSetConstruct("SELECT a, b, count(*) FROM t GROUP BY CUBE (a, b)")).isTrue()
    }

    @Test
    fun `detects GROUPING SETS`() {
      assertThat(hasGroupingSetConstruct("SELECT a, b FROM t GROUP BY GROUPING SETS ((a), (b), ())")).isTrue()
    }

    @Test
    fun `detects GROUPING SETS with a comment between the two words`() {
      assertThat(hasGroupingSetConstruct("SELECT a FROM t GROUP BY GROUPING/*c*/SETS((a), ())")).isTrue()
    }

    @Test
    fun `detects ROLLUP nested inside a USING subquery`() {
      val body = "MERGE INTO tgt USING (SELECT id, count(*) AS c FROM a GROUP BY ROLLUP(id)) s " +
        "ON tgt.id = s.id WHEN MATCHED THEN UPDATE SET tval = 'z' RETURNING s.id"
      assertThat(hasGroupingSetConstruct(body)).isTrue()
    }

    @Test
    fun `does not match a plain GROUP BY with no grouping-set construct`() {
      assertThat(hasGroupingSetConstruct("SELECT id, count(*) FROM a GROUP BY id")).isFalse()
    }

    @Test
    fun `does not match an identifier named rollup with no following open parenthesis`() {
      assertThat(hasGroupingSetConstruct("SELECT rollup FROM t")).isFalse()
    }

    @Test
    fun `does not match an ordinary cube function call with no GROUP BY anywhere`() {
      // "detects CUBE with a space before the open parenthesis" above already covers the
      // still-true case (a genuine GROUP BY CUBE(...)) that this negative case is paired with.
      assertThat(hasGroupingSetConstruct("SELECT cube(av) FROM t")).isFalse()
    }

    @Test
    fun `does not match a bare GROUPING aggregate function unrelated to GROUPING SETS`() {
      assertThat(hasGroupingSetConstruct("SELECT GROUPING(a) FROM t GROUP BY a")).isFalse()
    }
  }

  @Nested
  inner class HasNullExtendingConstructTest {

    @Test
    fun `trips on an outer join`() {
      assertThat(hasNullExtendingConstruct("UPDATE t SET x = 1 FROM a LEFT JOIN b ON b.id = a.id")).isTrue()
    }

    @Test
    fun `trips on WHEN NOT MATCHED BY SOURCE`() {
      assertThat(hasNullExtendingConstruct("WHEN NOT MATCHED BY SOURCE THEN DELETE")).isTrue()
    }

    @Test
    fun `trips on a grouping-set construct`() {
      assertThat(hasNullExtendingConstruct("SELECT id, count(*) FROM a GROUP BY ROLLUP(id)")).isTrue()
    }

    @Test
    fun `does not trip on a plain body with none of the three constructs`() {
      assertThat(hasNullExtendingConstruct("UPDATE t SET name = 'x' WHERE id = 1")).isFalse()
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
  }

  private companion object {
    // E'text \' FROM here' — the \' is an escaped quote (part of the string content, not a
    // terminator), so "FROM" is inside the string the whole way through to the real closing '.
    const val E_STRING_WITH_ESCAPED_QUOTE_CONTAINING_FROM = "UPDATE t SET name = E'text \\' FROM here' WHERE id = 1"
  }
}
