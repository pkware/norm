package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PgNodeTreeScannerTest {

  private val scanner = PgNodeTreeScanner()

  @Nested
  inner class Arms {

    @Test
    fun `a brace value reads as a Block including the outer braces`() {
      val text = "{QUERY :expr {VAR :varno 1} :other 2}"
      assertThat(scanner.fieldAtDepthOne(text, ":expr")).isEqualTo(FieldValue.Block("{VAR :varno 1}"))
    }

    @Test
    fun `a parenthesized value reads as ListContent without the outer parentheses`() {
      val text = "{QUERY :args (a b c) :other 2}"
      assertThat(scanner.fieldAtDepthOne(text, ":args")).isEqualTo(FieldValue.ListContent("a b c"))
    }

    @Test
    fun `a bare value reads as a Token`() {
      val text = "{QUERY :resjunk true :other 2}"
      assertThat(scanner.fieldAtDepthOne(text, ":resjunk")).isEqualTo(FieldValue.Token("true"))
    }
  }

  @Nested
  inner class AbsentCases {

    @Test
    fun `an empty-in-node-tree value of angle brackets is Absent`() {
      val text = "{QUERY :args <> :other 2}"
      assertThat(scanner.fieldAtDepthOne(text, ":args")).isEqualTo(FieldValue.Absent)
    }

    @Test
    fun `a field that never appears is Absent`() {
      val text = "{QUERY :other 2}"
      assertThat(scanner.fieldAtDepthOne(text, ":args")).isEqualTo(FieldValue.Absent)
    }

    @Test
    fun `a marker whose match completes exactly at the end of the text is Absent`() {
      val text = "{QUERY :args "
      assertThat(scanner.fieldAtDepthOne(text, ":args")).isEqualTo(FieldValue.Absent)
    }

    @Test
    fun `an unbalanced brace value is Absent`() {
      val text = "{QUERY :expr {VAR :varno 1 :other 2"
      assertThat(scanner.fieldAtDepthOne(text, ":expr")).isEqualTo(FieldValue.Absent)
    }

    @Test
    fun `an unbalanced parenthesis value is Absent`() {
      val text = "{QUERY :args (a b c"
      assertThat(scanner.fieldAtDepthOne(text, ":args")).isEqualTo(FieldValue.Absent)
    }
  }

  @Nested
  inner class ProjectionsRejectTheWrongArm {

    @Test
    fun `blockAtDepthOne returns null when the value is a list`() {
      val text = "{QUERY :args (a b c)}"
      assertThat(scanner.blockAtDepthOne(text, ":args")).isNull()
    }

    @Test
    fun `blockAtDepthOne returns null when the value is a token`() {
      val text = "{QUERY :resjunk true}"
      assertThat(scanner.blockAtDepthOne(text, ":resjunk")).isNull()
    }

    @Test
    fun `listAtDepthOne returns null when the value is a block`() {
      val text = "{QUERY :expr {VAR :varno 1}}"
      assertThat(scanner.listAtDepthOne(text, ":expr")).isNull()
    }

    @Test
    fun `rawListAtDepthOne returns null when the value is a block`() {
      val text = "{QUERY :expr {VAR :varno 1}}"
      assertThat(scanner.rawListAtDepthOne(text, ":expr")).isNull()
    }

    @Test
    fun `boolAtDepthOne returns null when the value is a block`() {
      val text = "{QUERY :expr {VAR :varno 1}}"
      assertThat(scanner.boolAtDepthOne(text, ":expr")).isNull()
    }

    @Test
    fun `boolAtDepthOne returns null when the value is a list`() {
      val text = "{QUERY :args (a b c)}"
      assertThat(scanner.boolAtDepthOne(text, ":args")).isNull()
    }
  }

  @Nested
  inner class BlankListSemantics {

    @Test
    fun `rawListAtDepthOne returns the empty string for a blank list`() {
      val text = "{QUERY :groupingSets ()}"
      assertThat(scanner.rawListAtDepthOne(text, ":groupingSets")).isEqualTo("")
    }

    @Test
    fun `listAtDepthOne returns null for a blank list`() {
      val text = "{QUERY :args ()}"
      assertThat(scanner.listAtDepthOne(text, ":args")).isNull()
    }

    @Test
    fun `rawListAtDepthOne returns the interior for a non-blank list`() {
      val text = "{QUERY :groupingSets ({GROUPINGSET :kind 1})}"
      assertThat(scanner.rawListAtDepthOne(text, ":groupingSets")).isEqualTo("{GROUPINGSET :kind 1}")
    }

    @Test
    fun `listAtDepthOne returns the interior for a non-blank list`() {
      val text = "{QUERY :args ({VAR :varno 1})}"
      assertThat(scanner.listAtDepthOne(text, ":args")).isEqualTo("{VAR :varno 1}")
    }
  }

  @Nested
  inner class BoolProjection {

    @Test
    fun `a true token reads as true`() {
      val text = "{QUERY :cterecursive true}"
      assertThat(scanner.boolAtDepthOne(text, ":cterecursive")).isEqualTo(true)
    }

    @Test
    fun `a false token reads as false`() {
      val text = "{QUERY :cterecursive false}"
      assertThat(scanner.boolAtDepthOne(text, ":cterecursive")).isEqualTo(false)
    }

    @Test
    fun `an unrelated token reads as null`() {
      val text = "{QUERY :aggkind n}"
      assertThat(scanner.boolAtDepthOne(text, ":aggkind")).isNull()
    }

    @Test
    fun `a token that merely starts with true reads as null, not true`() {
      val text = "{QUERY :cterecursive trueish}"
      assertThat(scanner.boolAtDepthOne(text, ":cterecursive")).isNull()
    }
  }

  @Nested
  inner class NestedSameNamedFieldDoesNotShadow {

    @Test
    fun `an outer field of angle brackets is not shadowed by a nested list of the same name`() {
      // Mirrors a JSONCONSTRUCTOREXPR with an empty :args <> whose :func holds an AGGREF with a
      // real :args (...) — a naive text.indexOf(":args (") would find the nested list instead.
      val text = "{OUTER :args <> :func {INNER :args (x y)}}"
      assertThat(scanner.fieldAtDepthOne(text, ":args")).isEqualTo(FieldValue.Absent)
    }

    @Test
    fun `an outer block field is not shadowed by a nested block of the same name`() {
      // Mirrors a SUBLINK's :testexpr containing a nested SUBLINK with its own :testexpr.
      val text = "{OUTER :testexpr {INNER :testexpr {DEEPEST :location -1} :location -1} :other 2}"
      val expected = "{INNER :testexpr {DEEPEST :location -1} :location -1}"
      assertThat(scanner.fieldAtDepthOne(text, ":testexpr")).isEqualTo(FieldValue.Block(expected))
    }
  }

  @Nested
  inner class EscapedDelimitersInAPrecedingValue {

    @Test
    fun `an escaped opening brace in a preceding value does not perturb block extraction`() {
      val text = "{QUERY :resname k\\{x :expr {VAR :varno 1}}"
      assertThat(scanner.fieldAtDepthOne(text, ":expr")).isEqualTo(FieldValue.Block("{VAR :varno 1}"))
    }

    @Test
    fun `an escaped opening parenthesis in a preceding value does not perturb list extraction`() {
      val text = "{QUERY :ctename k\\(x :args (a b)}"
      assertThat(scanner.fieldAtDepthOne(text, ":args")).isEqualTo(FieldValue.ListContent("a b"))
    }
  }

  @Nested
  inner class TokenTermination {

    @Test
    fun `a token ends at unescaped whitespace`() {
      val text = "{QUERY :resjunk true :other 2}"
      assertThat(scanner.fieldAtDepthOne(text, ":resjunk")).isEqualTo(FieldValue.Token("true"))
    }

    @Test
    fun `a token ends at a closing brace`() {
      val text = "{QUERY :aggkind n}"
      assertThat(scanner.fieldAtDepthOne(text, ":aggkind")).isEqualTo(FieldValue.Token("n"))
    }

    @Test
    fun `a token ends at a closing parenthesis`() {
      val text = "{QUERY :aggkind n)}"
      assertThat(scanner.fieldAtDepthOne(text, ":aggkind")).isEqualTo(FieldValue.Token("n"))
    }

    @Test
    fun `a token runs to the end of the text`() {
      val text = "{QUERY :aggkind n"
      assertThat(scanner.fieldAtDepthOne(text, ":aggkind")).isEqualTo(FieldValue.Token("n"))
    }
  }
}
