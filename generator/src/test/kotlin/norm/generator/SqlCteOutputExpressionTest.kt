package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlCteOutputExpressionTest {

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
}
