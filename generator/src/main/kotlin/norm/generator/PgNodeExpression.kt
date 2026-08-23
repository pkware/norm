package norm.generator

/**
 * Typed representation of PostgreSQL expression nodes from `pg_node_tree` text.
 *
 * Each subtype carries only the fields needed for nullability analysis, not a full PG node model.
 * Unrecognized node types are represented as [Unknown], which evaluates to nullable (safe default).
 */
internal sealed interface PgNodeExpression {

  /**
   * @property levelsUp `0` when this `Var` resolves in the current query level. A value greater
   *   than `0` means it is an outer reference from a correlated or LATERAL subquery, and [varno]
   *   refers to an enclosing query's range table rather than the current block's, so it must not
   *   be interpreted against the current block.
   * @property returningType `0` for an ordinary `Var` (including every `Var` on PostgreSQL versions
   *   older than 18, which never emit `:varreturningtype` at all). `1` means this `Var` reads the
   *   `OLD` row of a PostgreSQL 18+ `RETURNING WITH (OLD AS o, NEW AS n)` clause; `2` means `NEW`.
   *   An `OLD`-row `Var` is byte-identical to its `NEW` counterpart except for this field — in
   *   particular, [nullingRelations] is empty for both, because "the matched row does not exist for
   *   this `MERGE` action" is not an outer-join null-extension the planner tracks that way. A `1`
   *   here must therefore be treated as unconditionally nullable regardless of [nullingRelations]
   *   or the source column's own `NOT NULL` constraint: whether a `MERGE`'s `WHEN NOT MATCHED THEN
   *   INSERT` action fired for a given result row (leaving no `OLD` row to read) is not something
   *   this analyzer proves one way or the other, and "cannot be proven non-null" must resolve to
   *   nullable, never to a confidently wrong `NOT NULL`.
   */
  data class Var(
    val varno: Int,
    val varattno: Int,
    val nullingRelations: Set<Int>,
    val levelsUp: Int = 0,
    val returningType: Int = 0,
  ) : PgNodeExpression

  data class Const(val isNull: Boolean) : PgNodeExpression

  /**
   * @property isVariadic `true` when the call uses `VARIADIC` (e.g. `concat(VARIADIC arr)`) —
   *   from `:funcvariadic`. In this form the LAST entry of [arguments] is the array expression
   *   itself, passed through as one value, not exploded into its elements. This matters for
   *   nullability: `concat(VARIADIC arr)` is `null` when `arr` itself is `null` (verified live on
   *   PostgreSQL 16, 17, and 18), which neither [PgCatalogLoader.alwaysNonNullFunctionOids] nor
   *   [PgCatalogLoader.nonNullIffFirstArgumentNonNullFunctionOids] account for on their own — both
   *   assume the ordinary (non-`VARIADIC`) calling form where every argument is an individual
   *   scalar value, and [NodeTreeNullabilityAnalyzer.isNonNull] must check this flag before
   *   trusting either list unconditionally.
   */
  data class FuncExpr(val functionOid: Int, val arguments: List<PgNodeExpression>, val isVariadic: Boolean = false) :
    PgNodeExpression

