package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Repository-wide, live-server proof that EVERY `@property` source-reference span in EVERY golden
 * `.kt` file is SQL PostgreSQL accepts — the same standard [KdocProvenanceRoundTripTest] proves for
 * hand-built [Column] fixtures, extended here to the checked-in golden corpus itself (#238).
 *
 * A wrong or unparseable span is exactly what the last several verification passes over #238 found
 * by hand — driving the real generator, reading the generated FILE (never
 * `TypeSpec.kdoc.toString()`, which misses KotlinPoet's own KDoc-rendering rewrites — see
 * [KdocProvenanceRoundTripTest]'s KDoc), extracting each source reference with a real CommonMark
 * parse, and running the extracted text against a live server. This test makes that method a
 * permanent part of the suite: a future change that makes any golden span unparseable now fails the
 * build here, rather than waiting for the next manual review pass.
 *
 * A golden `.kt` file IS the generated file, checked into the repository — [GenerateCodeTest]
 * already proves, for every one of these same scenarios, that a freshly generated file matches its
 * golden BYTE FOR BYTE, so reading the committed golden text is equivalent to driving the generator
 * again and is far cheaper across this many files.
 *
 * Verification strategy: a source-reference span is either a `` `table."Column"` `` reference or a
 * computed expression (see [PropertySource.sourceReference]'s own KDoc); either way, the span is
 * proven by finding SOME syntactically valid context — a real table from the scenario's own schema,
 * or a CTE body's own `FROM` clause taken from the golden's embedded `sql` fence — in which
 * `EXPLAIN SELECT (span) <context>` parses and analyzes without error. `EXPLAIN` (never executed)
 * is used deliberately: several golden queries are `DELETE`/`UPDATE` CTE bodies, and this test must
 * never mutate the schema it is verifying against.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class SourceReferenceLiveVerificationTest {

  /**
   * One golden `.kt` file paired with the schema it must be verified against.
   */
  data class GoldenFileCase(val path: Path, val schemaPath: Path) {
    override fun toString(): String = path.toString()
  }

  @ParameterizedTest
  @MethodSource("goldenFiles")
  fun `every source-reference span in the golden file parses against a live server`(case: GoldenFileCase) {
    val fileText = case.path.readText()
    val markdown = extractKdocMarkdown(fileText) ?: return
    val markerCount = countSourceReferenceMarkers(markdown)
    if (markerCount == 0) return

    val spans = extractSourceReferenceSpans(markdown)
    // A raw "(`" in the KDoc's own Markdown SOURCE (before CommonMark parses it) is the exact,
    // and only, text addClassKdoc emits via `append("($source)")` where $source is itself
    // backtick-wrapped -- so every occurrence means a span WAS emitted here, and each must parse
    // back out on its own. Comparing the FULL count -- not just checking spans is non-empty --
    // catches a PARTIAL loss too: losing one span while others survive still leaves the list
    // non-empty, which a plain emptiness check would silently accept (#238 12.3). A mismatch means
    // span delimitation broke somewhere in this file's KDoc paragraph (#238 11.2's own defect
    // class): a stray, unpaired backtick or backslash elsewhere in the same paragraph closed,
    // reopened, or swallowed a code span in the wrong place.
    assertThat(
      spans.size,
      "found ${spans.size} source-reference span(s) in ${case.path} but its raw KDoc source " +
        "contains $markerCount \"(`\" marker(s) -- span delimitation broke somewhere in this " +
        "file's KDoc paragraph, whether that lost every span or only some of them",
    ).isEqualTo(markerCount)

    val originalSql = extractFencedSqlBlock(markdown)

    resetPublicSchema()
    connection.createStatement().use { it.execute(case.schemaPath.readText()) }
    val tableNames = realTableNames()
    val cteFromClauses = originalSql?.let(::collectCteFromClauses).orEmpty()

    for (span in spans) {
      val candidates = candidateQueries(span, tableNames, cteFromClauses)
      val verified = candidates.any(::explainsWithoutError)
      assertThat(verified, "source reference `$span` in ${case.path}").isTrue()
    }
  }

  @Test
  fun `a swallowed span among surviving ones is a count mismatch, not silently accepted`() {
    // #238 12.3: reproduces the exact shape a plain "spans.isEmpty()" check cannot see -- property
    // b's own unescaped backtick ("Say `hello", the #238 10.3 defect class fixed elsewhere by
    // escapeMarkdownBacktick) pairs with property c's marker backtick instead of a's, swallowing
    // c's ENTIRE span into a bigger code span that is not preceded by "(" at all. Property a's own
    // span, earlier in the same paragraph and fully self-contained, still parses correctly -- so
    // the result is non-empty (a's span alone), which the old guard would have accepted outright.
    val markdown = "@property a (`t.\"x\"`)\n@property b Say `hello\n@property c (`SOME_EXPR`)"

    val spans = extractSourceReferenceSpans(markdown)
    val markerCount = countSourceReferenceMarkers(markdown)

    assertThat(spans).containsExactly("t.\"x\"")
    assertThat(markerCount).isEqualTo(2)
    // The guard's own comparison: a non-empty, but INCOMPLETE, span list must still be caught.
    assertThat(spans.size).isEqualTo(1)
  }

  /**
   * Drops and recreates the `public` schema so each golden file's own `schema.sql` starts from a
   * clean catalog — mirrors [GenerateCodeTest]'s identical reset, needed for the same reason: golden
   * files across different scenario directories declare overlapping table names.
   */
  private fun resetPublicSchema() {
    connection.createStatement().use {
      it.execute(
        """
        DEALLOCATE ALL;
        DROP SCHEMA public CASCADE;
        CREATE SCHEMA public;
        GRANT ALL ON SCHEMA public TO public;
        """.trimIndent(),
      )
    }
  }

  /** Every real table/view name currently in the `public` schema, for use as a `FROM` candidate. */
  private fun realTableNames(): List<String> = buildList {
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
      ).use { resultSet ->
        while (resultSet.next()) add(resultSet.getString(1))
      }
    }
  }

  /**
   * For every CTE declared anywhere in [sql] (including a CTE body's own nested `WITH` clause,
   * walked recursively), the CTE's own `WITH` prefix text paired with its body's `FROM`-onward
   * text — a syntactically valid context for a computed expression drawn from that body, since a
   * sibling CTE the body's `FROM` clause references by name is declared in that SAME `WITH` prefix.
   *
   * A CTE body with no top-level `FROM` at all (a bare `VALUES`/`TABLE` body, or one whose only
   * source is another CTE addressed without needing a table) contributes nothing here; such an
   * expression is still reachable via [realTableNames]'s direct-table candidates when it references
   * a real column.
   */
  private fun collectCteFromClauses(sql: String): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    fun recurse(scopeText: String) {
      val cte = parseCteClause(scopeText) ?: return
      val withPrefix = scopeText.substring(0, cte.mainQueryStart)
      for (definition in cte.definitions) {
        val body = scopeText.substring(definition.bodyOpenParenthesis + 1, definition.bodyCloseParenthesis)
        val fromIndex = findTopLevelFromClauseKeyword(body, 0)
        if (fromIndex >= 0) {
          results.add(withPrefix to body.substring(fromIndex))
        }
        recurse(body)
      }
    }
    recurse(sql)
    return results
  }

  /**
   * Every syntactic context worth trying [span] against: no context at all (a self-contained
   * literal or aggregate like `COUNT(*)`), each real table in the schema, and each CTE body's own
   * `FROM`-onward text (for an expression that needs a JOIN a single bare table can't supply).
   */
  private fun candidateQueries(
    span: String,
    tableNames: List<String>,
    cteFromClauses: List<Pair<String, String>>,
  ): List<String> = buildList {
    add("EXPLAIN SELECT ($span)")
    for (table in tableNames) add("EXPLAIN SELECT ($span) FROM \"$table\"")
    for ((withPrefix, fromOnward) in cteFromClauses) add("EXPLAIN $withPrefix SELECT ($span) $fromOnward")
  }

  private fun explainsWithoutError(sql: String): Boolean = try {
    connection.createStatement().use { it.execute(sql) }
    true
  } catch (_: SQLException) {
    false
  }

  /**
   * Extracts every source-reference span from [markdown]: a [Code] node whose immediately preceding
   * text ends with `(` — the exact, and only, shape [TypeSpec.Builder.addClassKdoc] produces via
   * `append("($source)")`. A `@property` tag's own NAME token can also be a [Code] node (when the
   * property name itself needs backtick-quoting), but that one is always preceded by `@property `
   * or comment prose, never a bare `(`, so this marker never confuses the two.
   */
  private fun extractSourceReferenceSpans(markdown: String): List<String> {
    val spans = mutableListOf<String>()
    val precedingText = StringBuilder()
    val visitor = object : AbstractVisitor() {
      override fun visit(text: Text) {
        precedingText.append(text.literal)
      }

      override fun visit(softLineBreak: SoftLineBreak) {
        precedingText.append("\n")
      }

      override fun visit(code: Code) {
        if (precedingText.endsWith("(")) spans.add(code.literal)
        precedingText.append("`").append(code.literal).append("`")
      }
    }
    Parser.builder().build().parse(markdown).accept(visitor)
    return spans
  }

  /**
   * Counts every raw `` (` `` marker in [markdown]'s own Markdown SOURCE, before CommonMark parses
   * it — the exact, and only, text [TypeSpec.Builder.addClassKdoc] emits via `append("($source)")`
   * where `$source` is itself backtick-wrapped. Compared against [extractSourceReferenceSpans]'s
   * PARSED count, this is what catches a partial span loss (#238 12.3): [extractSourceReferenceSpans]
   * alone cannot tell "every marker parsed" from "some markers parsed, one silently swallowed".
   */
  private fun countSourceReferenceMarkers(markdown: String): Int {
    var count = 0
    var index = markdown.indexOf("(`")
    while (index >= 0) {
      count++
      index = markdown.indexOf("(`", index + 2)
    }
    return count
  }

  /** The literal contents of [markdown]'s own ` ```sql ` fenced code block, or `null` if it has none. */
  private fun extractFencedSqlBlock(markdown: String): String? {
    var result: String? = null
    val visitor = object : AbstractVisitor() {
      override fun visit(fencedCodeBlock: FencedCodeBlock) {
        if (result == null && fencedCodeBlock.info == "sql") result = fencedCodeBlock.literal.trimEnd('\n')
      }
    }
    Parser.builder().build().parse(markdown).accept(visitor)
    return result
  }

  /**
   * Unwraps the FIRST `/** ... */` KDoc block out of [fileText] back to its own Markdown source —
   * indentation-agnostic, unlike [KdocProvenanceRoundTripTest]'s identical-in-spirit helper, which
   * only ever reads a single, TOP-LEVEL (zero-indent) class KDoc rendered in isolation. A generated
   * FILE (as opposed to one [com.squareup.kotlinpoet.TypeSpec] rendered alone) can contain many
   * KDoc blocks nested at different indentation depths (one per interface method, for instance), so
   * the opening/closing markers are matched by their TRIMMED content (`/**`/`*/`) rather than a
   * fixed literal indent.
   *
   * Only the first block is ever examined: a data-class projection file — the only kind that ever
   * carries an `@property` source-reference span, via `addClassKdoc` — has exactly one [TypeSpec]
   * and therefore at most one KDoc block, so there is nothing later in such a file to miss. A file
   * with no source-reference-bearing KDoc at all (an interface/implementation file, or a table
   * projection with no documentation) either has no KDoc block (`null`, below) or a first block that
   * [extractSourceReferenceSpans] simply finds no matching span in — the caller already handles
   * both by skipping.
   *
   * @return `null` if [fileText] has no KDoc block at all.
   */
  private fun extractKdocMarkdown(fileText: String): String? {
    val lines = fileText.lines()
    val openIndex = lines.indexOfFirst { it.trimStart() == "/**" }
    if (openIndex < 0) return null
    val closeIndex = ((openIndex + 1) until lines.size).firstOrNull { lines[it].trimStart() == "*/" }
      ?: error("no closing KDoc delimiter found in:\n$fileText")

    return lines.subList(openIndex + 1, closeIndex).joinToString("\n") { line ->
      val trimmed = line.trimStart()
      when {
        trimmed == "*" -> ""
        trimmed.startsWith("* ") -> trimmed.removePrefix("* ")
        trimmed.startsWith("*") -> trimmed.removePrefix("*")
        else -> line
      }
    }
  }

  companion object {
    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_source_reference_verification_test")

    private lateinit var connection: Connection

    // sqlc.embed() is not yet supported by the JDBC analyzer -- these scenarios' golden files were
    // never produced by a real analysis run in the first place. Matches GenerateCodeTest's own
    // exclusion.
    private val EMBED_SCENARIOS =
      setOf("basic_embeds", "complex_embed_mixing", "consecutive_embeds", "nested_joins_embeds")

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

    @JvmStatic
    fun goldenFiles(): List<GoldenFileCase> {
      val scenarios = Path("../test-scenarios").toAbsolutePath()
        .listDirectoryEntries()
        .filter { it.isDirectory() && it.fileName.toString() !in EMBED_SCENARIOS }
      val scenarioCases = scenarios.flatMap { scenarioDirectory ->
        val schemaPath = scenarioDirectory.resolve("schema.sql")
        val exampleDirectory = scenarioDirectory.resolve("example")
        if (!Files.isDirectory(exampleDirectory)) return@flatMap emptyList()
        Files.walk(exampleDirectory).use { files ->
          files.filter { it.toString().endsWith(".kt") }.toList()
        }.map { GoldenFileCase(it, schemaPath) }
      }

      val frameworkScenariosDirectory = Path("../test-scenarios-frameworks").toAbsolutePath()
      val frameworkCases = frameworkScenariosDirectory.listDirectoryEntries()
        .filter { it.isDirectory() }
        .flatMap { scenarioDirectory ->
          val schemaPath = scenarioDirectory.resolve("schema.sql")
          listOf("micronaut", "micronaut-di", "spring").flatMap { goldenSubdirectory ->
            val directory = scenarioDirectory.resolve(goldenSubdirectory)
            if (!Files.isDirectory(directory)) return@flatMap emptyList()
            Files.walk(directory).use { files ->
              files.filter { it.toString().endsWith(".kt") }.toList()
            }.map { GoldenFileCase(it, schemaPath) }
          }
        }

      return scenarioCases + frameworkCases
    }
  }
}
