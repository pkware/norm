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

    @Test
    fun `an implicitly aliased top-level computed expression emits its complete text, alias included`() {
      // Whether a trailing bare word is an alias or the expression's own last operand cannot be
      // decided from text alone without a grammar (`SELECT a IS NOT NULL` would truncate to the
      // unparseable `a IS NOT`, `SELECT INTERVAL '1' DAY` would truncate to `INTERVAL '1'`, which
      // evaluates to a different value). So the top-level path never guesses: an item with no
      // explicit `AS` is embedded in its entire, exactly-as-written form, alias or operand included.
      val countColumn = Column(name = "c", notNull = true, type = Identifier(name = "int8"))
      val maxColumn = Column(name = "m", notNull = false, type = Identifier(name = "text"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery(
        "countAndMax",
        listOf(countColumn, maxColumn),
        "SELECT count(*) c, max(name) m FROM t",
      )

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property c (`count(*) c`)")
      assertThat(kdoc).contains("@property m (`max(name) m`)")
    }
  }

  @Nested
  inner class SetOperationComputedExpression {

    @Test
    fun `a computed expression under a top-level UNION gets no source-reference KDoc line`() {
      // "SELECT UPPER(x) AS u FROM t UNION SELECT LOWER(x) FROM t" -- parseSelectItems only ever
      // sees the first branch (it has no concept of set operations at all), so isComputedExpression
      // must not fire on the first branch's own item and document "UPPER(x)" as if that were the
      // query's one true expression, when branch 2 actually computes LOWER(x) for the same column.
      // One branch presented as the whole answer is a wrong emission; there is no per-branch text
      // to attribute a single property to, so this must emit nothing rather than
      // guess branch 1's text.
      val queryText = "SELECT UPPER(x) AS u FROM t UNION SELECT LOWER(x) FROM t"
      val unionColumn = Column(name = "u", notNull = true, type = Identifier(name = "text"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("upperOrLower", listOf(unionColumn), queryText)

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      // The raw SQL always appears verbatim in a fenced code block regardless of attribution (see
      // SelectItemColumnCountMismatchGuard's own test above), so "UPPER(x)" alone would trivially
      // still be present there -- asserting on the "@property" line specifically is what actually
      // distinguishes "no attribution" from "wrongly attributed to branch 1".
      assertThat(kdoc).doesNotContain("@property u")
    }

    @Test
    fun `a computed expression under a parenthesized top-level UNION gets no source-reference KDoc line`() {
      // hasTopLevelSetOperation used to inspect the raw main-query window while
      // parseOutputItemsWithAlias (via parseSelectItems) inspected the same window with redundant
      // outer parentheses stripped. Wrapping the whole set operation in one parenthesis pair sinks
      // the UNION to paren depth 1 in the raw window, so the guard missed it -- while the parser,
      // seeing the parentheses stripped, still split the (still branch-1-only) items and let
      // isComputedExpression fire on "UPPER(x)" as if it were the whole answer.
      val queryText = "(SELECT UPPER(x) AS u FROM t UNION SELECT LOWER(x) FROM t)"
      val unionColumn = Column(name = "u", notNull = true, type = Identifier(name = "text"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("upperOrLowerParenthesized", listOf(unionColumn), queryText)

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).doesNotContain("@property u")
    }

    @Test
    fun `a plain column reference under a top-level UNION still gets no source-reference KDoc line`() {
      // Contrast case, pinning existing accepted behavior: PostgreSQL itself names a set operation's
      // whole result column after branch 1 alone, so echoing a bare column reference is not
      // misleading the way a computed expression is -- this must keep behaving exactly as an
      // ordinary bare column reference already does (no source-reference line either way), NOT
      // regress to something new merely because a set operation is present.
      val queryText = "SELECT a FROM x UNION SELECT b FROM y"
      val plainColumn = Column(name = "a", notNull = true, type = Identifier(name = "int4"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("aOrB", listOf(plainColumn), queryText)

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).doesNotContain("@property a")
    }
  }

  @Nested
  inner class CteWrappedExpressionSourceReference {

    @Test
    fun `a CTE-wrapped expression column gets a source-reference KDoc line resolved through the CTE body`() {
      // Regression test for issue #229: a result column that is BOTH CTE-wrapped AND
      // expression-derived previously got no @property line at all. The outer select item
      // (`description_upper`, a plain column reference into the CTE) makes isComputedExpression
      // false, so this only passes if TypeRepository also reads column.provenanceExpression —
      // populated here directly, standing in for what ColumnNullabilityAnalyzer's own node-tree
      // resolution (NodeTreeProvenanceResolver + resolveNodeTreeProvenanceExpression) would have
      // computed during analysis; TypeRepository itself no longer does any SQL-text CTE resolution
      // of its own.
      val queryText = """
        WITH deleted_parent AS (
          DELETE FROM parent WHERE id = ? RETURNING UPPER(description) AS description_upper
        )
        SELECT description_upper FROM deleted_parent
      """.trimIndent()
      val expressionColumn = Column(
        name = "description_upper",
        notNull = false,
        type = Identifier(name = "text"),
        provenanceExpression = "UPPER(description)",
      )

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
      // Contrast case: a chained pass-through in the CTE body has no provenanceExpression at all
      // (the default `null`) — TypeRepository must not echo its own column name back as a fake
      // expression.
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
  inner class ComputedExpressionCommentAndWhitespaceStripping {

    @Test
    fun `a comment inside a top-level computed expression is stripped, not embedded verbatim`() {
      // The top-level path (isComputedExpression == true) must not emit selectItem.expression raw,
      // applying neither stripComments nor collapseCosmeticWhitespace -- unlike the CTE path
      // (resolveNodeTreeProvenanceExpression), which applies both. A developer who read the KDoc
      // and pasted the rendered text back into psql would get "a + -- note\n  b", where the line
      // comment swallows the rest of its own line -- including the "b" operand -- so PostgreSQL
      // rejects it with "syntax error at end of input".
      val queryText = "SELECT a + -- note\n  b AS sum2 FROM t"
      val sumColumn = Column(name = "sum2", notNull = true, type = Identifier(name = "int4"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("sumWithComment", listOf(sumColumn), queryText)

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property sum2 (`a + b`)")
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

  @Nested
  inner class TableColumnSourceReferenceQuoting {

    @Test
    fun `a mixed-case original column name is double-quoted in its table_column source reference`() {
      // Rendering the bare, unquoted originalName ("tq.Foo") reads back as PostgreSQL folding "Foo"
      // to "foo" -- a column "tq" never has ("SELECT tq.Foo FROM tq" fails with "column tq.foo does
      // not exist", while "SELECT tq.\"Foo\" FROM tq" succeeds).
      val quotedColumn = Column(
        name = "bar",
        notNull = true,
        type = Identifier(name = "text"),
        table = Identifier(name = "tq"),
        originalName = "Foo",
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("getBar", listOf(quotedColumn), "SELECT \"Foo\" AS bar FROM tq")

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property bar (`tq.\"Foo\"`)")
      assertThat(kdoc).doesNotContain("`tq.Foo`")
    }

    @Test
    fun `a space-containing original column name is double-quoted in its table_column source reference`() {
      val spacedColumn = Column(
        name = "myCol",
        notNull = true,
        type = Identifier(name = "text"),
        table = Identifier(name = "tq"),
        originalName = "My Col",
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("getMyCol", listOf(spacedColumn), "SELECT \"My Col\" FROM tq")

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property myCol (`tq.\"My Col\"`)")
      assertThat(kdoc).doesNotContain("`tq.My Col`")
    }

    @Test
    fun `a plain lowercase original column name is rendered bare, unquoted`() {
      // Contrast case: a normal identifier must NOT gain spurious quotes.
      val plainColumn = Column(
        name = "id",
        notNull = true,
        type = Identifier(name = "int4"),
        table = Identifier(name = "tq"),
        originalName = "id",
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("getId", listOf(plainColumn), "SELECT id FROM tq")

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property id (`tq.id`)")
    }
  }

  @Nested
  inner class ReservedWordSourceReferenceQuoting {

    @Test
    fun `a reserved-word relation name is double-quoted in its table_column source reference`() {
      // SQL_UNQUOTED_IDENTIFIER alone accepts "order" -- it's already all-lowercase with no special
      // characters -- so without consulting the live server's own reserved-word set, the relation
      // name would be rendered bare: "order.id" reads back as PostgreSQL parsing "order" as the
      // reserved keyword, not a table reference (`SELECT order.id FROM "order"` fails with `syntax
      // error at or near "."`).
      val orderColumn = Column(
        name = "id",
        notNull = true,
        type = Identifier(name = "int4"),
        table = Identifier(name = "order"),
        originalName = "id",
      )

      val repository = TypeRepository("test", Catalog(), reservedWords = setOf("order"))
      repository.buildTypeProjectionForQuery("getId", listOf(orderColumn), "SELECT id FROM \"order\"")

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property id (`\"order\".id`)")
      assertThat(kdoc).doesNotContain("`order.id`")
    }

    @Test
    fun `a reserved-word column name is double-quoted in its table_column source reference`() {
      val userColumn = Column(
        name = "name",
        notNull = true,
        type = Identifier(name = "text"),
        table = Identifier(name = "author"),
        originalName = "user",
      )

      val repository = TypeRepository("test", Catalog(), reservedWords = setOf("user"))
      repository.buildTypeProjectionForQuery("getName", listOf(userColumn), "SELECT \"user\" FROM author")

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property name (`author.\"user\"`)")
      assertThat(kdoc).doesNotContain("`author.user`")
    }

    @Test
    fun `an empty reserved-word set (the default) leaves an ordinary identifier bare, unquoted`() {
      // Contrast case: TypeRepository's own default (emptySet()) must not spuriously quote every
      // all-lowercase identifier -- only the connected server's OWN reported reserved words.
      val plainColumn = Column(
        name = "id",
        notNull = true,
        type = Identifier(name = "int4"),
        table = Identifier(name = "author"),
        originalName = "id",
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("getId", listOf(plainColumn), "SELECT id FROM author")

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property id (`author.id`)")
    }
  }

  @Nested
  inner class StarSelectItemNeverBecomesAnExpression {

    @Test
    fun `a lone star select item over a CTE reports the CTE body's own resolved expression`() {
      // "WITH c AS (SELECT UPPER(s) AS u FROM t) SELECT * FROM c" -- parseSelectItems returns the
      // single star item ("*") as-is rather than the empty-list fail-safe it uses for a star
      // followed by more items (see parseOutputItemsWithAlias's own KDoc), so
      // selectItem.columnName == null for it exactly as for a genuine computed expression.
      // isComputedExpression must not fire on the star's own literal text and document "*" as if it
      // were the expression that produced the column -- even though provenanceExpression (stood
      // in for here, as elsewhere in this file, for what the node-tree resolver would have
      // computed) already held the real answer.
      val queryText = "WITH c AS (SELECT UPPER(s) AS u FROM t) SELECT * FROM c"
      val starColumn = Column(
        name = "u",
        notNull = false,
        type = Identifier(name = "text"),
        provenanceExpression = "UPPER(s)",
      )

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery("starOverCte", listOf(starColumn), queryText)

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).contains("@property u (`UPPER(s)`)")
      assertThat(kdoc).doesNotContain("(`*`)")
    }

    @Test
    fun `a lone star select item with no resolved expression gets no source-reference KDoc line at all`() {
      // Contrast case: "SELECT * FROM generate_series(1,3)" -- a single-column, non-table source
      // with nothing for the node-tree resolver to resolve (provenanceExpression stays null).
      // Before this fix this still emitted "(`*`)"; the star must never be the fallback answer.
      val starColumn = Column(name = "generate_series", notNull = true, type = Identifier(name = "int4"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery(
        "generateSeriesStar",
        listOf(starColumn),
        "SELECT * FROM generate_series(1,3)",
      )

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).doesNotContain("@property generate_series")
    }

    @Test
    fun `a qualified star select item (c_star) with no resolved expression gets no source-reference KDoc line`() {
      // "SELECT c.* FROM (SELECT UPPER(s) AS u FROM t) c" -- the qualified-star spelling of the
      // same hazard.
      val starColumn = Column(name = "u", notNull = false, type = Identifier(name = "text"))

      val repository = TypeRepository("test", Catalog())
      repository.buildTypeProjectionForQuery(
        "qualifiedStar",
        listOf(starColumn),
        "SELECT c.* FROM (SELECT UPPER(s) AS u FROM t) c",
      )

      val kdoc = repository.requiredTypes.first().kdoc.toString()
      assertThat(kdoc).doesNotContain("@property u")
    }
  }
}