  data class OpExpr(val operatorFunctionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  /**
   * @property useOr `true` for `ANY`/`SOME`, `false` for `ALL`.
   */
  data class ScalarArrayOpExpr(
    val operatorFunctionOid: Int,
    val arguments: List<PgNodeExpression>,
    val useOr: Boolean = false,
  ) : PgNodeExpression

  data class CoalesceExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class NullIfExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class MinMaxExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class Aggref(val aggregateFunctionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class WindowFunc(val windowFunctionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class SubLink(val subLinkType: Int, val outerOperand: PgNodeExpression? = null) : PgNodeExpression

  /**
   * @property testExpression The `CASE testexpr WHEN ...` test expression (from `:arg`), `null`
   *   for the `CASE WHEN cond THEN ...` form, which has no single test expression. Not evaluated
   *   by [NodeTreeNullabilityAnalyzer.isNonNull] — a `CASE`'s own nullability never depends on its
   *   test expression's nullability — but retained so a `Var` that appears only here (e.g.
   *   `CASE a WHEN 'x' THEN 1 ELSE 2 END`) is reachable by
   *   [NodeTreeNullabilityAnalyzer] for GROUPING SETS/CUBE/ROLLUP Var-reachability analysis.
   * @property whenConditions The condition expression of each `WHEN` arm (from each `CASEWHEN`
   *   block's `:expr`). Like [testExpression], not evaluated by [NodeTreeNullabilityAnalyzer.isNonNull]
   *   — a `WHEN` condition's nullability does not affect the `CASE` result's nullability — but
   *   retained for the same Var-reachability reason: `CASE WHEN a = 'x' THEN 1 ELSE 2 END` has its
   *   only `Var` inside a `WHEN` condition, never in [resultExpressions] or [defaultResult].
   */
  data class CaseExpr(
    val resultExpressions: List<PgNodeExpression>,
    val defaultResult: PgNodeExpression?,
    val testExpression: PgNodeExpression? = null,
    val whenConditions: List<PgNodeExpression> = emptyList(),
  ) : PgNodeExpression

  /**
   * @property boolOperator One of [BOOL_OPERATOR_AND], [BOOL_OPERATOR_OR], or [BOOL_OPERATOR_NOT].
   */
  data class BoolExpr(val arguments: List<PgNodeExpression>, val boolOperator: String) : PgNodeExpression

  data class RelabelType(val argument: PgNodeExpression) : PgNodeExpression
  data class CoerceViaIo(val argument: PgNodeExpression) : PgNodeExpression
  data class ArrayCoerceExpr(val argument: PgNodeExpression) : PgNodeExpression
  data class CollateExpr(val argument: PgNodeExpression) : PgNodeExpression
  data class CoerceToDomain(val argument: PgNodeExpression) : PgNodeExpression

  data class SqlValueFunction(val operation: Int) : PgNodeExpression

  /**
   * @property nullTestType [NULL_TEST_IS_NULL] for `IS NULL`, [NULL_TEST_IS_NOT_NULL] for `IS NOT NULL`.
   */
  data class NullTest(val argument: PgNodeExpression, val nullTestType: Int) : PgNodeExpression
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
    const val SUBLINK_TYPE_ALL: Int = 3
    const val SUBLINK_TYPE_ARRAY: Int = 6

    // JsonExprOp (primnodes.h) — introduced in PG 17
    const val JSON_EXISTS_OP: Int = 0
    const val JSON_QUERY_OP: Int = 1
    const val JSON_VALUE_OP: Int = 2
    const val JSON_SERIALIZE_OP: Int = 4

    // JsonBehaviorType (primnodes.h). Each code named here is one NodeTreeNullabilityAnalyzer's
    // evaluateJsonExpr recognizes as producing a definite outcome for JSON_QUERY's ON EMPTY/ON
    // ERROR clause or JSON_EXISTS's ON ERROR clause; an unrecognized or unnamed code is treated as
    // nullable, the safe default.
    const val JSON_BEHAVIOR_NULL: Int = 0
    const val JSON_BEHAVIOR_ERROR: Int = 1
    const val JSON_BEHAVIOR_TRUE: Int = 3
    const val JSON_BEHAVIOR_FALSE: Int = 4
    const val JSON_BEHAVIOR_UNKNOWN: Int = 5
    const val JSON_BEHAVIOR_EMPTY_ARRAY: Int = 6
    const val JSON_BEHAVIOR_EMPTY_OBJECT: Int = 7
    const val JSON_BEHAVIOR_DEFAULT: Int = 8

    // BoolExprType (primnodes.h)
    const val BOOL_OPERATOR_AND: String = "and"
    const val BOOL_OPERATOR_OR: String = "or"
    const val BOOL_OPERATOR_NOT: String = "not"

    // NullTestType (primnodes.h)
    const val NULL_TEST_IS_NULL: Int = 0
    const val NULL_TEST_IS_NOT_NULL: Int = 1

    // VarReturningType (primnodes.h) — PostgreSQL 18+ only; see [Var.returningType]'s KDoc
    const val VAR_RETURNING_TYPE_NORMAL: Int = 0
    const val VAR_RETURNING_TYPE_OLD: Int = 1
    const val VAR_RETURNING_TYPE_NEW: Int = 2

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
 * @property resultName The column alias or name (from `:resname`), with `pg_node_tree`
 *   backslash-escaping already removed (an alias `k}x` round-trips as exactly `k}x`, never the raw
 *   `k\}x` — see [PgNodeTreeParser]'s class-level note on escaping). `null` if absent.
 * @property resultNumber The 1-based column position (from `:resno`).
 * @property isJunk Whether this entry is a junk column (`:resjunk true`), which should be excluded from results.
 * @property sortGroupRef The entry's `:ressortgroupref` value, `0` when the entry is not a `GROUP BY`/`ORDER
 *   BY`/`DISTINCT` key. When non-zero and this value appears among the `:tleSortGroupRef`s referenced by
 *   `:groupClause`/`:groupingSets`, this entry IS a `GROUP BY` grouping key — see
 *   [NodeTreeNullabilityAnalyzer]'s GROUPING SETS/CUBE/ROLLUP handling.
 */
internal data class TargetEntry(
  val expression: PgNodeExpression,
  val resultName: String?,
  val resultNumber: Int,
  val isJunk: Boolean,
  val sortGroupRef: Int = 0,
)
