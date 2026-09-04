package norm.generator

import norm.generator.NodeTreeNullabilityAnalyzer.Companion.MAX_EXPRESSION_DEPTH

/**
 * Substitutes every [PgNodeExpression.Var] in [expression] that references a PostgreSQL 18+ GROUP
 * RTE (see [PgNodeTreeParser.parseGroupRteExpressions]) with the resolved `:groupexprs` expression
 * it stands in for, restoring the same tree shape PostgreSQL 16 and 17 produce directly (where the
 * original grouping-key expression is left in the target list, never masked behind a `Var`). This
 * lets [NodeTreeNullabilityAnalyzer] apply one set of nullability rules to every supported
 * PostgreSQL version, instead of needing PG18-specific reasoning layered on top.
 *
 * A `Var` is substituted only when all of the following hold — otherwise it is returned unchanged:
 * - [PgNodeExpression.Var.levelsUp] is `0`. A `Var` with `levelsUp > 0` refers to an outer query
 *   level, whose range table [groupExpressionsByVarno] does not describe — substituting against it
 *   would resolve against the wrong query level's GROUP RTE, if that varno happens to collide.
 * - [PgNodeExpression.Var.varno] is a key of [groupExpressionsByVarno].
 * - [PgNodeExpression.Var.varattno] is a valid 1-based index into that varno's resolved list (i.e.
 *   in `1..list.size`) — an out-of-range `varattno` means either malformed input or a node-tree
 *   shape this parser does not (yet) model correctly, and substituting against a nonexistent entry
 *   would silently invent an expression PostgreSQL never produced.
 * - The resolved expression is not [PgNodeExpression.Unknown] — an unmodelled node type or a parse
 *   failure must not replace a `Var` that [PgNodeTreeParser.parseGroupRteMap]'s coarser, `Var`-only
 *   resolution could still succeed at (see that method's continued use as a fallback wherever this
 *   substitution declines to apply).
 *
 * Nothing is inherited from the replaced `Var` — no union of [PgNodeExpression.Var.nullingRelations],
 * no carrying over [PgNodeExpression.Var.returningType]: the resolved `:groupexprs` expression already
 * carries whatever outer-join nulling information applies to it directly. On PostgreSQL 18
 * (`SELECT b.x, count(*) FROM t LEFT JOIN u b ON b.id = t.id GROUP BY b.x`), the target-list `Var`
 * referencing the GROUP RTE has an empty `:varnullingrels` (PostgreSQL does not propagate the outer
 * join's nulling relations onto the wrapper `Var` at all), while the GROUP RTE's own `:groupexprs`
 * entry — `{VAR :varno 2 :varattno 2 :varnullingrels (b 3) ...}` — carries the correct, non-empty
 * set. Inheriting anything from the replaced `Var` here would discard that correct information in
 * favor of the wrapper's misleadingly empty one.
 *
 * Single pass only: the resolved expression substituted in for a matching `Var` is returned
 * verbatim, never itself recursively substituted. This makes a hypothetical cycle within
 * `:groupexprs` (one grouping-key expression's resolution referencing a `Var` that is itself
 * GROUP-RTE-shaped) structurally unable to loop, without this function ever having to prove
 * PostgreSQL cannot emit such a cycle — a proof this function does not attempt.
 *
 * Child coverage mirrors [NodeTreeNullabilityAnalyzer.containsVarOutsideRelation], not
 * [NodeTreeNullabilityAnalyzer.safetyWalkChildren]: the latter drops several children (e.g.
 * [PgNodeExpression.Aggref]'s own arguments, [PgNodeExpression.JsonExpr]'s `PASSING`-adjacent
 * fields, everything past [PgNodeExpression.SubLink.outerOperand]) for reasons specific to the
 * grouping-set safety analysis it backs, which do not apply here. A GROUP RTE `Var` can appear
 * buried inside a non-`Aggref` node this substitution must walk into — e.g. `count(*) +
 * 0::bigint` when `0::bigint` is also the grouping key rewrites that operand of the `+` to a GROUP
 * RTE `Var`, sitting alongside the `Aggref` as an [PgNodeExpression.OpExpr] argument.
 * [PgNodeExpression.Aggref]'s own arguments are a different case: PostgreSQL never rewrites them at
 * all, on PG18 or otherwise — `count(lower(a))`/`string_agg(lower(a), ',')` under
 * `GROUP BY ROLLUP(lower(a))` keep the real base-relation `Var` inside the `AGGREF`'s `:args`,
 * because aggregate arguments are evaluated pre-grouping, before any GROUP RTE substitution could
 * apply. This substitution still walks into `Aggref`'s arguments anyway — not because any known PG18
 * shape needs it, but defensively: the exhaustive `when` below requires some branch for `Aggref`
 * regardless, and doing the real rewrite there (rather than treating it as a childless leaf) means a
 * future PostgreSQL shape, or an unrelated caller that hands this function an `Aggref` subtree
 * directly, cannot silently escape substitution. Every child this substitution's `when` retains must
 * be rewritten, or a buried GROUP RTE `Var` would silently survive un-substituted.
 *
 * The `when` below is exhaustive over the sealed [PgNodeExpression] hierarchy with no `else ->`
 * branch: adding a new [PgNodeExpression] subtype without updating this function fails the build,
 * rather than silently leaving that subtype's children unwalked.
 *
 * @param depth remaining recursion budget, mirroring [MAX_EXPRESSION_DEPTH]; once exhausted,
 *   [expression] is returned unchanged — failing toward the current, un-substituted (and therefore
 *   more nullable-leaning, since an unresolved GROUP RTE `Var` falls through to "source column not
 *   found") behavior, never toward inventing a substitution this function could not verify.
 */
