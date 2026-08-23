package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlLexerTest {

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
}
