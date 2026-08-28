package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
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
 * the text a developer would read off the generated `.kt` file, extracted back out of its Markdown
 * rendering, still means what the original SQL meant when run against a real server (#238 8.1–8.3).
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
      val renderedExpression = extractInlineCodeSpanFor("sum2", repository.requiredTypes.first().kdoc.toString())

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
      val renderedExpression = extractInlineCodeSpanFor("u", repository.requiredTypes.first().kdoc.toString())

      // Decoding the escaped span (stripping exactly the delimiter markdownInlineCodeSpan added)
      // must recover the developer's own expression BYTE FOR BYTE -- proving the escaping never
      // altered the text itself, only how it is delimited.
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
      val kdoc = repository.requiredTypes.first().kdoc.toString()

      assertThat(kdoc.contains("@property u")).isFalse()
    }
  }

  /**
   * Extracts the inline code span's CONTENT from a `` @property [propertyName] (`...`) `` line in
   * [kdoc], stripping exactly the delimiter [markdownInlineCodeSpan] itself would have added — a
   * run of one or more backticks on each side, plus a single padding space just inside it if one
   * is present — and nothing more, so what remains is provably the UNMODIFIED expression text.
   */
  private fun extractInlineCodeSpanFor(propertyName: String, kdoc: String): String {
    val marker = "@property $propertyName ("
    val start = kdoc.indexOf(marker)
    check(start >= 0) { "no @property line for $propertyName in:\n$kdoc" }
    val contentStart = start + marker.length
    val contentEnd = kdoc.indexOf(")", contentStart)
    check(contentEnd >= 0)
    val span = kdoc.substring(contentStart, contentEnd)
    val delimiterLength = span.takeWhile { it == '`' }.length
    check(delimiterLength > 0) { "not a backtick-delimited span: $span" }
    val inner = span.substring(delimiterLength, span.length - delimiterLength)
    return if (inner.startsWith(" ") && inner.endsWith(" ")) inner.substring(1, inner.length - 1) else inner
  }

  companion object {
    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_kdoc_round_trip_test", inMemory = true)
  }
}
