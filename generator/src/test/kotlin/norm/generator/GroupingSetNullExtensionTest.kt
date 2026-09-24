package norm.generator

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [GroupingSetNullExtension.isSafeFromGroupingSetNullExtension] against
 * directly-constructed [PgNodeExpression] values.
 */
class GroupingSetNullExtensionTest {

  private fun groupingSetNullExtension(
    isAlwaysNonNull: (Int) -> Boolean = { false },
    isNonNullIffFirstArgumentNonNull: (Int) -> Boolean = { false },
    isFoldableToConst: (Int) -> Boolean = { false },
  ) = GroupingSetNullExtension(
    isAlwaysNonNull = isAlwaysNonNull,
    isNonNullIffFirstArgumentNonNull = isNonNullIffFirstArgumentNonNull,
    isFoldableToConst = isFoldableToConst,
  )

  /**
   * Unit tests for the rules by which [GroupingSetNullExtension.isSafeFromGroupingSetNullExtension]
   * proves an expression safe without an `Aggref`/`GroupingFunc`/`WindowFunc` descendant, which the
   * generic aggregate/window-domination rule alone would reject, plus controls those rules must not
   * rescue.
   */
  @Nested
  inner class GroupingSetNullExtensionLegA {

    private val someFunctionOid = 200
    private val varArgument = PgNodeExpression.Var(varno = 1, varattno = 1, nullingRelations = emptySet())

    @Test
    fun `self-match guard makes a grouping key itself unsafe even though it would otherwise qualify for a leg`() {
      // Without the self-match guard, a FuncExpr on isAlwaysNonNull's list would be rescued by that
      // leg even when the call itself is the grouping key being null-extended wholesale.
      val key = PgNodeExpression.FuncExpr(functionOid = someFunctionOid, arguments = listOf(varArgument))
      val safe = groupingSetNullExtension(isAlwaysNonNull = { it == someFunctionOid })
        .isSafeFromGroupingSetNullExtension(key, groupingKeyExpressions = setOf(key))
      assertThat(safe).isFalse()
    }

    @Test
    fun `a non-VARIADIC FuncExpr with isNonNullIffFirstArgumentNonNull is safe when its first argument is safe`() {
      // Models concat_ws(',', a, b): the later arguments (here, two bare Vars) do not matter to
      // this leg at all — only the first (separator) argument's own safety does.
      val expression = PgNodeExpression.FuncExpr(
        functionOid = someFunctionOid,
        arguments = listOf(PgNodeExpression.Const(isNull = false), varArgument, varArgument),
      )
      val safe = groupingSetNullExtension(isNonNullIffFirstArgumentNonNull = { it == someFunctionOid })
        .isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isTrue()
    }

    @Test
    fun `a non-VARIADIC FuncExpr with isNonNullIffFirstArgumentNonNull is unsafe when its first argument is the key`() {
      // Models concat_ws(concat(a, b), 'x', 'y') under a key of concat(a, b): the separator is
      // itself null-extendable, so the leg must recurse into it rather than assume any first
      // argument is automatically safe.
      val separator = PgNodeExpression.FuncExpr(functionOid = 999, arguments = listOf(varArgument))
      val expression = PgNodeExpression.FuncExpr(
        functionOid = someFunctionOid,
        arguments = listOf(separator, PgNodeExpression.Const(isNull = false)),
      )
      val safe = groupingSetNullExtension(isNonNullIffFirstArgumentNonNull = { it == someFunctionOid })
        .isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = setOf(separator))
      assertThat(safe).isFalse()
    }

