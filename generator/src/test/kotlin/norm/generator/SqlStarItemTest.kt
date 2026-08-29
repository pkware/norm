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
      // stripCommentsAndWhitespace deletes the separating space before this function ever runs, so
      // without gating the identifier-continuation run on OriginalAdjacency, "description dx" strips
      // to "descriptiondx" and reads as one fused segment spanning the whole text --
      // findTrailingImplicitAliasStart's own "a segment spanning the entire text is not an alias"
      // guard then refuses to report any split at all, so this position is left with no verifiable
      // name and fails NodeTreeProvenanceExpression's all-positions cross-validation gate for the
      // whole body, not merely this one position.
      val result = splitTrailingImplicitAlias("description dx")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("description", "dx"))
    }

    @Test
    fun `a computed expression with an implicit alias still splits correctly`() {
      // Non-adversarial control: proves the adjacency gate did not regress the shape this function
      // already supported, where a ")" already ends the expression's own run before the alias.
      val result = splitTrailingImplicitAlias("UPPER(a) y")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("UPPER(a)", "y"))
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
      assertThat(result).isEqualTo(ItemAndImplicitAlias("a\$b", "dx"))
    }
  }
}
