package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import norm.generator.baseline.ScannerCorpus
import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryException
import norm.generator.libpgquery.TokenNumber
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Checks against a live server the claims that [QueryFileParser] makes about where a query ends. */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class QueryFileParserLiveTest {

  /** Maps each query body to the SQL that cutting it after the first line ending in `;` gives. */
  private val queryFileBodies = mapOf(
    "DO $$ BEGIN PERFORM 1;\nEND $$;" to "DO $$ BEGIN PERFORM 1",
    "SELECT 'a;\nb' AS v;" to "SELECT 'a",
    "SELECT 1 /* note;\nmore */ AS v;" to "SELECT 1 /* note",
    "SELECT E'\\';\n' AS v;" to "SELECT E'\\'",
    "SELECT 1 AS \"a;\nb\";" to "SELECT 1 AS \"a",
    "SELECT 1 /* outer /* inner; */ still;\n*/ AS v;" to "SELECT 1 /* outer /* inner; */ still",
    "SELECT \$tag\$a;\nb\$tag\$ AS v;" to "SELECT \$tag\$a",
    "DO $$\n-- name: b :one\nSELECT 1;\n$$;" to "DO $$",
  )

  @Test
  fun `a line reading of a body cuts a token and PostgreSQL rejects the cut`() {
    for ((body, lineReading) in queryFileBodies) {
      assertThat(rejection(lineReading), "line reading of $body").isNotNull()
      val parsed = QueryFileParser.parse("-- name: q :exec\n$body\n").single().sql
      assertThat(rejection(parsed), "parsed $body").isNull()
    }
  }

  @Test
  fun `a line reading cuts a statement at a semicolon in a line comment and loses a column`() {
    val body = "SELECT 1\n-- trailing;\n, 2"
    val lineReading = "SELECT 1\n-- trailing"

    val parsed = QueryFileParser.parse("-- name: q :one\n$body;\n").single().sql

    assertThat(parsed).isEqualTo("SELECT 1\n-- trailing;\n, 2")
    assertThat(columnCount(parsed)).isEqualTo(2)
    assertThat(columnCount(lineReading)).isEqualTo(1)
  }

  @Test
  fun `the JDBC driver runs a BEGIN ATOMIC body that holds semicolons as one statement`() {
    val body = "CREATE FUNCTION split_probe() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; SELECT 2; END"

    connection.createStatement().use { statement ->
      statement.execute(body)
      statement.executeQuery("SELECT split_probe()").use {
        it.next()
        assertThat(it.getInt(1)).isEqualTo(2)
      }
      statement.execute("DROP FUNCTION split_probe()")
    }

    val statements = splitStatements(body)
    assertThat(statements.size).isEqualTo(1)
  }

  @Test
  fun `PostgreSQL rejects text with a NUL character`() {
    assertThat(rejection("SELECT 'a\u0000b'"), "NUL in a string").isNotNull()
    assertThat(rejection("-- note\u0000\nSELECT 1"), "NUL in a comment").isNotNull()
  }

  @Test
  fun `PostgreSQL rejects every corpus text that the lexer rejects`() {
    val lexerRejected = corpusTexts().filter { text ->
      try {
        LibPgQuery.scan(text)
        false
      } catch (_: LibPgQueryException) {
        true
      }
    }

    assertRejected(lexerRejected)
  }

  @Test
  fun `PostgreSQL rejects every corpus text that holds tokens but no statement`() {
    val withoutStatement = corpusTexts().filter { text ->
      scanLeadingTokens(text).any { it.token !in IGNORED_TOKENS } && splitStatements(text).isEmpty()
    }

    assertRejected(withoutStatement)
  }

  @Test
  fun `PostgreSQL rejects every corpus EXPLAIN text that has no wrapped statement`() {
    val withoutWrapped = corpusTexts().filter { text ->
      val first = scanLeadingTokens(text).firstOrNull { it.token !in IGNORED_TOKENS }
      first != null &&
        text.substring(first.start, first.end).equals("EXPLAIN", ignoreCase = true) &&
        explainedStatementStart(text) == 0
    }

    assertRejected(withoutWrapped)
  }

  private fun assertRejected(texts: List<String>) {
    assertThat(texts.isNotEmpty(), "the corpus holds such a text").isEqualTo(true)
    assertThat(texts.filter { rejection(it) == null }).isEqualTo(emptyList())
  }

  private fun corpusTexts(): List<String> = ScannerCorpus.load().map { it.text }.filter { '\u0000' !in it }

  private fun rejection(sql: String): SQLException? = try {
    connection.prepareStatement(sql).use { it.parameterMetaData }
    null
  } catch (exception: SQLException) {
    exception
  }

  private fun columnCount(sql: String): Int = connection.prepareStatement(sql).use { it.metaData.columnCount }

  companion object {
    private val IGNORED_TOKENS = setOf(TokenNumber.LINE_COMMENT, TokenNumber.BLOCK_COMMENT, TokenNumber.SEMICOLON)

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_query_file_parser_test")

    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::connection.isInitialized) connection.close()
    }
  }
}