    @Test
    fun `a VARIADIC FuncExpr with isNonNullIffFirstArgumentNonNull ignores this leg even for a safe first argument`() {
      // Models concat_ws(',', VARIADIC arr) under GROUP BY ROLLUP(arr): in the VARIADIC form, the
      // array itself (the last argument, not the first) is a single value that can be
      // null-extended, and concat_ws(',', VARIADIC arr) really is NULL when arr is NULL, regardless
      // of the separator. Without the isVariadic guard, this leg would wrongly treat the safe
      // literal separator as proof the whole call is safe.
      val arrayArgument = PgNodeExpression.Var(varno = 2, varattno = 1, nullingRelations = emptySet())
      val expression = PgNodeExpression.FuncExpr(
        functionOid = someFunctionOid,
        arguments = listOf(PgNodeExpression.Const(isNull = false), arrayArgument),
        isVariadic = true,
      )
      val safe = groupingSetNullExtension(isNonNullIffFirstArgumentNonNull = { it == someFunctionOid })
        .isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = setOf(arrayArgument))
      assertThat(safe).isFalse()
    }

    @Test
    fun `XmlExpr XML_IS_XMLELEMENT is unconditionally safe regardless of its arguments`() {
      val expression =
        PgNodeExpression.XmlExpr(op = PgNodeExpression.XML_IS_XMLELEMENT, arguments = listOf(varArgument))
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isTrue()
    }

    @Test
    fun `XmlExpr XML_IS_XMLFOREST is NOT covered by this leg — a null field can null the whole forest`() {
      val expression =
        PgNodeExpression.XmlExpr(op = PgNodeExpression.XML_IS_XMLFOREST, arguments = listOf(varArgument))
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isFalse()
    }

    @Test
    fun `XmlExpr XML_IS_XMLPI is NOT covered by this leg — a null content expression nulls the result`() {
      val expression = PgNodeExpression.XmlExpr(op = PgNodeExpression.XML_IS_XMLPI, arguments = listOf(varArgument))
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isFalse()
    }

    @Test
    fun `XmlExpr XML_IS_XMLCONCAT is not covered by this leg and falls through to the generic rule`() {
      val expression =
        PgNodeExpression.XmlExpr(op = PgNodeExpression.XML_IS_XMLCONCAT, arguments = listOf(varArgument))
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isFalse()
    }

    @Test
    fun `JsonConstructorExpr OBJECT is unconditionally safe regardless of its arguments`() {
      val expression = PgNodeExpression.JsonConstructorExpr(
        type = PgNodeExpression.JSON_CONSTRUCTOR_TYPE_OBJECT,
        arguments = listOf(varArgument),
      )
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isTrue()
    }

    @Test
    fun `JsonConstructorExpr ARRAY is unconditionally safe regardless of its arguments`() {
      val expression = PgNodeExpression.JsonConstructorExpr(
        type = PgNodeExpression.JSON_CONSTRUCTOR_TYPE_ARRAY,
        arguments = listOf(varArgument),
      )
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isTrue()
    }

    @Test
    fun `JsonConstructorExpr SCALAR is not covered by this leg and depends on its argument`() {
      val expression = PgNodeExpression.JsonConstructorExpr(
        type = PgNodeExpression.JSON_CONSTRUCTOR_TYPE_SCALAR,
        arguments = listOf(varArgument),
      )
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isFalse()
    }

    @Test
    fun `SqlValueFunction is unconditionally safe`() {
      val expression = PgNodeExpression.SqlValueFunction(operation = 0)
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isTrue()
    }

    @Test
    fun `NextValExpr is unconditionally safe`() {
      val expression = PgNodeExpression.NextValExpr(sequenceOid = 1)
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isTrue()
    }
  }

  /**
   * Unit tests for [GroupingSetNullExtension.isSafeFromGroupingSetNullExtension]'s Var-free,
   * no-structural-match leg (the private `immuneByNoGroupingKeyMatch`). The first test is the
   * positive case; each other test breaks exactly one of the leg's three conditions (no `Var`, no
   * lossily-parsed node, no grouping-key match). No tree here has an `Aggref`/`GroupingFunc`/
   * `WindowFunc` descendant, so the aggregate/window-domination rule cannot change the answer.
   */
  @Nested
  inner class GroupingSetNullExtensionLegB {

    @Test
    fun `a Var-free, non-matching FuncExpr with no dominating aggregate is safe`() {
      // Models now(): a zero-argument, non-foldable, not-always-non-null FuncExpr with no aggregate
      // descendant — unsafe under every other leg, safe only via this one.
      val expression = PgNodeExpression.FuncExpr(functionOid = 1234, arguments = emptyList())
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isTrue()
    }

    @Test
    fun `a Var anywhere in the subtree defeats immunity — condition 1`() {
      val expression = PgNodeExpression.FuncExpr(
        functionOid = 1234,
        arguments = listOf(PgNodeExpression.Var(varno = 1, varattno = 1, nullingRelations = emptySet())),
      )
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isFalse()
    }

    @Test
    fun `a SubLink anywhere in the subtree defeats immunity even though it is itself Var-free — condition 2`() {
      // The SubLink's own outerOperand is Var-free, but its unparsed subselect could hide a
      // correlated Var this parser cannot see — see immuneByNoGroupingKeyMatch's own KDoc for why
      // SubLink specifically (not just JsonExpr/Unknown) must be named as lossy here.
      val subLink = PgNodeExpression.SubLink(
        subLinkType = PgNodeExpression.SUBLINK_TYPE_EXISTS,
        outerOperand = PgNodeExpression.Const(isNull = false),
      )
      val expression = PgNodeExpression.FuncExpr(functionOid = 1234, arguments = listOf(subLink))
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = emptySet(),
      )
      assertThat(safe).isFalse()
    }

    @Test
    fun `a structural match against a grouping key subexpression defeats immunity — condition 3`() {
      val keySubexpression = PgNodeExpression.FuncExpr(functionOid = 5678, arguments = emptyList())
      val expression = PgNodeExpression.FuncExpr(functionOid = 1234, arguments = listOf(keySubexpression))
      val safe = groupingSetNullExtension().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = setOf(keySubexpression),
      )
      assertThat(safe).isFalse()
    }
  }
}
