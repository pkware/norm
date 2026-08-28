package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlStarItemTest {

  @Nested
  inner class SplitTrailingImplicitAlias {

    @Test
    fun `a bare column with an implicit alias splits into the column and its own alias`() {
      // #238 P2: stripCommentsAndWhitespace deletes the separating space before this function ever
      // runs, so without gating the identifier-continuation run on OriginalAdjacency, "description
      // dx" strips to "descriptiondx" and reads as ONE fused segment spanning the whole text --
      // findTrailingImplicitAliasStart's own "a segment spanning the ENTIRE text is not an alias"
      // guard then refuses to report any split at all, so this position is left with no verifiable
      // name and fails NodeTreeProvenanceExpression's all-positions cross-validation gate for the
      // WHOLE body, not merely this one position.
      val result = splitTrailingImplicitAlias("description dx")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("description", "dx", precededByWhitespace = true))
    }

    @Test
    fun `a computed expression with an implicit alias still splits correctly`() {
      // Non-adversarial control: proves the adjacency gate did not regress the shape this function
      // already supported, where a ")" already ends the expression's own run before the alias.
      val result = splitTrailingImplicitAlias("UPPER(a) y")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("UPPER(a)", "y", precededByWhitespace = true))
    }

    @Test
    fun `a bare column with no alias at all has no trailing segment to split off`() {
      val result = splitTrailingImplicitAlias("description")
      assertThat(result).isNull()
    }

    @Test
    fun `a dollar-quoted string's own opening dollar sign is not mistaken for an identifier boundary`() {
      // Regression guard for the adjacency gate this generalizes: "$" still only breaks a run when
      // it was NOT genuinely adjacent to the identifier character before it -- a real adjacency
      // (never separated by whitespace/comment in the original text) must keep continuing the run
      // exactly as before.
      val result = splitTrailingImplicitAlias("a\$b dx")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("a\$b", "dx", precededByWhitespace = true))
    }

    @Test
    fun `an identifier glued directly onto a cast operator is flagged as NOT genuinely separated`() {
      // #238 P2: "t.*::text" -- "text" is the cast's own mandatory type name, glued directly onto
      // "::" with no separator at all, never a real alias. precededByWhitespace is what
      // topLevelImplicitAlias uses to reject exactly this shape, since the split itself cannot tell
      // a real alias apart from a syntactically required trailing identifier.
      val result = splitTrailingImplicitAlias("t.*::text")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("t.*::", "text", precededByWhitespace = false))
    }

    @Test
    fun `an identifier separated only by a comment is flagged as NOT genuinely separated by whitespace`() {
      // A comment is not whitespace -- it stays attached to the expression (stripComments' own job
      // to remove it later), so precededByWhitespace is false even though a real lexical separator
      // (the comment) exists here.
      val result = splitTrailingImplicitAlias("UPPER(a)/*c*/y")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("UPPER(a)/*c*/", "y", precededByWhitespace = false))
    }
  }
}
