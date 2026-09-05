package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * One non-leaf [PgNodeExpression] subtype, constructed with a distinct sentinel
 * [PgNodeExpression.Var] in every [PgNodeExpression]/[PgNodeExpression]?/`List<PgNodeExpression>`
 * constructor parameter, so a [PgNodeExpression.children] or [PgNodeExpression.mapChildren] branch
 * that mishandles a field's position or omits it entirely fails [expectedChildren]'s comparison.
 */
internal data class NonLeafCase(
  val description: String,
  val node: PgNodeExpression,
  val expectedChildren: List<PgNodeExpression>,
) {
  override fun toString() = description
}

/**
 * Table-driven coverage for [PgNodeExpression.children] and [PgNodeExpression.mapChildren]: one
 * case per subtype, distinguishing leaves (which return an empty list / themselves unchanged) from
 * every other subtype (which must enumerate its children in constructor-parameter order and rebuild
 * itself around a transformed copy of them).
 *
 * `mapChildren { it } == node` alone would pass even when a branch forgets a field entirely — an
 * identity copy of a wrong field set is still equal to the original if that field was never varied —
 * so each non-leaf case also asserts [PgNodeExpression.children] directly, and that
 * [PgNodeExpression.mapChildren] applies a non-identity transform to exactly those children.
 */
