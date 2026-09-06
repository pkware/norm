package norm.generator

import norm.generator.NodeTreeNullabilityAnalyzer.Companion.MAX_EXPRESSION_DEPTH

/**
 * Substitutes every [PgNodeExpression.Var] in [expression] that references a PostgreSQL 18+ GROUP
 * RTE (see [groupExpressions]) with the resolved `:groupexprs` expression
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
 *   failure must not replace a `Var` that [groupRteMap]'s coarser, `Var`-only
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
 * Every non-`Var` node's children are walked via [PgNodeExpression.mapChildren], which is exhaustive
 * over the sealed [PgNodeExpression] hierarchy, so a buried GROUP RTE `Var` — e.g. `count(*) +
 * 0::bigint` when `0::bigint` is also the grouping key, sitting alongside an `Aggref` as an
 * [PgNodeExpression.OpExpr] argument — cannot silently escape substitution.
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
  if (expression !is PgNodeExpression.Var) return expression.mapChildren(recurse)
  val resolved = if (expression.levelsUp == 0) {
    groupExpressionsByVarno[expression.varno]?.let { groupExpressions ->
      groupExpressions.getOrNull(expression.varattno - 1)
    }
  } else {
    null
  }
  return if (resolved != null && resolved !is PgNodeExpression.Unknown) resolved else expression
}
