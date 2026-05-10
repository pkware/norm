package norm.generator

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
 * @param resolveColumnNotNull Resolves `(relid, attnum)` to whether the column is `NOT NULL` in
 *   `pg_attribute`. Used by CTE body analysis to build sub-analyzers with CTE-local range tables.
 */
internal class NodeTreeNullabilityAnalyzer(
  private val isStrict: (Int) -> Boolean,
  private val hasNonNullInitialValue: (Int) -> Boolean,
  private val isSourceColumnNotNull: (varno: Int, varattno: Int) -> Boolean,
  private val isOuterJoinNullable: (nullingRelations: Set<Int>) -> Boolean,
  private val isAlwaysNonNull: (Int) -> Boolean = { false },
  private val isStrictButNullable: (Int) -> Boolean = { false },
  private val isLagLeadWithDefault: (Int) -> Boolean = { false },
  private val resolveColumnNotNull: (relid: Int, attnum: Int) -> Boolean = { _, _ -> false },
  private val jsonExistsOp: Int = PgNodeExpression.JSON_EXISTS_OP,
  private val jsonValueOp: Int = PgNodeExpression.JSON_VALUE_OP,
  private val jsonSerializeOp: Int = PgNodeExpression.JSON_SERIALIZE_OP,
) {

  private val parser = PgNodeTreeParser()

  /**
   * Extracts per-column nullability from a `pg_node_tree` text using full expression evaluation.
   *
   * Uses [PgNodeTreeParser] to parse the target list, then evaluates each non-junk entry with
   * [isNonNull] for accurate expression-level nullability. Returns `true` (nullable) when
   * `isNonNull` returns `false`.
   *
   * For CTEs, when a VAR references a CTE range table entry (`rtekind 6`), the nullability is
   * resolved from the CTE body's own targetList column (which carries the correct `varnullingrels`
   * from the join inside the CTE). CTE outer-join nullability (when the CTE itself is on the
   * nullable side of an outer join) is also combined.
   *
   * @param nodeTreeText the raw text value of `pg_rewrite.ev_action`
   * @return one `Boolean` per result column (in column order), where `true` means the column may
   *   be `null`
   */
  fun extractColumnNullability(nodeTreeText: String): List<Boolean> {
    val entries = parser.parseTargetList(nodeTreeText)
    if (entries.isEmpty()) return emptyList()

    // Build a map from varno → CTE column nullabilities for CTE RTE references.
    // This allows resolving nullability when the outer query references a CTE (rtekind 6).
    val varnoToCteNullability = buildVarnoToCteNullabilityMap(nodeTreeText)

    return entries
      .filter { !it.isJunk }
      .sortedBy { it.resultNumber }
      .map { entry -> isNullableEntry(entry, varnoToCteNullability) }
  }

  /**
   * Determines nullability for a single [TargetEntry].
   *
   * For VAR nodes that reference a CTE range table entry, combines:
   * 1. Whether the outer VAR's own `varnullingrels` indicates an outer-join nullable CTE reference.
   * 2. The CTE body's internal column nullability at the corresponding column position.
   *
   * For all other expression types, delegates to [isNonNull] and inverts the result.
   *
   * @param entry the target list entry to evaluate
   * @param varnoToCteNullability map from `varno` to per-column CTE nullabilities for CTE RTEs
   * @return `true` if the column may be `null`, `false` if it is guaranteed non-null
   */
  private fun isNullableEntry(entry: TargetEntry, varnoToCteNullability: Map<Int, List<Boolean>>): Boolean {
    val expression = entry.expression
    if (expression is PgNodeExpression.Var && varnoToCteNullability.isNotEmpty()) {
      val cteNullabilities = varnoToCteNullability[expression.varno]
      if (cteNullabilities != null) {
        // Check the outer VAR's varnullingrels first — if the CTE is on the nullable side
        // of an outer join, the column is nullable regardless of the CTE's internal structure.
        if (isOuterJoinNullable(expression.nullingRelations)) return true
        // Otherwise, check the CTE body's internal column nullability.
        return cteNullabilities.getOrElse(expression.varattno - 1) { false }
      }
    }
    return !isNonNull(expression)
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
    jsonExistsOp -> true
    jsonSerializeOp -> recurse(expression.argument)
    jsonValueOp, PgNodeExpression.JSON_QUERY_OP -> {
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

  /**
   * Builds a map from `varno` (1-based RTE index) to CTE column nullabilities for CTE RTEs.
   *
   * Reads `:cteList` and `:rtable` entries at the outer QUERY level. For each CTE definition,
   * computes column nullabilities from the CTE body. Then maps each `rtekind 6` range table
   * entry to the corresponding CTE's nullabilities by name.
   *
   * @return A map from `varno` to the CTE's per-column nullable flags. Only
   *   contains entries for CTE RTEs; other RTE types are absent from the map.
   */
  private fun buildVarnoToCteNullabilityMap(nodeTreeText: String): Map<Int, List<Boolean>> {
    val cteDefinitions = parser.parseCteList(nodeTreeText)
    if (cteDefinitions.isEmpty()) return emptyMap()

    val cteRteMap = parser.parseCteRangeTableEntries(nodeTreeText)
    if (cteRteMap.isEmpty()) return emptyMap()

    val cteNullabilities = parseCteNullabilities(cteDefinitions)
    if (cteNullabilities.isEmpty()) return emptyMap()

    return buildMap {
      for ((varno, cteName) in cteRteMap) {
        val nullabilities = cteNullabilities[cteName] ?: continue
        put(varno, nullabilities)
      }
    }
  }

  /**
   * Parses CTE body nullabilities from a list of [NodeTreeCteDefinition]s.
   *
   * CTEs are processed in declaration order so that each CTE body can resolve references to
   * previously declared CTEs. This handles cases like `WITH a AS (SELECT ... LEFT JOIN ...),
   * b AS (SELECT ... FROM a) SELECT ... FROM b` where nullability from `a`'s LEFT JOIN must
   * propagate through `b` to the outer query.
   *
   * @return A map from CTE name to per-column nullable flags for the CTE's output columns.
   */
  private fun parseCteNullabilities(cteDefinitions: List<NodeTreeCteDefinition>): Map<String, List<Boolean>> {
    val resolvedCtes = mutableMapOf<String, List<Boolean>>()
    for (cte in cteDefinitions) {
      val nullabilities = parseSingleCteNullability(cte, resolvedCtes) ?: continue
      resolvedCtes[cte.name] = nullabilities
    }
    return resolvedCtes
  }

  /**
   * Computes per-column nullability for a single CTE body using full expression evaluation.
   *
   * Builds a sub-analyzer with the CTE body's own range table so that VAR nodes inside the CTE
   * resolve against the CTE's tables, not the outer query's. For CTE bodies with set operations
   * (UNION ALL, INTERSECT, EXCEPT), analyzes each branch and combines results: a column is
   * nullable if ANY branch produces a nullable value.
   *
   * @param cte The CTE definition containing the name and query block.
   * @param previouslyResolvedCtes CTE nullabilities resolved from earlier declarations in the same
   *   WITH clause, used when this CTE's body references another CTE.
   * @return Per-column nullable flags, or `null` if the CTE body cannot be parsed.
   */
  private fun parseSingleCteNullability(
    cte: NodeTreeCteDefinition,
    previouslyResolvedCtes: Map<String, List<Boolean>>,
  ): List<Boolean>? {
    if (parser.hasSetOperations(cte.queryBlock)) {
      return analyzeSetOperationCteBody(cte.queryBlock, previouslyResolvedCtes, cte.name)
    }
    val subAnalyzer = buildCteSubAnalyzer(cte.queryBlock, previouslyResolvedCtes)
    val result = subAnalyzer.extractColumnNullability(cte.queryBlock)
    if (result.isNotEmpty()) return result
    // DML CTEs (INSERT/UPDATE/DELETE ... RETURNING) store output columns in :returningList,
    // not :targetList. Fall back to analyzing the returning list.
    val returningEntries = parser.parseReturningList(cte.queryBlock)
    if (returningEntries.isEmpty()) return null
    return returningEntries
      .filter { !it.isJunk }
      .sortedBy { it.resultNumber }
      .map { entry -> !subAnalyzer.isNonNull(entry.expression) }
  }

  private fun analyzeSetOperationCteBody(
    queryBlock: String,
    previouslyResolvedCtes: Map<String, List<Boolean>>,
    cteName: String,
  ): List<Boolean>? {
    val subqueryRangeTable = parser.parseSubqueryRangeTable(queryBlock)
    if (subqueryRangeTable.isEmpty()) return null
    var seedResult: List<Boolean>? = null
    val branchResults = mutableListOf<List<Boolean>>()
    for ((_, branchBlock) in subqueryRangeTable) {
      // For recursive CTEs, the recursive branch references the CTE itself. Use the seed's
      // nullabilities so the self-reference resolves correctly instead of defaulting to nullable.
      val resolved = if (seedResult != null) {
        previouslyResolvedCtes + (cteName to seedResult)
      } else {
        previouslyResolvedCtes
      }
      val branchAnalyzer = buildCteSubAnalyzer(branchBlock, resolved)
      val result = branchAnalyzer.extractColumnNullability(branchBlock)
      if (result.isEmpty()) continue
      branchResults.add(result)
      if (seedResult == null) seedResult = result
    }
    if (branchResults.isEmpty()) return null
    val columnCount = branchResults.maxOf { it.size }
    return (0 until columnCount).map { col ->
      branchResults.any { it.getOrElse(col) { true } }
    }
  }

  private fun buildCteSubAnalyzer(
    queryBlock: String,
    previouslyResolvedCtes: Map<String, List<Boolean>>,
  ): NodeTreeNullabilityAnalyzer {
    val cteRangeTable = parser.parseRangeTable(queryBlock)
    val groupRteMap = parser.parseGroupRteMap(queryBlock)
    val innerCteRtes = parser.parseCteRangeTableEntries(queryBlock)
    val innerCteNotNull = buildMap<Pair<Int, Int>, Boolean> {
      for ((varno, cteName) in innerCteRtes) {
        val nullabilities = previouslyResolvedCtes[cteName] ?: continue
        nullabilities.forEachIndexed { columnIndex, nullable ->
          put(varno to (columnIndex + 1), !nullable)
        }
      }
    }
    return NodeTreeNullabilityAnalyzer(
      isStrict = isStrict,
      hasNonNullInitialValue = hasNonNullInitialValue,
      isSourceColumnNotNull = { varno, varattno ->
        val relid = cteRangeTable[varno]
        if (relid != null) {
          resolveColumnNotNull(relid, varattno)
        } else {
          val baseVar = groupRteMap[varno to varattno]
          if (baseVar != null) {
            val baseRelid = cteRangeTable[baseVar.first]
            baseRelid != null && resolveColumnNotNull(baseRelid, baseVar.second)
          } else {
            innerCteNotNull[varno to varattno] == true
          }
        }
      },
      isOuterJoinNullable = isOuterJoinNullable,
      isAlwaysNonNull = isAlwaysNonNull,
      isStrictButNullable = isStrictButNullable,
      isLagLeadWithDefault = isLagLeadWithDefault,
      resolveColumnNotNull = resolveColumnNotNull,
      jsonExistsOp = jsonExistsOp,
      jsonValueOp = jsonValueOp,
      jsonSerializeOp = jsonSerializeOp,
    )
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