class PgNodeExpressionTest {

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  internal inner class NonLeafSubtypes {

    private val replaceWithConst: (PgNodeExpression) -> PgNodeExpression = { PgNodeExpression.Const(isNull = true) }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonLeafCases")
    fun `children returns the exact sentinel list in constructor-parameter order`(testCase: NonLeafCase) {
      assertThat(testCase.node.children).isEqualTo(testCase.expectedChildren)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonLeafCases")
    fun `mapChildren applies transform to exactly the enumerated children`(testCase: NonLeafCase) {
      val mapped = testCase.node.mapChildren(replaceWithConst)
      assertThat(mapped.children).isEqualTo(testCase.expectedChildren.map(replaceWithConst))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonLeafCases")
    fun `mapChildren with the identity transform rebuilds an equal node`(testCase: NonLeafCase) {
      assertThat(testCase.node.mapChildren { it }).isEqualTo(testCase.node)
    }

    fun nonLeafCases(): List<NonLeafCase> {
      val sentinels = (1..4).map { PgNodeExpression.Var(varno = it, varattno = 0, nullingRelations = emptySet()) }
      val (first, second, third, fourth) = sentinels
      return listOf(
        NonLeafCase(
          "FuncExpr",
          PgNodeExpression.FuncExpr(functionOid = 1, arguments = listOf(first), isVariadic = true),
          listOf(first),
        ),
        NonLeafCase(
          "OpExpr",
          PgNodeExpression.OpExpr(operatorFunctionOid = 1, arguments = listOf(first)),
          listOf(first),
        ),
        NonLeafCase(
          "ScalarArrayOpExpr",
          PgNodeExpression.ScalarArrayOpExpr(operatorFunctionOid = 1, arguments = listOf(first), useOr = true),
          listOf(first),
        ),
        NonLeafCase(
          "CoalesceExpr",
          PgNodeExpression.CoalesceExpr(arguments = listOf(first)),
          listOf(first),
        ),
        NonLeafCase(
          "NullIfExpr",
          PgNodeExpression.NullIfExpr(arguments = listOf(first)),
          listOf(first),
        ),
        NonLeafCase(
          "MinMaxExpr",
          PgNodeExpression.MinMaxExpr(arguments = listOf(first)),
          listOf(first),
        ),
        NonLeafCase(
          "Aggref",
          PgNodeExpression.Aggref(aggregateFunctionOid = 1, arguments = listOf(first)),
          listOf(first),
        ),
        NonLeafCase(
          "WindowFunc",
          PgNodeExpression.WindowFunc(windowFunctionOid = 1, arguments = listOf(first)),
          listOf(first),
        ),
        NonLeafCase(
          "SubLink",
          PgNodeExpression.SubLink(subLinkType = 2, outerOperand = first),
          listOf(first),
        ),
        NonLeafCase(
          "CaseExpr",
          PgNodeExpression.CaseExpr(
            resultExpressions = listOf(first),
            defaultResult = second,
            testExpression = third,
            whenConditions = listOf(fourth),
          ),
          listOf(first, second, third, fourth),
        ),
        NonLeafCase(
          "BoolExpr",
          PgNodeExpression.BoolExpr(arguments = listOf(first), boolOperator = PgNodeExpression.BOOL_OPERATOR_AND),
          listOf(first),
        ),
        NonLeafCase(
          "RelabelType",
          PgNodeExpression.RelabelType(argument = first),
          listOf(first),
        ),
        NonLeafCase(
          "CoerceViaIo",
          PgNodeExpression.CoerceViaIo(argument = first),
          listOf(first),
        ),
        NonLeafCase(
          "ArrayCoerceExpr",
          PgNodeExpression.ArrayCoerceExpr(argument = first),
          listOf(first),
        ),
        NonLeafCase(
          "CollateExpr",
          PgNodeExpression.CollateExpr(argument = first),
          listOf(first),
        ),
        NonLeafCase(
          "CoerceToDomain",
          PgNodeExpression.CoerceToDomain(argument = first),
          listOf(first),
        ),
        NonLeafCase(
          "NullTest",
          PgNodeExpression.NullTest(argument = first, nullTestType = PgNodeExpression.NULL_TEST_IS_NULL),
          listOf(first),
        ),
        NonLeafCase(
          "BooleanTest",
          PgNodeExpression.BooleanTest(argument = first),
          listOf(first),
        ),
        NonLeafCase(
          "DistinctExpr",
          PgNodeExpression.DistinctExpr(arguments = listOf(first)),
          listOf(first),
        ),
        NonLeafCase(
          "ArrayExpr",
          PgNodeExpression.ArrayExpr(elements = listOf(first)),
          listOf(first),
        ),
        NonLeafCase(
          "RowExpr",
          PgNodeExpression.RowExpr(arguments = listOf(first)),
          listOf(first),
        ),
        NonLeafCase(
          "GroupingFunc",
          PgNodeExpression.GroupingFunc(arguments = listOf(first)),
          listOf(first),
        ),
        NonLeafCase(
          "FieldSelect",
          PgNodeExpression.FieldSelect(argument = first, fieldNumber = 1),
          listOf(first),
        ),
        NonLeafCase(
          "JsonIsPredicate",
          PgNodeExpression.JsonIsPredicate(argument = first),
          listOf(first),
        ),
        NonLeafCase(
          "JsonConstructorExpr",
          PgNodeExpression.JsonConstructorExpr(
            type = PgNodeExpression.JSON_CONSTRUCTOR_TYPE_OBJECT,
            arguments = listOf(first),
            function = second,
          ),
          listOf(first, second),
        ),
        NonLeafCase(
          "JsonExpr",
          PgNodeExpression.JsonExpr(
            op = PgNodeExpression.JSON_VALUE_OP,
            argument = first,
            onEmpty = PgNodeExpression.JSON_BEHAVIOR_DEFAULT,
            onEmptyDefault = second,
            onError = PgNodeExpression.JSON_BEHAVIOR_DEFAULT,
            onErrorDefault = third,
          ),
          listOf(first, second, third),
        ),
        NonLeafCase(
          "XmlExpr",
          PgNodeExpression.XmlExpr(op = PgNodeExpression.XML_IS_XMLCONCAT, arguments = listOf(first)),
          listOf(first),
        ),
      )
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  internal inner class LeafSubtypes {

    @ParameterizedTest(name = "{0}")
    @MethodSource("leafNodes")
    fun `children is empty`(node: PgNodeExpression) {
      assertThat(node.children).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("leafNodes")
    fun `mapChildren with the identity transform returns the same instance`(node: PgNodeExpression) {
      assertThat(node.mapChildren { it }).isSameInstanceAs(node)
    }

    fun leafNodes(): List<PgNodeExpression> = listOf(
      PgNodeExpression.Var(varno = 1, varattno = 0, nullingRelations = emptySet()),
      PgNodeExpression.Const(isNull = false),
      PgNodeExpression.SqlValueFunction(operation = 0),
      PgNodeExpression.NextValExpr(sequenceOid = 1),
      PgNodeExpression.Unknown(nodeType = "FOO"),
    )
  }
}
