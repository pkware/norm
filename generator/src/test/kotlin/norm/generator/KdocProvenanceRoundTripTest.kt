package norm.generator

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * End-to-end proof, against a live PostgreSQL server, that a source-reference expression rendered
 * into generated KDoc ([TypeRepository.addClassKdoc]'s `` @property x (`EXPR`) `` line) is exactly
 * the developer's own SQL — not merely that the string computed internally looks right, but that
 * the text a developer would read off the generated `.kt` FILE, extracted back out of a REAL
 * Markdown render, still means what the original SQL meant when run against a real server (#238
 * 8.1–8.3, 9.1, 9.4).
 *
 * Reads [renderedFileTextFor], which runs the [TypeSpec] through KotlinPoet's own [Any.toString]
 * emission — the SAME [com.squareup.kotlinpoet.CodeWriter] machinery a real generated `.kt` file
 * goes through — NEVER `typeSpec.kdoc.toString()`. `kdoc.toString()` is the pre-render [CodeBlock]
 * text, computed before [com.squareup.kotlinpoet.CodeWriter] ever sees it, so it cannot show
 * [com.squareup.kotlinpoet.CodeWriter]'s own unconditional `"/*"`/`"*/"` → `"/&#42;"`/`"&#42;/"`
 * rewrite inside every KDoc block (#238 9.1) — a defect five prior verification rounds all missed
 * for exactly this reason (see [BlockCommentDelimiterDeclines]).
 *
 * Extracts the rendered expression with a REAL CommonMark parse ([extractSourceReferenceExpression])
 * rather than cutting the surrounding text at the first `)` — the previous version of this test did
 * that, which cannot handle an expression containing its own parenthesis at all (nearly every real
 * expression — see [ParenthesisContainingExpressionRoundTrips]).
 */
@Testcontainers
class KdocProvenanceRoundTripTest {

  @Nested
  inner class TopLevelExpressionWithComment {

    @Test
    fun `the top-level path's rendered expression parses, and evaluates the same as the original select item`() {
      // #238 8.1 repro: a "--" comment sitting mid-expression used to be embedded verbatim,
      // rendering "a + -- note\n  b" -- PostgreSQL rejects that with a syntax error, since the
      // comment swallows the "b" operand along with the rest of its own line.
      val queryText = "SELECT a + -- note\n  b AS sum2 FROM t"
      val sumColumn = Column(name = "sum2", notNull = true, type = Identifier(name = "int4"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("sumWithComment", listOf(sumColumn), queryText)
      val renderedExpression = extractSourceReferenceExpression("sum2", renderedFileTextFor(repository))
      checkNotNull(renderedExpression)

      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use { statement ->
          statement.execute("CREATE TABLE t (a INT, b INT)")
          statement.execute("INSERT INTO t VALUES (5, 3)")
        }
        val extractedResult = connection.createStatement().use { statement ->
          statement.executeQuery("SELECT $renderedExpression FROM t").use { resultSet ->
            check(resultSet.next())
            resultSet.getInt(1)
          }
        }
        val originalResult = connection.createStatement().use { statement ->
          statement.executeQuery("SELECT a + b FROM t").use { resultSet ->
            check(resultSet.next())
            resultSet.getInt(1)
          }
        }
        assertThat(extractedResult).isEqualTo(originalResult)
      }
    }
  }

  @Nested
  inner class BacktickContainingExpression {

    @Test
    fun `the escaped inline code span decodes back to the exact original expression and evaluates correctly`() {
      // #238 8.3 repro: a literal backtick inside the expression used to close a single-backtick
      // inline span early, corrupting the rendered Markdown.
      val expressionColumn = Column(
        name = "u",
        notNull = false,
        type = Identifier(name = "text"),
        provenanceExpression = "'x' || '`'",
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("backtickExpression", listOf(expressionColumn), "SELECT u FROM c")
      val renderedExpression = extractSourceReferenceExpression("u", renderedFileTextFor(repository))

      // Decoding the REAL Markdown render must recover the developer's own expression BYTE FOR
      // BYTE -- proving the escaping never altered the text itself, only how it is delimited.
      assertThat(renderedExpression).isEqualTo("'x' || '`'")

      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val value = connection.createStatement().use { statement ->
          statement.executeQuery("SELECT $renderedExpression").use { resultSet ->
            check(resultSet.next())
            resultSet.getString(1)
          }
        }
        assertThat(value).isEqualTo("x`")
      }
    }
  }

  @Nested
  inner class ParenthesisContainingExpressionRoundTrips {

    @Test
    fun `an expression containing its own nested parentheses round-trips whole, not truncated at the first one`() {
      // This test's own point: the OLD extraction helper cut the span at the first ")" in the
      // surrounding text, so ANY expression containing a parenthesis -- nearly every real one --
      // would have been truncated to "COALESCE(a, 0" here, which does not even parse. A real
      // CommonMark parse locates the actual Code node instead of guessing from a bracket count.
      val expressionColumn = Column(
        name = "u",
        notNull = false,
        type = Identifier(name = "int4"),
        provenanceExpression = "COALESCE(a, 0) + (b * 2)",
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("parenthesizedExpression", listOf(expressionColumn), "SELECT u FROM c")
      val renderedExpression = extractSourceReferenceExpression("u", renderedFileTextFor(repository))

      assertThat(renderedExpression).isEqualTo("COALESCE(a, 0) + (b * 2)")

      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use { statement ->
          statement.execute("CREATE TABLE c (a INT, b INT)")
          statement.execute("INSERT INTO c VALUES (NULL, 4)")
        }
        val extractedResult = connection.createStatement().use { statement ->
          statement.executeQuery("SELECT $renderedExpression FROM c").use { resultSet ->
            check(resultSet.next())
            resultSet.getInt(1)
          }
        }
        // COALESCE(NULL, 0) + (4 * 2) = 8
        assertThat(extractedResult).isEqualTo(8)
      }
    }
  }

  @Nested
  inner class NewlineContainingExpressionDeclinesRatherThanCorrupt {

    @Test
    fun `a newline-containing expression is declined entirely -- there is nothing to extract or run`() {
      // #238 8.2 repro: rendering "s || 'a\nb'" as a single-line inline code span used to fold the
      // literal's own newline to a space when rendered, silently changing "'a\nb'" into the
      // DIFFERENT value "'a b'" (verified live: "SELECT ('a\nb' = 'a b')" is false).
      val expressionColumn = Column(
        name = "u",
        notNull = false,
        type = Identifier(name = "text"),
        provenanceExpression = "'a' || '\nb'",
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("newlineExpression", listOf(expressionColumn), "SELECT u FROM c")
      val generatedFile = renderedFileTextFor(repository)

      assertThat(generatedFile.contains("@property u")).isFalse()
    }
  }

  @Nested
  inner class BlockCommentDelimiterDeclines {

    @Test
    fun `an expression containing a block-comment close delimiter is declined, never rendered as an entity`() {
      // #238 9.1: KotlinPoet's own KDoc emission (CodeWriter.emit, kdoc branch) unconditionally
      // rewrites "*/" to "&#42;/" inside EVERY KDoc block it writes -- necessary so a literal block
      // comment can never prematurely close the surrounding "/** ... */" comment, but CommonMark
      // never decodes an HTML entity back to a literal character INSIDE a code span. Reading
      // typeSpec.kdoc.toString() (the pre-render CodeBlock text) would MISS this rewrite entirely
      // -- it only happens when the TypeSpec is actually rendered, which is exactly what
      // renderedFileTextFor does here.
      val expressionColumn = Column(
        name = "u",
        notNull = false,
        type = Identifier(name = "text"),
        provenanceExpression = "s || '*/'",
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("starSlashExpression", listOf(expressionColumn), "SELECT u FROM c")
      val generatedFile = renderedFileTextFor(repository)

      assertThat(generatedFile).doesNotContain("@property u")
      assertThat(generatedFile).doesNotContain("&#42;")
      assertThat(extractSourceReferenceExpression("u", generatedFile)).isNull()
    }

    @Test
    fun `a query containing a block comment gets no fenced sql block, never a rewritten one`() {
      val plainColumn = Column(name = "id", notNull = true, type = Identifier(name = "int4"))
      val queryText = "SELECT id /* note */ FROM t"

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("getIdWithComment", listOf(plainColumn), queryText)
      val generatedFile = renderedFileTextFor(repository)

      assertThat(generatedFile).doesNotContain("```sql")
      assertThat(generatedFile).doesNotContain("&#42;")
    }
  }

  /**
   * Renders [repository]'s first generated type through KotlinPoet's OWN [Any.toString] emission
   * (`buildCodeString { emit(this, null) }`, the same [com.squareup.kotlinpoet.CodeWriter]-based
   * machinery `FileSpec.writeTo` uses to produce a real generated `.kt` file) — never
   * `typeSpec.kdoc.toString()`, which returns the [CodeBlock]'s own pre-render text, computed
   * BEFORE [com.squareup.kotlinpoet.CodeWriter] ever applies its KDoc-block escaping (#238 9.1).
   */
  private fun renderedFileTextFor(repository: TypeRepository): String = repository.requiredTypes.first().toString()

  /**
   * Extracts the rendered Markdown CONTENT of the inline code span following
   * `` @property [propertyName] ( `` in [generatedFileText]'s KDoc block, or `null` if there is no
   * such `@property` line with a source-reference span at all (the "declined" case).
   *
   * Unlike cutting the surrounding text at the first `)` (what this test used to do, and which
   * cannot handle an expression containing its own parenthesis — see
   * [ParenthesisContainingExpressionRoundTrips]), this locates the actual [Code] inline node a REAL
   * CommonMark parse produces: [generatedFileText]'s `/** ... */` block is unwrapped back to its
   * own Markdown source first (stripping the ` * `/` *` line prefix
   * [com.squareup.kotlinpoet.CodeWriter] adds to every KDoc line — a real KDoc renderer, e.g. an
   * IDE's quick-doc popup or Dokka, strips that same prefix before treating the remainder as
   * Markdown), then parsed, then walked tracking the plain-text stream immediately preceding each
   * [Code] node so the marker match is by POSITION, not merely by "some code span exists somewhere
   * in the document" (which would misattribute if more than one property has a source reference).
   */
  private fun extractSourceReferenceExpression(propertyName: String, generatedFileText: String): String? {
    val kdocMarkdown = extractKdocMarkdown(generatedFileText)
    val marker = "@property $propertyName ("
    var precedingText = StringBuilder()
    var matchedLiteral: String? = null

    val visitor = object : AbstractVisitor() {
      override fun visit(text: Text) {
        precedingText.append(text.literal)
      }

      override fun visit(softLineBreak: SoftLineBreak) {
        precedingText.append("\n")
      }

      override fun visit(code: Code) {
        if (matchedLiteral == null && precedingText.toString().endsWith(marker)) {
          matchedLiteral = code.literal
        }
        precedingText.append("`").append(code.literal).append("`")
      }
    }

    Parser.builder().build().parse(kdocMarkdown).accept(visitor)
    return matchedLiteral
  }

  /**
   * Unwraps a `/** ... */` KDoc block out of [generatedFileText] back to its own Markdown source:
   * strips the leading `/**` line and trailing ` */` line, and strips the ` * `/` *` prefix
   * [com.squareup.kotlinpoet.CodeWriter] adds to every content line in between (` * ` for a line
   * with content, bare ` *` for a blank line — see [com.squareup.kotlinpoet.CodeWriter.emit]).
   */
  private fun extractKdocMarkdown(generatedFileText: String): String {
    val openDelimiter = "/**\n"
    val start = generatedFileText.indexOf(openDelimiter)
    check(start >= 0) { "no KDoc block found in:\n$generatedFileText" }
    val contentStart = start + openDelimiter.length
    val closeDelimiter = "\n */\n"
    val end = generatedFileText.indexOf(closeDelimiter, contentStart)
    check(end >= 0) { "no closing KDoc delimiter found in:\n$generatedFileText" }

    return generatedFileText.substring(contentStart, end)
      .lineSequence()
      .joinToString("\n") { line ->
        val withoutPrefix = line.removePrefix(" *")
        withoutPrefix.removePrefix(" ")
      }
  }

  companion object {
    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_kdoc_round_trip_test", inMemory = true)
  }
}
