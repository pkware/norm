package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlCteClauseTest {

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
      // corrupting everything parsed after — including a second CTE that follows. With lexical
      // awareness, the literal's ')' is skipped as part of the string token, so the CTE body's
      // real closing paren (the one right before the comma) is what's found.
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
      // Before the dollar-quote identifier fix, the "$" between "b" and "c" in "a$b$c" was
      // misread as opening a "$b$"-tagged dollar-quote, swallowing the CTE body's own closing ")"
      // (and everything after) as unterminated string content — parseCteClause would have found
      // only one (corrupted) definition, or none at all. "a$b$c" is an ordinary identifier, not a
      // dollar-quoted string.
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
      // In PostgreSQL 18.4, "WITH data€x AS (SELECT 1 AS inner_name) SELECT inner_name AS
      // outer_name FROM data€x" is accepted -- "data€x" is an ordinary unquoted identifier
      // (PostgreSQL's lexer admits any byte >= 0x80 inside one). Before the fix, the
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
    fun `a quoted name with an escaped embedded double quote keeps the WHOLE token in rawName`() {
      // The quoted-name scan previously stopped at the first '"', truncating rawName to
      // `"He"` for a CTE actually named `He"llo` (SQL source `"He""llo"`) -- the escaped `""` in the
      // middle was misread as the closing quote. `WITH "He""llo" AS (SELECT 1) SELECT 1 FROM
      // "He""llo"` is valid PostgreSQL, and the CTE's real name is `He"llo` (one literal embedded
      // quote).
      val result = parseCteClause("""WITH "He""llo" AS (SELECT 1) SELECT 1 FROM "He""llo"""")
      assertThat(result!!.definitions).hasSize(1)
      assertThat(result.definitions[0].rawName).isEqualTo("\"He\"\"llo\"")
      assertThat(result.definitions[0].name).isEqualTo("He\"llo")
    }

    @Test
    fun `a dollar-led CTE name is not recognized, since PostgreSQL itself rejects one`() {
      // parseSingleCteDefinition's unquoted-name run originally used isIdentifierChar -- the
      // continuation predicate -- for the name's first character too, so a leading "$" was
      // wrongly accepted as starting a CTE name. In PostgreSQL 18.4, "WITH $x AS (SELECT 1)
      // SELECT a FROM x" is a syntax error ("at or near $") -- "$" may only continue an
      // identifier, never start one. With the first character
      // correctly gated by isIdentifierStartChar, no CTE name is found here at all, so this
      // returns null exactly as it does on main.
      val result = parseCteClause("WITH \$x AS (SELECT 1) SELECT a FROM x")
      assertThat(result).isNull()
    }

    @Test
    fun `an over-length unquoted CTE name is truncated to 63 bytes, while rawName keeps the full untruncated text`() {
      val overLongName = "c".repeat(70)
      val sql = "WITH $overLongName AS (SELECT 1) SELECT * FROM $overLongName"
      val result = parseCteClause(sql)
      assertThat(result!!.definitions[0].name).isEqualTo("c".repeat(63))
      assertThat(result.definitions[0].rawName).isEqualTo(overLongName)
    }

    @Test
    fun `an over-length quoted CTE name is truncated to 63 bytes, while rawName keeps the quotes and full text`() {
      val overLongName = "c".repeat(70)
      val sql = "WITH \"$overLongName\" AS (SELECT 1) SELECT * FROM \"$overLongName\""
      val result = parseCteClause(sql)
      assertThat(result!!.definitions[0].name).isEqualTo("c".repeat(63))
      assertThat(result.definitions[0].rawName).isEqualTo("\"$overLongName\"")
    }
  }
}
