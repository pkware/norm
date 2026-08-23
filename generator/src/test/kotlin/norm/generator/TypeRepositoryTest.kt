package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [TypeRepository.buildTypeProjectionForQuery]'s generated class-level KDoc, in
 * particular the `@property` source-reference line a computed expression (one with no source
 * table and no [SelectItem.columnName]) gets via `PropertySource.sourceReference()`.
 */
class TypeRepositoryTest {

  /**
   * Regression coverage for the guard added at [TypeRepository.buildTypeProjectionForQuery]'s
   * `parseSelectItems` call site: [parseSelectItems] has no independent way to confirm its own
   * item count against the real result column count (see its KDoc), so a spelling it
   * mis-recognizes -- most concretely, an unrecognized star item that expands to several real
   * columns -- degrades SILENTLY to a shifted, wrong mapping of names/expressions onto columns
   * they don't belong to, rather than the documented empty-list fail-safe, unless the caller
   * cross-checks the count itself.
   *
   * The mismatch here is constructed directly at [TypeRepository.buildTypeProjectionForQuery]'s
   * own seam (a [queryText] whose select-item count disagrees with the passed `queryResults`)
   * rather than via a real unrecognized-star SQL input, since [TypeRepository] has no dependency
   * on [java.sql.ResultSetMetaData] to construct such a case end-to-end.
   */
  @Nested
  inner class SelectItemColumnCountMismatchGuard {

    @Test
    fun `a select-item count mismatch falls back to no expression attribution, not a shifted one`() {
      // 3 select items, all computed expressions with distinct, individually-recognizable text.
      val queryText = "SELECT LENGTH(a) AS a_len, UPPER(b) AS b_upper, LOWER(c) AS c_lower FROM t"

      // Only 2 REAL result columns -- simulates the exact shape of the bug: parseSelectItems'
      // item count (3) disagrees with the real column count (2, what queryResults.size stands in
      // for here). Without the guard, the second queryResult ("c_lower", conceptually LOWER(c))
      // would incorrectly borrow selectItems[1] -- UPPER(b), a DIFFERENT expression entirely.
      val queryResults = listOf(
        Column(name = "a_len", notNull = true, type = Identifier(name = "int4")),
        Column(name = "c_lower", notNull = true, type = Identifier(name = "text")),
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("computeThings", queryResults, queryText)

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      // The raw SQL always appears verbatim in a fenced code block regardless of attribution, so
      // asserting on "@property" lines specifically (not bare substring presence of the SQL text)
      // is what actually distinguishes the fail-safe from the wrong, shifted mapping.
      assertThat(kdoc).doesNotContain("@property c_lower (`UPPER(b)`)")
      assertThat(kdoc).doesNotContain("@property c_lower")
      // The fail-safe treats the WHOLE mapping as unreliable once the counts disagree -- not just
      // the specific item that would otherwise be shifted -- so a_len loses its (individually
      // correct) attribution too, exactly like oldOrNewReturningColumns' callers, which fall back
      // to forcing every column rather than only the ones they can specifically identify as risky.
      assertThat(kdoc).doesNotContain("@property a_len")
    }
  }

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

  @Nested
  inner class CteWrappedExpressionSourceReference {

    @Test
    fun `a CTE-wrapped expression column gets a source-reference KDoc line resolved through the CTE body`() {
      // Regression test for issue #229: a result column that is BOTH CTE-wrapped AND
      // expression-derived previously got no @property line at all. The outer select item
      // (`description_upper`, a plain column reference into the CTE) makes isComputedExpression
      // false, so this only passes if TypeRepository also tries resolveCteOutputExpression.
      val queryText = """
        WITH deleted_parent AS (
          DELETE FROM parent WHERE id = ? RETURNING UPPER(description) AS description_upper
        )
        SELECT description_upper FROM deleted_parent
      """.trimIndent()
      val expressionColumn = Column(name = "description_upper", notNull = false, type = Identifier(name = "text"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery(
        "deleteParentReturningDescriptionUpperViaCte",
        listOf(expressionColumn),
        queryText,
      )

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property description_upper (`UPPER(description)`)")
    }

    @Test
    fun `a CTE-wrapped plain pass-through column gets no spurious source-reference KDoc line`() {
      // Contrast case: a chained pass-through in the CTE body must not echo its own column name
      // back as a fake expression.
      val queryText = """
        WITH deleted_parent AS (
          SELECT name FROM parent
        )
        SELECT name FROM deleted_parent
      """.trimIndent()
      val passThroughColumn = Column(name = "name", notNull = true, type = Identifier(name = "text"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("listDeletedParentNamesViaCte", listOf(passThroughColumn), queryText)

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).isEqualTo("```sql\n$queryText\n```")
    }
  }

  @Nested
  inner class PropertyNameNeedingBackticks {

    @Test
    fun `a property name containing a space is backtick-quoted in its own property tag`() {
      // A quoted SQL column name (e.g. "My Col") becomes a Kotlin property that itself needs
      // backticks to declare (`My Col`). Without the same escaping in the KDoc `@property` tag,
      // "@property My Col Some comment." reads as a property literally named "My", with
      // "Col Some comment." swallowed into the description -- not the intended two-token name.
      val spacedColumn = Column(
        name = "My Col",
        notNull = true,
        type = Identifier(name = "text"),
        comment = "Column name containing a space.",
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("getMyCol", listOf(spacedColumn), "SELECT \"My Col\" FROM t")

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property `My Col` Column name containing a space.")
      assertThat(kdoc).doesNotContain("@property My Col ")
    }
  }
}
