package norm.generator

import norm.generator.NodeTreeNullabilityAnalyzer.Companion.MAX_EXPRESSION_DEPTH
import norm.generator.NodeTreeNullabilityAnalyzer.Companion.extractOuterJoinNullability

/**
 * Evaluates nullability of result columns from a PostgreSQL `pg_node_tree` text
 * (from `pg_rewrite.ev_action`).
 *
 * Uses [PgNodeTreeParser] to parse the `:targetList` and then recursively evaluates each
 * expression via [isNonNull]. This covers outer-join-induced nullability (VAR nodes with
 * non-empty `varnullingrels`), aggregate nullability, strict-function propagation, and more.
 *
 * For outer-join-only inspection (without full expression evaluation), use
 * [extractOuterJoinNullability].
 *
 * @param isStrict Returns `true` if the function or operator with the given OID is strict (returns
 *   `null` when any argument is `null`). Used for [isNonNull] evaluation of [PgNodeExpression.FuncExpr],
 *   [PgNodeExpression.OpExpr], [PgNodeExpression.ScalarArrayOpExpr], and [PgNodeExpression.WindowFunc].
 * @param hasNonNullInitialValue Returns `true` if the aggregate with the given OID has a non-null
 *   initial transition value (`agginitval IS NOT NULL` in `pg_aggregate`). Used for [isNonNull]
 *   evaluation of [PgNodeExpression.Aggref].
 * @param isSourceColumnNotNull Returns `true` if the source column identified by `varno` and
 *   `varattno` has a `NOT NULL` constraint. Used for [isNonNull] evaluation of [PgNodeExpression.Var].
 * @param isOuterJoinNullable Returns `true` if the given `nullingRelations` set indicates the column
 *   can be nulled by an outer join. Typically `true` when the set is non-empty.
 * @param isAlwaysNonNull Returns `true` for function OIDs that never return `null` regardless of
 *   argument nullability (e.g., `concat`).
 * @param isStrictButNullable Returns `true` for function OIDs that are marked STRICT but can still
 *   return `null` from non-null inputs (e.g., JSON path-extraction operators like `->>` and `->`).
 * @param isLagLeadWithDefault Returns `true` for the 3-argument overloads of `lag` and `lead` window
 *   functions, which return non-null when both the value and default arguments are non-null.
 */
