package norm.generator.baseline

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KotlinStringLiteralTest {

  private fun values(source: String): List<String> = lexKotlinStringLiterals(source).map { it.value }

  @Nested
  inner class RegularLiterals {

    @Test
    fun `extracts each literal in source order`() {
      assertThat(values("""val a = "one"; val b = "two"""")).isEqualTo(listOf("one", "two"))
    }

    @Test
    fun `decodes the supported escapes`() {
      val source = """val a = "q\" d\${'$'}x b\\ n\n t\t r\r ué""""
      assertThat(values(source)).isEqualTo(listOf("q\" d${'$'}x b\\ n\n t\t r\r ué"))
    }

    @Test
    fun `leaves a simple template name verbatim`() {
      assertThat(values("""val a = "x ${'$'}name y"""")).isEqualTo(listOf("x ${'$'}name y"))
    }

    @Test
    fun `leaves a braced template verbatim, including a nested literal`() {
      assertThat(values("""val a = "x ${'$'}{f("n}") + 1} y"""")).isEqualTo(listOf("x ${'$'}{f(\"n}\") + 1} y"))
    }

    @Test
    fun `reads an empty literal`() {
      assertThat(values("""val a = """"")).isEqualTo(listOf(""))
    }

    @Test
    fun `rejects an escape it does not decode`() {
      assertFailure { values("""val a = "\q"""") }
        .isInstanceOf(IllegalArgumentException::class)
        .hasMessage("Unsupported escape \\q at line 1")
    }
  }

  @Nested
  inner class RawLiterals {

    @Test
    fun `keeps backslashes and quotes verbatim`() {
      val source = "val a = \"\"\"a\\n \"q\" \\\" b\"\"\""
      assertThat(values(source)).isEqualTo(listOf("a\\n \"q\" \\\" b"))
    }

    @Test
    fun `keeps a quote that directly precedes the closing delimiter`() {
      val source = "val a = \"\"\"say \"hi\"\"\"\""
      assertThat(values(source)).isEqualTo(listOf("say \"hi\""))
    }

    @Test
    fun `leaves a braced template verbatim`() {
      val source = "val a = \"\"\"x \${f(\"\"\"y\"\"\")} z\"\"\""
      assertThat(values(source)).isEqualTo(listOf("x \${f(\"\"\"y\"\"\")} z"))
    }

    @Test
    fun `records a literal followed by trimIndent after trimIndent`() {
      val source = "val a = \"\"\"\n    SELECT 1\n      FROM t\n    \"\"\".trimIndent()"
      assertThat(values(source)).isEqualTo(listOf("SELECT 1\n  FROM t"))
    }

    @Test
    fun `applies trimIndent that starts on the next line`() {
      val source = "val a = \"\"\"\n    SELECT 1\n    \"\"\"\n  .trimIndent()"
      assertThat(values(source)).isEqualTo(listOf("SELECT 1"))
    }

    @Test
    fun `leaves a raw literal followed by another call unchanged`() {
      val source = "val a = \"\"\"\n  SELECT 1\n\"\"\".trim()"
      assertThat(values(source)).isEqualTo(listOf("\n  SELECT 1\n"))
    }
  }

  @Nested
  inner class SkippedText {

    @Test
    fun `ignores quotes in comments`() {
      val source = "// say \"no\"\n/* a \"b\" /* \"c\" */ \"d\" */\nval a = \"yes\""
      assertThat(values(source)).isEqualTo(listOf("yes"))
    }

    @Test
    fun `ignores a quote character literal`() {
      assertThat(values("val a = '\"'; val b = \"x\"")).isEqualTo(listOf("x"))
    }

    @Test
    fun `ignores quotes in a backticked name`() {
      assertThat(values("fun `a \"b\" c`() = \"x\"")).isEqualTo(listOf("x"))
    }
  }

  @Nested
  inner class Lines {

    @Test
    fun `records the line of the opening quote`() {
      val source = "val a = \"x\"\n\nval b = \"\"\"\ny\n\"\"\"\nval c = \"z\""
      assertThat(lexKotlinStringLiterals(source).map { it.line }).isEqualTo(listOf(1, 3, 6))
    }
  }
}
