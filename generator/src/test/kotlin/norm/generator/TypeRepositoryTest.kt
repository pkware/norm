package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [TypeRepository.buildTypeProjectionForQuery]'s generated class-level KDoc, in
 * particular the `@property` source-reference line a computed expression (one with no source
 * table and no [SelectItem.columnName]) gets via `PropertySource.sourceReference()`.
 */
class TypeRepositoryTest {

  @Nested
  inner class ComputedExpressionSourceReference {

    @Test
    fun `a bare numeric literal select item gets a source-reference KDoc line, not silence`() {
      // Regression test for a behavior change from the COLUMN_REFERENCE identifier-start fix:
      // main's COLUMN_REFERENCE regex was a bare "\w+", which matches a digit-leading run too, so
      // parseSelectItems("SELECT 5") wrongly resolved the literal "5" as if it were a column named
      // "5" (columnName = "5"). That made isComputedExpression (TypeRepository.kt) false, so
      // PropertySource.expression was never populated, and sourceReference() returned null for
      // this property entirely -- the KDoc carried NO reference to the literal's origin.
      //
      // With COLUMN_REFERENCE's leading-character restricted to a legal identifier start (no
      // digit, no "$"), "5" no longer matches as a column reference: columnName is correctly
      // null, isComputedExpression is true, and sourceReference() now renders "`5`" -- this is
      // the desired, MORE correct behavior (see COLUMN_REFERENCE's own KDoc), but nothing
      // previously exercised the downstream KDoc-generation effect of that change.
      val repository = TypeRepository("test", Catalog())
      val literalColumn = Column(name = "column1", notNull = true, type = Identifier(name = "int4"))

      repository.buildTypeProjectionForQuery("getFive", listOf(literalColumn), "SELECT 5")

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("`5`")
    }

    @Test
    fun `a simple column reference select item gets no source-reference KDoc line`() {
      // Contrast case: a genuine column reference (not a computed expression) still gets no
      // source-reference KDoc line at all -- echoing the column's own name back adds no value
      // (see buildTypeProjectionForQuery's own comment). Guards against a future change making
      // isComputedExpression fire for every property regardless of columnName.
      val simpleColumn = Column(name = "id", notNull = true, type = Identifier(name = "int4"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("getId", listOf(simpleColumn), "SELECT id FROM t")

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).doesNotContain("`id`")
    }
  }
}
