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
      // it was not genuinely adjacent to the identifier character before it -- a real adjacency
      // (never separated by whitespace/comment in the original text) must keep continuing the run
      // exactly as before.
      val result = splitTrailingImplicitAlias("a\$b dx")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("a\$b", "dx"))
    }

    @Test
    fun `a doubled-quote escape inside an implicit quoted alias is not mistaken for its closing quote`() {
      val result = splitTrailingImplicitAlias("description \"He\"\"llo\"")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("description", "\"He\"\"llo\""))
    }

    @Test
    fun `two identifiers only adjacent after stripping are not fused into one alias segment`() {
      // "col" and "a" and "b" are three separate words in the original text, but stripping the
      // spaces between them makes "a" and "b" sit right next to each other in the stripped text.
      // matchTrailingAliasSegment's continuation loop must still stop after "a", not fuse it with
      // "b" into "ab" -- so only "b" (the last segment reaching the end) is the implicit alias.
      val result = splitTrailingImplicitAlias("col a b")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("col a", "b"))
    }

    @Test
    fun `two separately quoted identifiers separated only by a stripped whitespace are not fused into one token`() {
      // Stripping the space between "a" and "b" leaves the stripped text "a""b" -- exactly what a
      // single escaped quoted identifier ("a\"b") looks like. skipDoubleQuotedIdentifier's own
      // adjacency gate on the "" doubled-quote check keeps this from being misread as one fused
      // token, so "a" and "b" stay two separate segments and only "b" is the implicit alias.
      val result = splitTrailingImplicitAlias("description \"a\" \"b\"")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("description \"a\"", "\"b\""))
    }

    @Test
    fun `a Unicode-escape identifier as an implicit alias is still recognized as the trailing segment`() {
      val result = splitTrailingImplicitAlias("description U&\"x\"")
      assertThat(result).isEqualTo(ItemAndImplicitAlias("description", "U&\"x\""))
    }
  }
}
