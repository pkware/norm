package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import norm.generator.libpgquery.LEXER_WHITESPACE
import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.PgRegress
import norm.generator.libpgquery.RecordingMachine
import norm.generator.libpgquery.ScanToken
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.SAME_THREAD)
class ParsedSqlPartTest {

  @Nested
  inner class RegressCorpus {

    @Test
    fun `tokens cut from the parent equal a fresh scan wherever the cut is on a token boundary before whitespace`() {
      val failures = mutableListOf<String>()
      var cutCount = 0
      var scannedCount = 0
      for (sql in sweptStatements()) {
        val parent = ParsedSql(sql)
        for (token in parent.tokens) {
          for ((start, end) in listOf(token.start to sql.length, 0 to token.end)) {
            scannedCount++
            val fresh = scanLeadingTokens(sql.substring(start, end))
            if (parent.part(start, end).tokens != fresh) failures += "part($start, $end) of: $sql"
            if (isCuttable(sql, parent.tokens, start, end)) {
              cutCount++
              if (cut(parent.tokens, start, end) != fresh) failures += "cut($start, $end) of: $sql"
            }
          }
        }
      }

      assertThat(failures).isEmpty()
      assertThat(scannedCount).isGreaterThan(0)
      assertThat(cutCount).isGreaterThan(0)
    }

    private fun sweptStatements(): List<String> = PgRegress.files("17")
      .filter { it.name in setOf("with.sql", "select.sql", "strings.sql") }
      .flatMap { it.statements }
      .filterNot { it.isTokenizerRejected }
      .map { it.text }
      .filter { LibPgQuery.scan(it).valueOrNull() != null }

    private fun isCuttable(sql: String, tokens: List<ScanToken>, start: Int, end: Int): Boolean =
      tokens.none { (it.start < start && start < it.end) || (it.start < end && end < it.end) } &&
        (end == sql.length || sql[end] in ";$LEXER_WHITESPACE")

    private fun cut(tokens: List<ScanToken>, start: Int, end: Int): List<ScanToken> = tokens.filter {
      it.start >= start && it.end <= end
    }.map { it.copy(start = it.start - start, end = it.end - start) }
  }

  @Nested
  inner class Part {

    @Test
    fun `a part that ends after a named parameter and before whitespace equals a fresh scan`() {
      val part = ParsedSql("SELECT :id ").part(0, 10)

      assertThat(part.tokens).isEqualTo(scanLeadingTokens("SELECT :id"))
    }

    @Test
    fun `a part that ends inside a national string prefix equals a fresh scan`() {
      val part = ParsedSql("SELECT N'a'").part(0, 8)

      assertThat(part.tokens).isEqualTo(scanLeadingTokens("SELECT N"))
    }

    @Test
    fun `a part of a parent that the lexer rejects equals a fresh scan`() {
      val parent = ParsedSql("SELECT 1; SELECT 'abc")

      assertThat(parent.part(0, 8).tokens).isEqualTo(scanLeadingTokens("SELECT 1"))
      assertThat(parent.part(9, 21).tokens).isEqualTo(scanLeadingTokens(" SELECT 'abc"))
    }

    @Test
    fun `a part that starts after non-ASCII characters equals a fresh scan and scans nothing`() {
      val sql = "SELECT 'é🚀'; SELECT 2"
      val parent = ParsedSql(sql)
      val start = sql.indexOf(';') + 1
      lateinit var part: ParsedSql

      val calls = RecordingMachine.record { part = parent.part(start, sql.length) }

      assertThat(part.tokens).isEqualTo(scanLeadingTokens(sql.substring(start)))
      assertThat(calls.scanTexts).isEmpty()
    }
  }

  @Nested
  inner class WhereTokensAreCut {

    @Test
    fun `a part that ends before a semicolon or whitespace takes tokens from the parent without a scan`() {
      val parent = ParsedSql("SELECT a; SELECT b")
      lateinit var beforeSemicolon: ParsedSql
      lateinit var beforeWhitespace: ParsedSql

      val calls = RecordingMachine.record {
        beforeSemicolon = parent.part(0, 8)
        beforeWhitespace = parent.part(9, 16)
      }

      assertThat(calls.scanTexts).isEmpty()
      assertThat(beforeSemicolon.tokens).isEqualTo(scanLeadingTokens("SELECT a"))
      assertThat(beforeWhitespace.tokens).isEqualTo(scanLeadingTokens(" SELECT "))
    }

    @Test
    fun `a part that ends before another character is scanned`() {
      val parent = ParsedSql("SELECT a.b")
      lateinit var part: ParsedSql

      val calls = RecordingMachine.record { part = parent.part(0, 8) }

      assertThat(calls.scanTexts).isEqualTo(listOf("SELECT a"))
      assertThat(part.tokens).isEqualTo(scanLeadingTokens("SELECT a"))
    }

    @Test
    fun `a part of a parent that the lexer rejects is scanned`() {
      val parent = ParsedSql("SELECT 1; SELECT 'abc")
      lateinit var part: ParsedSql

      val calls = RecordingMachine.record { part = parent.part(0, 8) }

      assertThat(calls.scanTexts).isEqualTo(listOf("SELECT 1"))
      assertThat(part.tokens).isEqualTo(scanLeadingTokens("SELECT 1"))
    }
  }

  @Nested
  inner class QueryFile {

    @Test
    fun `a non-breaking space before the terminator is not part of the named parameter`() {
      val query = QueryFileParser.parse("-- name: a :one\nSELECT :id ;\n").single()

      assertThat(query.sql).isEqualTo("SELECT ?")
      assertThat(query.namedParameters).isEqualTo(mapOf(1 to "id"))
    }

    @Test
    fun `a file of two ASCII queries is scanned once`() {
      val text = "-- name: a :one\nSELECT :id;\n-- name: b :many\nSELECT 2 AS two;\n"
      lateinit var queries: List<ParsedQuery>

      val calls = RecordingMachine.record { queries = QueryFileParser.parse(text) }

      assertThat(queries.map { it.sql }).isEqualTo(listOf("SELECT ?", "SELECT 2 AS two"))
      assertThat(calls.scanTexts).isEqualTo(listOf(text))
    }
  }
}
