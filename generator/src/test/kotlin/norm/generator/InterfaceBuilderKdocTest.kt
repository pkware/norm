package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import com.squareup.kotlinpoet.TypeSpec
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
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
}
