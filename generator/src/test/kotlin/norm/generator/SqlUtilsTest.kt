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