internal fun substituteGroupRteVars(
  expression: PgNodeExpression,
  groupExpressionsByVarno: Map<Int, List<PgNodeExpression>>,
  depth: Int = MAX_EXPRESSION_DEPTH,
): PgNodeExpression {
  if (depth <= 0) return expression
  val recurse = { child: PgNodeExpression -> substituteGroupRteVars(child, groupExpressionsByVarno, depth - 1) }
  return when (expression) {
    is PgNodeExpression.Var -> {
      val resolved = if (expression.levelsUp == 0) {
        groupExpressionsByVarno[expression.varno]?.let { groupExpressions ->
          groupExpressions.getOrNull(expression.varattno - 1)
        }
      } else {
        null
      }
      if (resolved != null && resolved !is PgNodeExpression.Unknown) resolved else expression
    }

    is PgNodeExpression.Const,
    is PgNodeExpression.SqlValueFunction,
    is PgNodeExpression.NextValExpr,
    is PgNodeExpression.Unknown,
    -> expression

    is PgNodeExpression.FuncExpr -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.OpExpr -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.ScalarArrayOpExpr -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.CoalesceExpr -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.NullIfExpr -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.MinMaxExpr -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.Aggref -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.WindowFunc -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.SubLink -> expression.copy(outerOperand = expression.outerOperand?.let(recurse))
    is PgNodeExpression.CaseExpr -> expression.copy(
      resultExpressions = expression.resultExpressions.map(recurse),
      defaultResult = expression.defaultResult?.let(recurse),
      testExpression = expression.testExpression?.let(recurse),
      whenConditions = expression.whenConditions.map(recurse),
    )

    is PgNodeExpression.BoolExpr -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.RelabelType -> expression.copy(argument = recurse(expression.argument))
    is PgNodeExpression.CoerceViaIo -> expression.copy(argument = recurse(expression.argument))
    is PgNodeExpression.ArrayCoerceExpr -> expression.copy(argument = recurse(expression.argument))
    is PgNodeExpression.CollateExpr -> expression.copy(argument = recurse(expression.argument))
    is PgNodeExpression.CoerceToDomain -> expression.copy(argument = recurse(expression.argument))
    is PgNodeExpression.NullTest -> expression.copy(argument = recurse(expression.argument))
    is PgNodeExpression.BooleanTest -> expression.copy(argument = recurse(expression.argument))
    is PgNodeExpression.DistinctExpr -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.ArrayExpr -> expression.copy(elements = expression.elements.map(recurse))
    is PgNodeExpression.RowExpr -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.GroupingFunc -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.FieldSelect -> expression.copy(argument = recurse(expression.argument))
    is PgNodeExpression.JsonIsPredicate -> expression.copy(argument = recurse(expression.argument))
    is PgNodeExpression.JsonConstructorExpr -> expression.copy(arguments = expression.arguments.map(recurse))
    is PgNodeExpression.JsonExpr -> expression.copy(
      argument = recurse(expression.argument),
      onEmptyDefault = expression.onEmptyDefault?.let(recurse),
      onErrorDefault = expression.onErrorDefault?.let(recurse),
    )

    is PgNodeExpression.XmlExpr -> expression.copy(arguments = expression.arguments.map(recurse))
  }
}
