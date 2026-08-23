package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlKeywordScannerTest {

  @Nested
  inner class SplitAtTopLevelTest {

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

  private companion object {
    // E'text \' FROM here' — the \' is an escaped quote (part of the string content, not a
    // terminator), so "FROM" is inside the string the whole way through to the real closing '.
    const val E_STRING_WITH_ESCAPED_QUOTE_CONTAINING_FROM = "UPDATE t SET name = E'text \\' FROM here' WHERE id = 1"
  }
}
