package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * One [findTopLevelKeyword] input: [sql] and the [keyword] to search for. [expected] is a function
 * of the (already-evaluated) [sql] string rather than a plain `Int` so a case can assert either a
 * fixed `-1` or a position computed from [sql] itself (e.g. `sql.indexOf("FROM a")`) without
 * duplicating the substring being searched for.
 */
internal data class KeywordScanCase(
  val description: String,
  val sql: String,
  val keyword: String,
  val expected: (String) -> Int,
) {
  override fun toString() = description
}

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
      // Before the dollar-quote fix, the "$" between "b" and "c" was misread as opening a
      // "$b$"-tagged dollar-quote, swallowing everything after it (including the real commas) as
      // unterminated string content — yielding one item instead of three. "a$b$c" is an ordinary
      // PostgreSQL identifier.
      val result = splitAtTopLevel("a\$b\$c, id, name", ',')
      assertThat(result).containsExactly("a\$b\$c", " id", " name")
    }

    @Test
    fun `preserves content inside square brackets`() {
      // Square brackets were not tracked as a nesting level, so ARRAY[1, 2]'s internal comma
      // split the item in two — corrupting oldOrNewReturningColumns's item count, and even
      // capable of numerically canceling out an unrelated star-caused split error, defeating its
      // real-column-count cross-check entirely.
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
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  internal inner class FindTopLevelKeywordTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("keywordScanCases")
    fun `scans for a keyword the same way regardless of what surrounds it`(testCase: KeywordScanCase) {
      assertThat(findTopLevelKeyword(testCase.sql, testCase.keyword)).isEqualTo(testCase.expected(testCase.sql))
    }

    fun keywordScanCases(): List<KeywordScanCase> = listOf(
      KeywordScanCase(
        description = "does not match a keyword inside a single-quoted string literal",
        sql = "UPDATE t SET name = 'copied from source' WHERE id = 1",
        keyword = "FROM",
        expected = { -1 },
      ),
      // "\\'" inside an E-string is an escaped quote, not the string's terminator. A scanner
      // that (wrongly) treats it as the terminator would see the string as ending right there,
      // exposing " FROM here'" as ordinary top-level text — including a false-positive "FROM".
      KeywordScanCase(
        description = "does not match a keyword inside an E-string with a backslash-escaped quote",
        sql = E_STRING_WITH_ESCAPED_QUOTE_CONTAINING_FROM,
        keyword = "FROM",
        expected = { -1 },
      ),
      KeywordScanCase(
        description = "does not match a keyword inside a dollar-quoted string",
        sql = "UPDATE t SET name = \$\$copied from source\$\$ WHERE id = 1",
        keyword = "FROM",
        expected = { -1 },
      ),
      KeywordScanCase(
        description = "does not match a keyword inside a tagged dollar-quoted string",
        sql = "UPDATE t SET name = \$tag\$copied from source\$tag\$ WHERE id = 1",
        keyword = "FROM",
        expected = { -1 },
      ),
      KeywordScanCase(
        description = "does not match a keyword inside a double-quoted identifier",
        sql = """UPDATE t SET "from column" = 1 WHERE id = 2""",
        keyword = "FROM",
        expected = { -1 },
      ),
      KeywordScanCase(
        description = "does not match a keyword inside a line comment",
        sql = "UPDATE t SET name = 'x' -- copied FROM elsewhere\nWHERE id = 1",
        keyword = "FROM",
        expected = { -1 },
      ),
      KeywordScanCase(
        description = "does not match a keyword inside a block comment",
        sql = "UPDATE t /* set FROM the caller */ SET name = 'x'",
        keyword = "FROM",
        expected = { -1 },
      ),
      KeywordScanCase(
        description = "still finds a real top-level keyword after a string literal containing the keyword text",
        sql = "UPDATE t SET name = 'copied from source' FROM a WHERE id = 1",
        keyword = "FROM",
        expected = { it.indexOf("FROM a") },
      ),
      // The literal keyword text 'when not matched by source ' must not be mistaken for the
      // real clause — this is exactly the input hasWhenNotMatchedBySourceClause scans, via
      // repeated findTopLevelKeyword("WHEN", ...) calls.
      KeywordScanCase(
        description = "does not match WHEN NOT MATCHED BY SOURCE inside a string literal",
        sql = "SET tname = 'when not matched by source '",
        keyword = "WHEN",
        expected = { -1 },
      ),
      // Before the word-boundary fix, "_" did not count as an identifier character, so the
      // character after "FROM" in "valid_from" ("_" is not letterOrDigit) was wrongly treated as
      // a valid word boundary, matching "FROM" inside the column name itself.
      KeywordScanCase(
        description = "does not match FROM inside the identifier valid_from",
        sql = "UPDATE t SET valid_from = 'x' FROM a",
        keyword = "FROM",
        expected = { it.indexOf("FROM a") },
      ),
      KeywordScanCase(
        description = "does not match FROM inside the identifier from_date",
        sql = "UPDATE t SET from_date = 'x' FROM a",
        keyword = "FROM",
        expected = { it.indexOf("FROM a") },
      ),
      // "data_set" as a table name ends in "_set" — the character before "set" is "_", which
      // must count as an identifier character so the real "SET" keyword afterward is what's found.
      KeywordScanCase(
        description = "does not match SET inside the identifier data_set",
        sql = "UPDATE data_set SET x = 1",
        keyword = "SET",
        expected = { it.indexOf("SET x") },
      ),
      KeywordScanCase(
        description = "does not match RETURNING inside the identifier returning_note",
        sql = "UPDATE t SET returning_note = 'x' RETURNING id",
        keyword = "RETURNING",
        expected = { it.indexOf("RETURNING id") },
      ),
      KeywordScanCase(
        description = "does not match USING inside the identifier using_note",
        sql = "DELETE FROM using_note USING a",
        keyword = "USING",
        expected = { it.indexOf("USING a") },
      ),
      KeywordScanCase(
        description = "does not match SET as a standalone keyword when it is only the prefix of set_at",
        sql = "SELECT set_at FROM t",
        keyword = "SET",
        expected = { -1 },
      ),
      // PostgreSQL allows "$" inside (not at the start of) an unquoted identifier — it must
      // count as an identifier character for word-boundary purposes, the same as "_".
      KeywordScanCase(
        description = "does not match FROM inside an identifier containing a dollar sign",
        sql = "UPDATE t SET x\$from = 1 FROM a",
        keyword = "FROM",
        expected = { it.indexOf("FROM a") },
      ),
      // PostgreSQL's lexer admits any byte >= 0x80 inside an unquoted identifier, so "from€" is
      // a single identifier, not the keyword FROM followed by a stray "€".
      KeywordScanCase(
        description = "does not match FROM when immediately followed by a non-ASCII identifier-continuation character",
        sql = "SELECT * FROM€ t",
        keyword = "FROM",
        expected = { -1 },
      ),
      // Same fact, leading side: "€from" is a single identifier, not a stray "€" followed by the
      // keyword FROM.
      KeywordScanCase(
        description = "does not match FROM when immediately preceded by a non-ASCII identifier-continuation character",
        sql = "SELECT * €FROM t",
        keyword = "FROM",
        expected = { -1 },
      ),
    )

    @Test
    fun `bails to not-found once an unmatched closing parenthesis drives depth negative`() {
      // A bare ")" with no matching "(" before it means the input is not the well-formed,
      // balanced text this scan assumes. Without a floor, depth stays negative until a later
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
      // "returning€" is a legal PostgreSQL column name (PostgreSQL's lexer admits any byte
      // >= 0x80 inside an unquoted identifier), so this must not be split into the bare word
      // "returning" plus a stray "€".
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
      // Not new behavior — the AS-preceded-alias exclusion predates this branch's >= 0x80
      // boundary work. Kept as a regression guard that a real RETURNING clause with no AS before
      // it still matches.
      val sql = "DELETE FROM t WHERE id = 1 RETURNING id"
      val result = findTopLevelReturningKeyword(sql)
      assertThat(result).isEqualTo(sql.indexOf("RETURNING id"))
    }

    @Test
    fun `does not match an AS-preceded column alias`() {
      // Not new behavior — same as above, the AS-preceded exclusion predates this branch's
      // >= 0x80 boundary work. Kept as a regression guard against that exclusion becoming overly
      // broad or narrow.
      val sql = "SELECT email AS returning, x FROM t"
      val result = findTopLevelReturningKeyword(sql)
      assertThat(result).isEqualTo(-1)
    }
  }

  @Nested
  inner class FindTopLevelFromClauseKeywordTest {

    @Test
    fun `a FROM that is really part of IS DISTINCT FROM is not mistaken for the clause boundary`() {
      // A plain findTopLevelKeyword search returns the first depth-0 "FROM", which here is the
      // one glued to "IS DISTINCT" -- truncating the select list to "a IS DISTINCT" and leaving
      // "b FROM t" (the expression's own right-hand operand) looking like the real clause.
      val sql = "SELECT a IS DISTINCT FROM b FROM t"
      val result = findTopLevelFromClauseKeyword(sql, 0)
      assertThat(result).isEqualTo(sql.indexOf("FROM t"))
    }

    @Test
    fun `a FROM that is really part of IS NOT DISTINCT FROM is not mistaken for the clause boundary`() {
      val sql = "SELECT a IS NOT DISTINCT FROM b FROM t"
      val result = findTopLevelFromClauseKeyword(sql, 0)
      assertThat(result).isEqualTo(sql.indexOf("FROM t"))
    }

    @Test
    fun `a FROM inside EXTRACT's own parentheses is not mistaken for the clause boundary`() {
      // Ordinary depth tracking already handles this -- included as a regression guard that the
      // new DISTINCT-preceded exclusion did not accidentally widen matching in the other direction.
      val sql = "SELECT EXTRACT(DAY FROM ts) FROM tbl"
      val result = findTopLevelFromClauseKeyword(sql, 0)
      assertThat(result).isEqualTo(sql.indexOf("FROM tbl"))
    }

    @Test
    fun `still finds an ordinary FROM clause with no DISTINCT anywhere`() {
      val sql = "SELECT a, b FROM t"
      val result = findTopLevelFromClauseKeyword(sql, 0)
      assertThat(result).isEqualTo(sql.indexOf("FROM t"))
    }

    @Test
    fun `a comment between DISTINCT and FROM still suppresses the match`() {
      val sql = "SELECT a IS DISTINCT/*c*/FROM b FROM t"
      val result = findTopLevelFromClauseKeyword(sql, 0)
      assertThat(result).isEqualTo(sql.indexOf("FROM t"))
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
      // "OUTER€" is a single identifier, not the keyword OUTER followed by a stray "€" -- the
      // position must be left unchanged, exactly as for any other non-matching text.
      val sql = "OUTER€ JOIN b"
      assertThat(skipOptionalKeyword(sql, 0, "OUTER")).isEqualTo(0)
    }

    @Test
    fun `still consumes a real keyword followed by ordinary whitespace`() {
      // Not new behavior — a positive control confirming a real keyword followed by plain
      // whitespace still advances past it. Kept as a regression guard against the non-ASCII
      // boundary check above becoming overly broad and rejecting this too.
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
