package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlCteClauseTest {

  @Nested
  inner class ParseCteClauseTest {

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
    fun `mixed-case unquoted name is folded in name but preserved as-is in rawName`() {
      // Unquoted identifiers are case-INsensitive to PostgreSQL (folded to lowercase): name is
      // PostgreSQL's logical, folded identifier value, while rawName keeps exactly what the user
      // wrote for splicing back into SQL.
      val result = parseCteClause("WITH MyCte AS (SELECT 1) SELECT * FROM MyCte")
      assertThat(result!!.definitions[0].name).isEqualTo("mycte")
      assertThat(result.definitions[0].rawName).isEqualTo("MyCte")
    }

    @Test
    fun `CTE body containing a closing parenthesis inside a string literal parses correctly`() {
      // findMatchingCloseParenthesis skips the ')' inside 'closing )' as part of the string token.
      // The CTE body's real closing paren (the one right before the comma) is the one found, so
      // the second CTE that follows is parsed intact.
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
      // "a$b$c" is an ordinary identifier, not the opening of a "$b$"-tagged dollar-quote, so the
      // CTE body's own closing ")" is found and parseCteClause finds every definition.
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
      // (PostgreSQL's lexer admits any byte >= 0x80 inside one). The CTE-name run accepts "€" as
      // an identifier character, so parsing finds one definition and does not return `null`.
      val result = parseCteClause(
        "WITH data€x AS (SELECT 1 AS inner_name) SELECT inner_name AS outer_name FROM data€x",
      )
      assertThat(result).isNotNull()
      assertThat(result!!.definitions).hasSize(1)
      assertThat(result.definitions[0].name).isEqualTo("data€x")
    }

    @Test
    fun `a quoted name with an escaped embedded double quote keeps the WHOLE token in rawName`() {
      // The quoted-name scan treats the escaped `""` in the middle of `"He""llo"` as part of the
      // name, so rawName keeps the whole token. `WITH "He""llo" AS (SELECT 1) SELECT 1 FROM
      // "He""llo"` is valid PostgreSQL, and the CTE's real name is `He"llo` (one literal embedded
      // quote).
      val result = parseCteClause("""WITH "He""llo" AS (SELECT 1) SELECT 1 FROM "He""llo"""")
      assertThat(result!!.definitions).hasSize(1)
      assertThat(result.definitions[0].rawName).isEqualTo("\"He\"\"llo\"")
      assertThat(result.definitions[0].name).isEqualTo("He\"llo")
    }

    @Test
    fun `a dollar-led CTE name is not recognized, since PostgreSQL itself rejects one`() {
      // parseSingleCteDefinition gates the name's first character with isIdentifierStartChar,
      // because the continuation predicate isIdentifierChar accepts a leading "$". In PostgreSQL
      // 18.4, "WITH $x AS (SELECT 1) SELECT a FROM x" is a syntax error ("at or near $") -- "$"
      // may only continue an identifier, never start one. No CTE name is found here at all, so
      // this returns `null`.
      val result = parseCteClause("WITH \$x AS (SELECT 1) SELECT a FROM x")
      assertThat(result).isNull()
    }

    @Test
    fun `a digit-led CTE name is not recognized, since PostgreSQL itself rejects one`() {
      // Same reasoning as the dollar-led case above: parseSingleCteDefinition's unquoted-name run
      // gates its first character on isIdentifierStartChar, which does not admit a digit. In
      // PostgreSQL 18.4, "WITH 1x AS (SELECT 1) SELECT a FROM x" is a syntax error.
      val result = parseCteClause("WITH 1x AS (SELECT 1) SELECT a FROM x")
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
