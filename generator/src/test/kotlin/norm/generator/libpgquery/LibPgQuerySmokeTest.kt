package norm.generator.libpgquery

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.types.MemoryImport
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LibPgQuerySmokeTest {

  @Nested
  inner class Parse {

    @Test
    fun `parses SELECT 1 with PostgreSQL 18`() {
      val tree = LibPgQuery.parse("SELECT 1").accepted()
      assertThat(tree.intField("version")).isEqualTo(180_006)
      assertThat(tree.objectArrayField("stmts").size).isEqualTo(1)
    }

    @Test
    fun `reports a syntax error with PostgreSQL's message and keeps serving calls`() {
      assertThat(LibPgQuery.parse("SELEC 1"))
        .isEqualTo(LibPgQueryResult.Rejected("syntax error at or near \"SELEC\"", 0))
      val tree = LibPgQuery.parse("SELECT 2").accepted()
      assertThat(tree.intField("version")).isEqualTo(180_006)
    }

    @Test
    fun `a rejected text leaves the instance in use`() {
      val calls = RecordingMachine.record { LibPgQuery.parse("SELEC 1").rejected() }

      assertThat(calls.parseTexts).isEqualTo(listOf("SELEC 1"))
    }

    @Test
    fun `reports the cursor of a syntax error as a String index`() {
      val sql = "SELECT 1 FROM t WHERE )"
      assertThat(LibPgQuery.parse(sql).rejected().cursorIndex).isEqualTo(sql.indexOf(')'))
    }

    @Test
    fun `rejects text containing a NUL character`() {
      assertFailure { LibPgQuery.parse("SELECT 1\u0000; SELECT 2") }.isInstanceOf(IllegalArgumentException::class)
    }
  }

  @Nested
  inner class Scan {

    @Test
    fun `scans keywords and literals`() {
      val sql = "SELECT 1 FROM t"
      val texts = LibPgQuery.scan(sql).accepted().map { sql.substring(it.start, it.end) }
      assertThat(texts).isEqualTo(listOf("SELECT", "1", "FROM", "t"))
    }

    @Test
    fun `classifies keywords by kind`() {
      val sql = "SELECT abort between left foo"
      val kinds = LibPgQuery.scan(sql).accepted().associate { sql.substring(it.start, it.end) to it.keywordKind }
      assertThat(kinds).isEqualTo(
        mapOf(
          "SELECT" to KeywordKind.RESERVED,
          "abort" to KeywordKind.UNRESERVED,
          "between" to KeywordKind.COLUMN_NAME,
          "left" to KeywordKind.TYPE_FUNCTION_NAME,
          "foo" to KeywordKind.NONE,
        ),
      )
    }

    @Test
    fun `reports a lexer error and keeps serving calls`() {
      assertThat(LibPgQuery.scan("SELECT \"unterminated").rejected().message)
        .isEqualTo("unterminated quoted identifier at or near \"\"unterminated\"")
      assertThat(LibPgQuery.scan("SELECT 1").accepted().size).isEqualTo(2)
    }
  }

  @Nested
  inner class Exports {

    @Test
    fun `exports both split entry points and the split result release`() {
      val exportedFunctions = exports().filter { it.exportType() == ExternalType.FUNCTION }.map { it.name() }
      assertThat(exportedFunctions).containsAtLeast(
        "pg_query_split_with_scanner",
        "pg_query_split_with_parser",
        "pg_query_free_split_result",
      )
    }

    @Test
    fun `defines its own memory instead of importing one`() {
      val imports = module().importSection()
      val importedMemories = (0 until imports.importCount()).map {
        imports.getImport(it)
      }.filterIsInstance<MemoryImport>()
      assertThat(importedMemories).isEmpty()
      assertThat(
        exports().filter {
          it.exportType() == ExternalType.MEMORY
        }.map { it.name() },
      ).containsExactly("memory")
    }

    private fun module() = openResource("/norm/generator/libpgquery/LibPgQuery.meta").use { Parser.parse(it) }

    private fun exports() = module().exportSection().let { section ->
      (0 until section.exportCount()).map { section.getExport(it) }
    }
  }
}
