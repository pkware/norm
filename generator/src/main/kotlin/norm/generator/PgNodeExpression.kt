package norm.generator

/**
 * Typed representation of PostgreSQL expression nodes from `pg_node_tree` text.
 *
 * Each subtype carries only the fields needed for nullability analysis, not a full PG node model.
 * Unrecognized node types are represented as [Unknown], which evaluates to nullable (safe default).
 */
internal sealed interface PgNodeExpression {

  data class Var(val varno: Int, val varattno: Int, val nullingRelations: Set<Int>) : PgNodeExpression

  data class Const(val isNull: Boolean) : PgNodeExpression

  data class FuncExpr(val functionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class OpExpr(val operatorFunctionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class ScalarArrayOpExpr(val operatorFunctionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class CoalesceExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class NullIfExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class MinMaxExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class Aggref(val aggregateFunctionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class WindowFunc(val windowFunctionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class SubLink(val subLinkType: Int, val outerOperand: PgNodeExpression? = null) : PgNodeExpression

  data class CaseExpr(val resultExpressions: List<PgNodeExpression>, val defaultResult: PgNodeExpression?) :
    PgNodeExpression

  data class BoolExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class RelabelType(val argument: PgNodeExpression) : PgNodeExpression
  data class CoerceViaIo(val argument: PgNodeExpression) : PgNodeExpression
  data class ArrayCoerceExpr(val argument: PgNodeExpression) : PgNodeExpression
  data class CollateExpr(val argument: PgNodeExpression) : PgNodeExpression
  data class CoerceToDomain(val argument: PgNodeExpression) : PgNodeExpression

  data class SqlValueFunction(val operation: Int) : PgNodeExpression
  data class NullTest(val argument: PgNodeExpression) : PgNodeExpression
  data class BooleanTest(val argument: PgNodeExpression) : PgNodeExpression
  data class DistinctExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression
  data class ArrayExpr(val elements: List<PgNodeExpression>) : PgNodeExpression
  data class RowExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression
  data class NextValExpr(val sequenceOid: Int) : PgNodeExpression
  data class GroupingFunc(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class FieldSelect(val argument: PgNodeExpression, val fieldNumber: Int) : PgNodeExpression

  data class JsonIsPredicate(val argument: PgNodeExpression) : PgNodeExpression
  data class JsonConstructorExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression
  data class JsonExpr(
    val op: Int,
    val argument: PgNodeExpression,
    val onEmpty: Int,
    val onEmptyDefault: PgNodeExpression?,
    val onError: Int,
    val onErrorDefault: PgNodeExpression?,
  ) : PgNodeExpression

  data class XmlExpr(val op: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  /** Fallback for unrecognized node types. Safe default: nullable. */
  data class Unknown(val nodeType: String) : PgNodeExpression

  companion object {
    // SubLinkType (primnodes.h)
    const val SUBLINK_TYPE_EXISTS: Int = 0
    const val SUBLINK_TYPE_ANY: Int = 2
    const val SUBLINK_TYPE_ARRAY: Int = 6

    // JsonExprOp (primnodes.h)
    const val JSON_VALUE_OP: Int = 0
    const val JSON_QUERY_OP: Int = 1
    const val JSON_EXISTS_OP: Int = 2
    const val JSON_SERIALIZE_OP: Int = 4

    // JsonBehaviorType (primnodes.h)
    const val JSON_BEHAVIOR_NULL: Int = 0

    // XmlExprOp (primnodes.h)
    const val XML_IS_XMLCONCAT: Int = 0
    const val XML_IS_XMLELEMENT: Int = 1
    const val XML_IS_XMLFOREST: Int = 2
    const val XML_IS_XMLPARSE: Int = 3
    const val XML_IS_XMLPI: Int = 4
    const val XML_IS_XMLROOT: Int = 5
    const val XML_IS_XMLSERIALIZE: Int = 6
  }
}

/**
 * A CTE (Common Table Expression) definition parsed from a `{COMMONTABLEEXPR ...}` block
 * in the `:cteList` of a query's node tree.
 *
 * Named with a `NodeTree` prefix to distinguish from [CteDefinition] in `SqlUtils.kt`, which
 * represents SQL-text-level CTE positions for DML transformation.
 *
 * @property name The CTE name (from `:ctename`).
 * @property queryBlock The full `{QUERY ...}` block text of the CTE's body (from `:ctequery`).
 */
internal data class NodeTreeCteDefinition(val name: String, val queryBlock: String)

/**
 * A single result column from a query's `targetList`.
 *
 * @property expression The parsed expression node for this column.
 * @property resultName The column alias or name (from `:resname`), `null` if absent.
 * @property resultNumber The 1-based column position (from `:resno`).
 * @property isJunk Whether this entry is a junk column (`:resjunk true`), which should be excluded from results.
 */
internal data class TargetEntry(
  val expression: PgNodeExpression,
  val resultName: String?,
  val resultNumber: Int,
  val isJunk: Boolean,
)
