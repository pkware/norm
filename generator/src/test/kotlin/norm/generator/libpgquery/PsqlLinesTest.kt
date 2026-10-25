package norm.generator.libpgquery

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PsqlLinesTest {

  private fun psqlLines(vararg lines: String): PsqlLines = PsqlLines.of(lines.toList())

  @Nested
  inner class BackslashLines {

    @Test
    fun `drops a line that starts with a backslash after whitespace`() {
      val result = psqlLines("SELECT 1;", "  \\d t", "SELECT 2;")
      assertThat(result.text).isEqualTo("SELECT 1;\nSELECT 2;\n")
    }
  }

  @Nested
  inner class QueryBufferCommands {

    @ParameterizedTest
    @ValueSource(strings = ["\\g", "\\gx", "\\gset prefix_", "\\gexec", "\\gdesc", "  \\g |cat", "\\g(format=csv)"])
    fun `replaces a command that sends the query buffer with a line holding only a semicolon`(command: String) {
      val result = psqlLines("SELECT 1", command, "SELECT 2;")
      assertThat(result.text).isEqualTo("SELECT 1\n;\nSELECT 2;\n")
      assertThat(LibPgQuery.split(result.text).map { result.text.substring(it.start, it.end).trim() })
        .isEqualTo(listOf("SELECT 1", "SELECT 2"))
    }

    @Test
    fun `drops getenv, which is not a command that sends the query buffer`() {
      val result = psqlLines("\\getenv libdir PG_LIBDIR", "SELECT 1;")
      assertThat(result.text).isEqualTo("SELECT 1;\n")
    }

    @Test
    fun `drops a command that only shares a prefix with one that sends the query buffer`() {
      val result = psqlLines("SELECT 1", "\\gexecx", "\\gsetter", "\\gdescribe", "SELECT 2;")
      assertThat(result.text).isEqualTo("SELECT 1\nSELECT 2;\n")
    }
  }

  @Nested
  inner class ConditionalBlocks {

    @Test
    fun `drops the lines of the branch that is not taken and logs them`() {
      val result = psqlLines("\\if true", "SELECT 1;", "\\else", "SELECT 2;", "\\endif", "SELECT 3;")
      assertThat(result.text).isEqualTo("SELECT 1;\nSELECT 3;\n")
      assertThat(result.inactiveBranches).isEqualTo(listOf(InactiveBranch(4, 4, "SELECT 2;")))
    }

    @Test
    fun `keeps the else branch when the condition is false`() {
      val result = psqlLines("\\if false", "SELECT 1;", "\\else", "SELECT 2;", "\\endif")
      assertThat(result.text).isEqualTo("SELECT 2;\n")
      assertThat(result.inactiveBranches).isEqualTo(listOf(InactiveBranch(2, 2, "SELECT 1;")))
    }

    @Test
    fun `takes the first elif that holds and no later branch`() {
      val result = psqlLines(
        "\\if false", "SELECT 1;", "\\elif off", "SELECT 2;", "\\elif on", "SELECT 3;",
        "\\elif true", "SELECT 4;", "\\else", "SELECT 5;", "\\endif",
      )
      assertThat(result.text).isEqualTo("SELECT 3;\n")
      assertThat(
        result.inactiveBranches.map {
          it.firstCode
        },
      ).isEqualTo(listOf("SELECT 1;", "SELECT 2;", "SELECT 4;", "SELECT 5;"))
    }

    @Test
    fun `keeps nothing of a block nested in an inactive branch`() {
      val result = psqlLines(
        "\\if false",
        "\\if true",
        "SELECT 1;",
        "\\else",
        "SELECT 2;",
        "\\endif",
        "\\endif",
        "SELECT 3;",
      )
      assertThat(result.text).isEqualTo("SELECT 3;\n")
    }

    @Test
    fun `evaluates a block nested in an active branch`() {
      val result = psqlLines(
        "\\if true",
        "\\if false",
        "SELECT 1;",
        "\\else",
        "SELECT 2;",
        "\\endif",
        "SELECT 3;",
        "\\endif",
      )
      assertThat(result.text).isEqualTo("SELECT 2;\nSELECT 3;\n")
    }

    @Test
    fun `does not evaluate a condition that psql does not evaluate`() {
      val result = psqlLines(
        "\\if false",
        "\\if :nested",
        "SELECT 1;",
        "\\endif",
        "\\elif true",
        "SELECT 2;",
        "\\elif :later",
        "\\endif",
      )
      assertThat(result.text).isEqualTo("SELECT 2;\n")
    }

    @Test
    fun `makes a branch inactive and logs the condition when it is not a boolean literal`() {
      val result = psqlLines("SELECT 1;", "\\if :skip_test", "\\quit", "\\endif", "SELECT 2;")
      assertThat(result.text).isEqualTo("SELECT 1;\nSELECT 2;\n")
      assertThat(result.inactiveBranches).isEqualTo(listOf(InactiveBranch(2, 2, "\\if :skip_test")))
    }

    @Test
    fun `runs the else branch after a condition that is not a boolean literal`() {
      val result = psqlLines("\\if invalid boolean expression", "SELECT 1;", "\\else", "SELECT 2;", "\\endif")
      assertThat(result.text).isEqualTo("SELECT 2;\n")
      assertThat(result.inactiveBranches).isEqualTo(
        listOf(InactiveBranch(1, 1, "\\if invalid boolean expression"), InactiveBranch(2, 2, "SELECT 1;")),
      )
    }

    @Test
    fun `treats an elif without a condition as false and logs it`() {
      val result = psqlLines("\\if false", "\\elif", "SELECT 1;", "\\elif true", "SELECT 2;", "\\endif")
      assertThat(result.text).isEqualTo("SELECT 2;\n")
      assertThat(
        result.inactiveBranches,
      ).isEqualTo(listOf(InactiveBranch(2, 2, "\\elif"), InactiveBranch(3, 3, "SELECT 1;")))
    }

    @Test
    fun `ignores an elif or else outside a block and an elif after else`() {
      val result = psqlLines("\\elif", "\\else", "\\if false", "\\else", "SELECT 1;", "\\elif", "SELECT 2;", "\\endif")
      assertThat(result.text).isEqualTo("SELECT 1;\nSELECT 2;\n")
      assertThat(result.inactiveBranches).isEqualTo(emptyList())
    }

    @ParameterizedTest
    @ValueSource(strings = ["t", "TRUE", "yes", "Y", "on", "ON", "1"])
    fun `reads these as true`(literal: String) {
      assertThat(psqlLines("\\if $literal", "SELECT 1;", "\\endif").text).isEqualTo("SELECT 1;\n")
    }

    @ParameterizedTest
    @ValueSource(strings = ["f", "False", "no", "N", "off", "of", "0"])
    fun `reads these as false`(literal: String) {
      assertThat(psqlLines("\\if $literal", "SELECT 1;", "\\endif").text).isEqualTo("")
    }

    @Test
    fun `reads the ambiguous prefix o as a condition that is not a literal`() {
      val result = psqlLines("\\if o", "SELECT 1;", "\\endif")
      assertThat(result.text).isEqualTo("")
      assertThat(result.inactiveBranches.first()).isEqualTo(InactiveBranch(1, 1, "\\if o"))
    }
  }

  @Nested
  inner class SqlCopy {

    @Test
    fun `drops the data of COPY FROM STDIN through the terminator line`() {
      val result = psqlLines("COPY t FROM stdin;", "1", "\\N", "\\.", "SELECT 2;")
      assertThat(result.text).isEqualTo("COPY t FROM stdin;\nSELECT 2;\n")
      assertThat(result.copyBlocks).isEqualTo(listOf(CopyDataBlock(2, 4, "COPY t FROM stdin")))
    }

    @Test
    fun `drops the data when a comment follows the semicolon`() {
      val result = psqlLines("COPY t FROM stdin; -- load", "1", "\\.", "SELECT 2;")
      assertThat(result.text).isEqualTo("COPY t FROM stdin; -- load\nSELECT 2;\n")
    }

    @Test
    fun `keeps the lines after COPY TO STDOUT and COPY FROM a file`() {
      val result = psqlLines("COPY t TO stdout;", "COPY t FROM 'f';", "SELECT 1;")
      assertThat(result.text).isEqualTo("COPY t TO stdout;\nCOPY t FROM 'f';\nSELECT 1;\n")
      assertThat(result.copyBlocks).isEqualTo(emptyList())
    }

    @Test
    fun `finds a COPY after a statement that the lexer rejects`() {
      val result = psqlLines("SELECT 1 AS \"\";", "COPY t FROM stdin;", "1", "\\.", "SELECT 2;")
      assertThat(result.text).isEqualTo("SELECT 1 AS \"\";\nCOPY t FROM stdin;\nSELECT 2;\n")
    }
  }

  @Nested
  inner class BackslashCopy {

    @Test
    fun `drops the data of a copy command that reads standard input`() {
      val result = psqlLines("CREATE TABLE t (a text);", "\\copy t from stdin", "'New York'\tnyc", "\\.", "SELECT 2;")
      assertThat(result.text).isEqualTo("CREATE TABLE t (a text);\nSELECT 2;\n")
      assertThat(result.copyBlocks).isEqualTo(listOf(CopyDataBlock(3, 4, "\\copy t from stdin")))
    }

    @Test
    fun `keeps the lines after a copy command that writes or reads a file`() {
      val result = psqlLines("\\copy t to stdout", "\\copy t from 'f'", "SELECT 1;")
      assertThat(result.text).isEqualTo("SELECT 1;\n")
      assertThat(result.copyBlocks).isEqualTo(emptyList())
    }

    @Test
    fun `keeps the lines after another backslash command`() {
      val result = psqlLines("\\set ECHO none", "x", "SELECT 1;")
      assertThat(result.text).isEqualTo("x\nSELECT 1;\n")
    }
  }

  @Nested
  inner class ChainedStatements {

    @Test
    fun `each COPY FROM STDIN part of a chain consumes one data block in order`() {
      val result = psqlLines(
        "select 0\\; copy t from stdin\\; copy t from stdin\\; select 1; -- 0 1",
        "1",
        "\\.",
        "2",
        "\\.",
        "select * from t;",
      )
      assertThat(result.text)
        .isEqualTo("select 0\\; copy t from stdin\\; copy t from stdin\\; select 1; -- 0 1\nselect * from t;\n")
      assertThat(result.copyBlocks).isEqualTo(
        listOf(CopyDataBlock(2, 3, "copy t from stdin"), CopyDataBlock(4, 5, "copy t from stdin")),
      )
    }

    @Test
    fun `a chain without COPY FROM STDIN keeps the following lines`() {
      val result = psqlLines("select 0\\; select 1;", "x", "SELECT 2;")
      assertThat(result.text).isEqualTo("select 0\\; select 1;\nx\nSELECT 2;\n")
      assertThat(result.copyBlocks).isEqualTo(emptyList())
    }

    @Test
    fun `a chain that spans lines is read when its last part ends with a semicolon`() {
      val result = psqlLines("select 0\\;", "copy t from stdin;", "1", "\\.", "SELECT 2;")
      assertThat(result.text).isEqualTo("select 0\\;\ncopy t from stdin;\nSELECT 2;\n")
      assertThat(result.copyBlocks).isEqualTo(listOf(CopyDataBlock(3, 4, "copy t from stdin")))
    }
  }
}
