package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.squareup.kotlinpoet.TypeSpec
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.parser.Parser
import org.junit.jupiter.api.Test

/**
 * Regression coverage for [addSqlStatementInterfaceMethod]'s `@param` KDoc lines: a stray backtick in
 * one parameter's column comment must not be free to pair with a backtick belonging to a LATER
 * parameter's own comment, the exact defect class [escapeMarkdownBacktick] already fixed for
 * [TypeRepository]'s `@property` lines (#238 10.3) — this emission path shares the same "single `\n`,
 * no blank line, between consecutive tags" shape, but is invisible to
 * [SourceReferenceLiveVerificationTest] since it produces no `@property` source-reference span.
 */
class InterfaceBuilderKdocTest {

  @Test
  fun `a stray backtick in one parameter comment cannot swallow a later param's own line into one code span`() {
    val statement = createStatement(
      sql = "SELECT 1",
      cmd = ":exec",
      params = listOf(
        Parameter(1, column("aParam", comment = "Say `hello")),
        Parameter(2, column("bParam", comment = "cruel world`")),
      ),
    )

    val builder = TypeSpec.interfaceBuilder("Test")
    builder.addSqlStatementInterfaceMethod(statement)
    val kdoc = builder.build().funSpecs.first().kdoc.toString()

    // Without escaping, CommonMark parses "`hello\n@param bParam cruel world`" as ONE inline code
    // span spanning both `@param` lines -- hiding bParam's own tag and comment text inside it.
    // Escaping the source backticks means no code span forms here at all.
    val codeSpans = mutableListOf<String>()
    Parser.builder().build().parse(kdoc).accept(
      object : AbstractVisitor() {
        override fun visit(code: Code) {
          codeSpans.add(code.literal)
        }
      },
    )
    assertThat(codeSpans).isEmpty()
  }

  @Test
  fun `a backslash immediately before a backtick in one parameter comment cannot open a code span either`() {
    // #238 11.2: escaping ONLY the backtick ("`" -> "\\`") is defeated when the comment already
    // contains a backslash immediately before the backtick -- CommonMark reads the resulting "\\`"
    // as an escaped backslash followed by an UNESCAPED, code-span-opening backtick, exactly the
    // defect this test's sibling above already guards against for a bare backtick.
    val statement = createStatement(
      sql = "SELECT 1",
      cmd = ":exec",
      params = listOf(
        Parameter(1, column("aParam", comment = "weird \\" + "`")),
        Parameter(2, column("bParam", comment = "cruel world`")),
      ),
    )

    val builder = TypeSpec.interfaceBuilder("Test")
    builder.addSqlStatementInterfaceMethod(statement)
    val kdoc = builder.build().funSpecs.first().kdoc.toString()

    val codeSpans = mutableListOf<String>()
    Parser.builder().build().parse(kdoc).accept(
      object : AbstractVisitor() {
        override fun visit(code: Code) {
          codeSpans.add(code.literal)
        }
      },
    )
    assertThat(codeSpans).isEmpty()
  }

  @Test
  fun `a percent sign in the query's own leading SQL comment does not abort generation`() {
    // #238 11.3: addStandardKdoc appends query.comments (the developer's own "-- comment" lines
    // preceding "-- name:") as a KotlinPoet KDoc FORMAT string with no arguments, the same class of
    // defect fixed for EnumBuilder's catalog comment -- a literal "%" in the developer's own comment
    // is read as a format specifier and throws building the KDoc.
    val statement = createStatement(
      sql = "SELECT 1",
      cmd = ":exec",
      comments = listOf("Matches 100% of rows."),
    )

    val builder = TypeSpec.interfaceBuilder("Test")
    builder.addSqlStatementInterfaceMethod(statement)
    val kdoc = builder.build().funSpecs.first().kdoc.toString()

    assertThat(kdoc).contains("Matches 100% of rows.")
  }

  @Test
  fun `an unescapable block-comment delimiter declines the fenced sql block, matching the data-class KDoc`() {
    // #238 12.2: TypeRepository.addClassKdoc already declines its "sql" fenced block for the exact
    // same query text (canRenderSqlVerbatim, guarded by containsUnescapableBlockCommentDelimiter)
    // because KotlinPoet's own KDoc emission unconditionally rewrites "/*"/"*/" to "/&#42;"/"&#42;/"
    // -- a rewrite CommonMark never decodes back inside a fenced code block. addStandardKdoc emitted
    // its own "```sql" fence unconditionally, so the very same query rendered faithfully in one KDoc
    // and corrupted in the other.
    val statement = createStatement(
      sql = "SELECT name || '/*x*/' AS block_delim",
      columns = listOf(column("block_delim")),
    )

    val builder = TypeSpec.interfaceBuilder("Test")
    builder.addSqlStatementInterfaceMethod(statement)
    val kdoc = builder.build().funSpecs.first().kdoc.toString()

    assertThat(kdoc).doesNotContain("```sql")
  }

  @Test
  fun `a query text line matching or exceeding a fixed 3-backtick fence does not truncate the sql block`() {
    // Twin-site sweep for #238 12: TypeRepository.addClassKdoc already guards its "sql" fenced
    // block against this exact hazard via markdownFenceDelimiter (a fence one backtick longer than
    // any run already in the SQL), but addStandardKdoc still opens/closes with a FIXED "```sql"
    // fence -- a query text containing its own line of 3+ backticks (e.g. inside a multi-line
    // string literal) closes that fence early, silently truncating the rendered SQL.
    val sqlWithTripleBacktickLine = "SELECT '\n```\n' AS x"
    val statement = createStatement(sql = sqlWithTripleBacktickLine, columns = listOf(column("x")))

    val builder = TypeSpec.interfaceBuilder("Test")
    builder.addSqlStatementInterfaceMethod(statement)
    val kdoc = builder.build().funSpecs.first().kdoc.toString()

    var fencedContent: String? = null
    Parser.builder().build().parse(kdoc).accept(
      object : AbstractVisitor() {
        override fun visit(fencedCodeBlock: FencedCodeBlock) {
          if (fencedContent == null && fencedCodeBlock.info == "sql") {
            fencedContent = fencedCodeBlock.literal.trimEnd('\n')
          }
        }
      },
    )
    assertThat(fencedContent).isEqualTo(sqlWithTripleBacktickLine)
  }
}