internal class NodeTreeNullabilityAnalyzer(
  private val isStrict: (Int) -> Boolean,
  private val hasNonNullInitialValue: (Int) -> Boolean,
  private val isSourceColumnNotNull: (varno: Int, varattno: Int) -> Boolean,
  private val isOuterJoinNullable: (nullingRelations: Set<Int>) -> Boolean,
  private val isAlwaysNonNull: (Int) -> Boolean = { false },
  private val isStrictButNullable: (Int) -> Boolean = { false },
  private val isLagLeadWithDefault: (Int) -> Boolean = { false },
) {

  private val parser = PgNodeTreeParser()

  /**
   * Extracts per-column nullability from a `pg_node_tree` text using full expression evaluation.
   *
   * Uses [PgNodeTreeParser] to parse the target list, then evaluates each non-junk entry with
   * [isNonNull] for accurate expression-level nullability. Returns `true` (nullable) when
   * `isNonNull` returns `false`.
   *
   * CTE column resolution is handled by the caller through the [isSourceColumnNotNull] callback.
   * The caller must include CTE column not-null information in this callback so that VAR nodes
   * referencing CTE RTEs resolve correctly via the standard [isNonNull] Var evaluation path.
   *
   * @param nodeTreeText the raw text value of `pg_rewrite.ev_action`
   * @return one `Boolean` per result column (in column order), where `true` means the column may
   *   be `null`
   */
  fun extractColumnNullability(nodeTreeText: String): List<Boolean> {
    val entries = parser.parseTargetList(nodeTreeText)
    if (entries.isEmpty()) return emptyList()

    return entries
      .filter { !it.isJunk }
      .sortedBy { it.resultNumber }
      .map { entry -> !isNonNull(entry.expression) }
  }

  /**
   * Recursively evaluates whether [expression] is guaranteed non-null.
   *
   * Uses the constructor-injected lookup functions to check strictness, aggregate initial values,
   * and source column nullability. A recursion [depth] guard prevents stack overflow on pathological
   * inputs; returns `false` (safe: nullable) when the depth limit is reached.
   *
   * @param expression The expression node to evaluate.
   * @param depth Maximum remaining recursion depth. Defaults to [MAX_EXPRESSION_DEPTH].
   * @return `true` if the expression is guaranteed non-null, `false` if it may be `null`.
   */
  internal fun isNonNull(expression: PgNodeExpression, depth: Int = MAX_EXPRESSION_DEPTH): Boolean {
    if (depth <= 0) return false
    val recurse = { expr: PgNodeExpression -> isNonNull(expr, depth - 1) }
    return when (expression) {
      is PgNodeExpression.Var -> !isOuterJoinNullable(expression.nullingRelations) &&
        isSourceColumnNotNull(expression.varno, expression.varattno)

      is PgNodeExpression.Const -> !expression.isNull
      is PgNodeExpression.FuncExpr ->
        isAlwaysNonNull(expression.functionOid) ||
          (
            isStrict(expression.functionOid) &&
              !isStrictButNullable(expression.functionOid) &&
              expression.arguments.all(recurse)
            )

      is PgNodeExpression.OpExpr ->
        isStrict(expression.operatorFunctionOid) &&
          !isStrictButNullable(expression.operatorFunctionOid) &&
          expression.arguments.all(recurse)

      is PgNodeExpression.ScalarArrayOpExpr ->
        isStrict(expression.operatorFunctionOid) &&
          !isStrictButNullable(expression.operatorFunctionOid) &&
          expression.arguments.all(recurse)

      is PgNodeExpression.CoalesceExpr -> expression.arguments.any(recurse)
      is PgNodeExpression.NullIfExpr -> false // can always return null
      is PgNodeExpression.MinMaxExpr -> expression.arguments.all(recurse)
      is PgNodeExpression.Aggref -> hasNonNullInitialValue(expression.aggregateFunctionOid)
      is PgNodeExpression.WindowFunc -> evaluateWindowFunc(expression, recurse)
      is PgNodeExpression.SubLink ->
        expression.subLinkType == PgNodeExpression.SUBLINK_TYPE_EXISTS ||
          expression.subLinkType == PgNodeExpression.SUBLINK_TYPE_ARRAY ||
          (
            expression.subLinkType == PgNodeExpression.SUBLINK_TYPE_ANY &&
              expression.outerOperand?.let(recurse) == true
            )

      is PgNodeExpression.CaseExpr ->
        expression.defaultResult != null &&
          (expression.resultExpressions + expression.defaultResult).all(recurse)

      is PgNodeExpression.BoolExpr -> expression.arguments.all(recurse)
      is PgNodeExpression.RelabelType -> recurse(expression.argument)
      is PgNodeExpression.CoerceViaIo -> recurse(expression.argument)
      is PgNodeExpression.ArrayCoerceExpr -> recurse(expression.argument)
      is PgNodeExpression.CollateExpr -> recurse(expression.argument)
      is PgNodeExpression.CoerceToDomain -> recurse(expression.argument)
      is PgNodeExpression.SqlValueFunction -> true
      is PgNodeExpression.NullTest -> true
      is PgNodeExpression.BooleanTest -> true
      is PgNodeExpression.DistinctExpr -> true
      is PgNodeExpression.ArrayExpr -> true
      is PgNodeExpression.RowExpr -> true
      is PgNodeExpression.NextValExpr -> true
      is PgNodeExpression.GroupingFunc -> true
      is PgNodeExpression.FieldSelect -> false // safe default — field nullability requires composite type analysis
      is PgNodeExpression.JsonIsPredicate -> true
      is PgNodeExpression.JsonConstructorExpr -> true
      is PgNodeExpression.JsonExpr -> evaluateJsonExpr(expression, recurse)
      is PgNodeExpression.XmlExpr -> evaluateXmlExpr(expression, recurse)
      is PgNodeExpression.Unknown -> false // safe default
    }
  }

  private fun evaluateWindowFunc(
    expression: PgNodeExpression.WindowFunc,
    recurse: (PgNodeExpression) -> Boolean,
  ): Boolean {
    if (expression.arguments.isEmpty()) return true
    // LAG/LEAD with 3 args (value, offset, default): non-null when value and default are non-null.
    if (isLagLeadWithDefault(expression.windowFunctionOid) && expression.arguments.size >= 3) {
      return recurse(expression.arguments[0]) && recurse(expression.arguments[2])
    }
    // NTILE is strict and always returns non-null from non-null input. Other strict window functions
    // (FIRST_VALUE, LAST_VALUE, NTH_VALUE, LAG/LEAD 1-2 arg) can return null at frame boundaries
    // and are excluded via isStrictButNullable.
    if (isStrict(expression.windowFunctionOid) &&
      !isStrictButNullable(expression.windowFunctionOid) &&
      expression.arguments.all(recurse)
    ) {
      return true
    }
    return false
  }

  private fun evaluateJsonExpr(
    expression: PgNodeExpression.JsonExpr,
    recurse: (PgNodeExpression) -> Boolean,
  ): Boolean = when (expression.op) {
    PgNodeExpression.JSON_EXISTS_OP -> true
    PgNodeExpression.JSON_SERIALIZE_OP -> recurse(expression.argument)
    PgNodeExpression.JSON_VALUE_OP, PgNodeExpression.JSON_QUERY_OP -> {
      val emptyOk = expression.onEmpty != PgNodeExpression.JSON_BEHAVIOR_NULL &&
        expression.onEmptyDefault?.let(recurse) != false
      val errorOk = expression.onError != PgNodeExpression.JSON_BEHAVIOR_NULL &&
        expression.onErrorDefault?.let(recurse) != false
      emptyOk && errorOk
    }

    else -> false
  }

  private fun evaluateXmlExpr(expression: PgNodeExpression.XmlExpr, recurse: (PgNodeExpression) -> Boolean): Boolean =
    when (expression.op) {
      PgNodeExpression.XML_IS_XMLELEMENT,
      PgNodeExpression.XML_IS_XMLFOREST,
      PgNodeExpression.XML_IS_XMLPI,
      -> true

      PgNodeExpression.XML_IS_XMLCONCAT,
      PgNodeExpression.XML_IS_XMLROOT,
      PgNodeExpression.XML_IS_XMLPARSE,
      PgNodeExpression.XML_IS_XMLSERIALIZE,
      -> expression.arguments.all(recurse)

      else -> false
    }

  internal companion object {
    internal const val MAX_EXPRESSION_DEPTH = 100

    /**
     * Returns nullable flags for each non-junk column based solely on outer-join nulling relations.
     *
     * Unlike [NodeTreeNullabilityAnalyzer.extractColumnNullability], this method does not evaluate
     * expression nullability — it only checks whether a VAR node was introduced by an outer join
     * (via `varnullingrels`). Use this for catalog view inspection where only outer-join structure
     * matters, not expression semantics.
     */
    internal fun extractOuterJoinNullability(nodeTreeText: String): List<Boolean> {
      val parser = PgNodeTreeParser()
      return parser.parseTargetList(nodeTreeText)
        .filter { !it.isJunk }
        .sortedBy { it.resultNumber }
        .map { entry ->
          when (val expression = entry.expression) {
            is PgNodeExpression.Var -> expression.nullingRelations.isNotEmpty()
            else -> false
          }
        }
    }
  }
}
