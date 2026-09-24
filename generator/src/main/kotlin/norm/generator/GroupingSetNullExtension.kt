package norm.generator

/**
 * Determines whether a target-list expression is immune to PostgreSQL's GROUPING SETS/CUBE/ROLLUP
 * null-extension mechanism — the planner-level substitution that replaces a result column's
 * computed value with `NULL` for a row belonging to a grouping set that omits it. Its answers are
 * meaningful only for a query block that uses GROUPING SETS, CUBE, or ROLLUP.
 *
 * @param isAlwaysNonNull Returns `true` for function OIDs that never return `null` for any
 *   combination of argument values, including when every argument is `null`, in the ordinary
 *   (non-`VARIADIC`) calling form.
 * @param isNonNullIffFirstArgumentNonNull Returns `true` for function OIDs that are non-null if and
 *   only if their first argument is non-null, regardless of any other argument's nullability, in
 *   the ordinary (non-`VARIADIC`) calling form.
 * @param isFoldableToConst Returns `true` for function/operator OIDs that are IMMUTABLE and not
 *   set-returning (`pg_proc.provolatile = 'i' AND NOT proretset`).
 */
internal class GroupingSetNullExtension(
  private val isAlwaysNonNull: (Int) -> Boolean,
  private val isNonNullIffFirstArgumentNonNull: (Int) -> Boolean,
  private val isFoldableToConst: (Int) -> Boolean,
) {

  /**
   * Returns the expressions of every [entries] item whose own [TargetEntry.sortGroupRef] is a
   * grouping key (per [groupingSortGroupRefs]), excluding any that are a bare
   * [PgNodeExpression.Const] or that [foldsToConst] — PostgreSQL's structural matching
   * (`search_indexed_tlist_for_non_var` in `setrefs.c`) refuses to match a `Const` node, and an
   * expression that folds to a `Const` before that matching pass runs (e.g. `upper('a')`) is, by
   * then, already a `Const` too: on PostgreSQL 16, 17, and 18, `SELECT upper('a') AS u1,
   * upper('a') AS u2, ... GROUP BY ROLLUP(upper('a'))` leaves the un-ref'd duplicate `u2` as `'A'`,
   * never `NULL`, unlike a duplicate that does not fold, e.g. `date_trunc('month', current_date)`,
   * where both occurrences are null-extended. Without this exclusion, a duplicate literal like
   * `SELECT 'ALL'::text AS l1, 'ALL'::text AS l2, ... GROUP BY ROLLUP('ALL'::text)` would be
   * wrongly forced nullable for `l2` on every supported version: `l2` stays `'ALL'`, never `NULL`,
   * even though `l1` (the ref'd occurrence) does become `NULL`.
   *
   * [entries] must already be run through [substituteGroupRteVars]. On PostgreSQL 18, unresolved
   * entries are bare `Var`s referencing the synthesized `*GROUP*` RTE rather than the `FuncExpr`/
   * `Const` this exclusion recognizes.
   */
  internal fun groupingKeyExpressions(
    entries: List<TargetEntry>,
    groupingSortGroupRefs: Set<Int>,
  ): Set<PgNodeExpression> = entries
    .filter { it.sortGroupRef != 0 && it.sortGroupRef in groupingSortGroupRefs }
    .map { it.expression }
    .filterNot { it is PgNodeExpression.Const || foldsToConst(it) }
    .toSet()

  /**
   * Returns `true` when [entry] must be forced nullable by PostgreSQL's GROUPING SETS/CUBE/ROLLUP
   * null-extension mechanism, because any of:
   * - [entry] is a grouping key itself: its [TargetEntry.sortGroupRef] is non-zero and appears in
   *   [groupingSortGroupRefs]. Alone this misses a *derived* expression over a key, e.g.
   *   `upper(lower(a))` when the key is `lower(a)` — caught by the third condition instead.
   * - [entry]'s expression structurally equals one of [groupingKeyExpressions] — a *duplicate*
   *   occurrence of a grouping key expression that PostgreSQL did not assign the matching
   *   `ressortgroupref` to. Not subsumed by the third condition, which proves only that a result
   *   cannot be forced null by a *deeper* subexpression being null-extended, not that the whole
   *   expression is swapped for `NULL` because it structurally repeats the grouping key.
   * - [entry]'s expression is not proven [isSafeFromGroupingSetNullExtension]. Alone this misses a
   *   bare-`Const` grouping key (e.g. `GROUP BY ROLLUP('ALL'::text)`): a `Const` is always
   *   [isSafeFromGroupingSetNullExtension], yet PostgreSQL still null-extends it when it is itself
   *   the grouping key — caught by the first or second condition instead.
   *
   * @param groupingKeyExpressions the set [GroupingSetNullExtension.groupingKeyExpressions] returns for
   *   the same target list.
   */
  internal fun forcesNullable(
    entry: TargetEntry,
    groupingSortGroupRefs: Set<Int>,
    groupingKeyExpressions: Set<PgNodeExpression>,
  ): Boolean {
    val isGroupingKey = (entry.sortGroupRef != 0 && entry.sortGroupRef in groupingSortGroupRefs) ||
      entry.expression in groupingKeyExpressions
    return isGroupingKey || !isSafeFromGroupingSetNullExtension(entry.expression, groupingKeyExpressions)
  }

  /**
   * Returns `true` if [expression] is provably immune to PostgreSQL's GROUPING SETS/CUBE/ROLLUP
   * null-extension mechanism — i.e. it cannot be the *value* PostgreSQL replaces with `NULL` for a
   * row belonging to a grouping set that omits it. Only meaningful for a query block that uses
   * GROUPING SETS, CUBE, or ROLLUP.
   *
   * Null-extension is a structural, planner-level substitution: PostgreSQL scans the target list
   * for stable, non-folded subexpressions that match a grouping key and replaces their computed
   * value with `NULL` outright, without evaluating the subexpression's own semantics — so even a
   * construct that is *semantically* always non-null (`EXISTS(...)`, `ARRAY[...]`, `IS NULL`) can
   * still be replaced with `NULL` if it structurally matches a grouping key.
   *
   * Two node kinds are exempt from ever matching a grouping key: `Aggref`/`GroupingFunc`
   * (aggregates are illegal inside `GROUP BY`, so nothing built on one can itself be a grouping
   * key), and a bare `Const` (PostgreSQL's matching specifically refuses to match a constant) —
   * though a `Const` wrapped in a non-folded coercion (e.g. a `text`-to-`timestamptz` cast) is a
   * real function call and does not inherit that exemption.
   *
   * Beyond those two, an expression is safe if it [foldsToConst]; or is a non-`VARIADIC`
   * [PgNodeExpression.FuncExpr] whose function [isAlwaysNonNull] (its own result cannot be forced
   * `null` by null-extending one of its arguments — e.g. `concat(a, '-')` stays `'-'`, never
   * `null`, when `a` alone, not the whole call, is the grouping key, PostgreSQL 16-18; this does not
   * apply to a `VARIADIC` call, since `concat(VARIADIC arr)` is `null` when `arr` itself is `null`,
   * also PostgreSQL 16-18); or a `WindowFunc` whose every child is itself safe (a window function
   * can never itself be a grouping key, but its arguments run over already-grouped, potentially
   * null-extended rows — `first_value(b) OVER (...)` is genuinely nullable); or, for everything else
   * including a bare `Var`, iff the expression's parsed descendants include at least one
   * `Aggref`/`GroupingFunc`/`WindowFunc` and every parsed child is itself safe. A bare `Var` has no
   * descendants, so it is never safe under this last rule. [isNonNullIffFirstArgumentNonNull] gets
   * its own conditional leg — safe iff its first argument is itself safe, since a `concat_ws`
   * separator can independently be a grouping key. [immuneByNoGroupingKeyMatch] is a structurally
   * different leg, for constructs with no per-node-kind rule at all, e.g. `now()`.
   *
   * This walk is only sound for a [PgNodeExpression] subtype whose parsed representation retains
   * every child the underlying Postgres node actually has. [PgNodeExpression.JsonExpr] and
   * [PgNodeExpression.Unknown] drop information this method cannot recover and are hardcoded unsafe
   * unconditionally — a `JSON_EXISTS`'s `PASSING` clause (e.g. `JSON_EXISTS(doc, '\$.a ? (@ == \$v)'
   * PASSING a AS v)`) is not parsed, so a `Var` living only there is invisible. The same treatment
   * applies once [depth] is exhausted. [PgNodeExpression.CaseExpr.testExpression] and
   * [PgNodeExpression.CaseExpr.whenConditions] exist purely so this walk can see a `Var` that
   * appears only in a `CASE`'s test/condition, e.g. `CASE a WHEN 'x' THEN 1 ELSE 2 END`.
   *
   * `XML_IS_XMLFOREST` and `XML_IS_XMLPI` are excluded from the always-safe `XmlExpr` case because
   * neither is total over `null` input: `SELECT xmlforest(lower(a) AS q), count(*) FROM t2 GROUP BY
   * ROLLUP(a)` returns `NULL` in the rollup summary row on PostgreSQL 16, 17, and 18. PostgreSQL has
   * no equality operator for `xml` or for default-`RETURNING` `json`, so neither an `XmlExpr` nor a
   * `JSON_OBJECT`/`JSON_ARRAY` yielding `json` can itself be a grouping key; `jsonb` does have one.
   *
   * The self-match guard (`expression in groupingKeyExpressions`) runs at every node the walk
   * reaches, not only the entry root, because a match can occur on a nested subexpression: `SELECT
   * count(*)::text || concat(a, b) FROM t2 GROUP BY ROLLUP(concat(a, b))` returns `NULL` on live
   * PostgreSQL 16 and 18, which only checking the root would miss.
   *
   * @param groupingKeyExpressions the same set [forcesNullable] receives.
   * @param depth remaining recursion budget, mirroring [MAX_EXPRESSION_DEPTH]; returns `false`
   *   (assume unsafe) once exhausted
   */
  internal fun isSafeFromGroupingSetNullExtension(
    expression: PgNodeExpression,
    groupingKeyExpressions: Set<PgNodeExpression>,
    depth: Int = MAX_EXPRESSION_DEPTH,
  ): Boolean {
    if (depth <= 0) return false
    if (expression in groupingKeyExpressions) return false
    if (foldsToConst(expression, depth)) return true
    if (expression is PgNodeExpression.FuncExpr && !expression.isVariadic && isAlwaysNonNull(expression.functionOid)) {
      return true
    }
    if (immuneByNoGroupingKeyMatch(expression, groupingKeyExpressions, depth)) return true
    return when (expression) {
      is PgNodeExpression.Aggref, is PgNodeExpression.GroupingFunc -> true
      is PgNodeExpression.Const -> true
      is PgNodeExpression.JsonExpr, is PgNodeExpression.Unknown -> false
      is PgNodeExpression.XmlExpr ->
        expression.op == PgNodeExpression.XML_IS_XMLELEMENT ||
          isDominatedWithSafeChildren(expression, groupingKeyExpressions, depth)

      is PgNodeExpression.JsonConstructorExpr ->
        expression.type == PgNodeExpression.JSON_CONSTRUCTOR_TYPE_OBJECT ||
          expression.type == PgNodeExpression.JSON_CONSTRUCTOR_TYPE_ARRAY ||
          isDominatedWithSafeChildren(expression, groupingKeyExpressions, depth)

      is PgNodeExpression.FuncExpr ->
        if (!expression.isVariadic && isNonNullIffFirstArgumentNonNull(expression.functionOid)) {
          expression.arguments.firstOrNull()
            ?.let { isSafeFromGroupingSetNullExtension(it, groupingKeyExpressions, depth - 1) } == true
        } else {
          isDominatedWithSafeChildren(expression, groupingKeyExpressions, depth)
        }

      is PgNodeExpression.WindowFunc ->
        safetyWalkChildren(expression).all { isSafeFromGroupingSetNullExtension(it, groupingKeyExpressions, depth - 1) }

      else -> isDominatedWithSafeChildren(expression, groupingKeyExpressions, depth)
    }
  }

  private fun isDominatedWithSafeChildren(
    expression: PgNodeExpression,
    groupingKeyExpressions: Set<PgNodeExpression>,
    depth: Int,
  ): Boolean = containsDominatingConstruct(expression, depth) &&
    safetyWalkChildren(expression).all { isSafeFromGroupingSetNullExtension(it, groupingKeyExpressions, depth - 1) }

  /**
   * Returns `true` when [expression] and every descendant [safetyWalkChildren] reaches contain
   * nothing GROUPING SETS/CUBE/ROLLUP null-extension could act on: no [PgNodeExpression.Var], no
   * lossily-parsed node, and no structural match against [groupingKeyExpressions]. A `Var`-free
   * expression's value is fixed for the row whichever grouping set that row belongs to, so `now()`
   * under `GROUP BY ROLLUP(a)` is immune.
   *
   * The lossy nodes are [PgNodeExpression.JsonExpr] and [PgNodeExpression.Unknown], plus
   * [PgNodeExpression.SubLink]: [PgNodeExpression.SubLink.subselectBlock] keeps the subselect as
   * unparsed raw text and [safetyWalkChildren] walks only [PgNodeExpression.SubLink.outerOperand],
   * so a correlated `Var` inside the subselect is invisible to the `Var` check.
   *
   * The structural-equality match is sound in this direction only: [PgNodeExpression]'s subtypes
   * retain a SUBSET of the fields PostgreSQL's own `equal()` compares (e.g.
   * [PgNodeExpression.Const] keeps only `isNull`), so this class's equality is COARSER than
   * PostgreSQL's — a false "no match" verdict can never arise from a field this parser dropped.
   *
   * @param depth remaining recursion budget, mirroring [MAX_EXPRESSION_DEPTH]; returns `false` (not
   *   provably immune) once exhausted.
   */
  private fun immuneByNoGroupingKeyMatch(
    expression: PgNodeExpression,
    groupingKeyExpressions: Set<PgNodeExpression>,
    depth: Int = MAX_EXPRESSION_DEPTH,
  ): Boolean {
    if (depth <= 0) return false
    if (expression is PgNodeExpression.Var) return false
    if (expression is PgNodeExpression.JsonExpr || expression is PgNodeExpression.Unknown) return false
    if (expression is PgNodeExpression.SubLink) return false
    if (expression in groupingKeyExpressions) return false
    return safetyWalkChildren(expression).all { immuneByNoGroupingKeyMatch(it, groupingKeyExpressions, depth - 1) }
  }

  /**
   * Returns `true` if [expression] provably constant-folds by the time PostgreSQL's planner reaches
   * the grouping-set null-extension substitution — i.e. it is a [PgNodeExpression.Const], or an
   * IMMUTABLE, non-set-returning function/operator call whose every argument itself [foldsToConst],
   * or a [PgNodeExpression.RelabelType] (a no-op type reinterpretation) over one, or a
   * [PgNodeExpression.ArrayExpr] whose every element does.
   *
   * PostgreSQL's null-extension substitution (`search_indexed_tlist_for_non_var` in `setrefs.c`)
   * runs at the end of planning and refuses to match a `Const`, while constant folding
   * (`eval_const_expressions`) runs early, well before that substitution — so an expression that
   * will fold to a `Const` by the time the substitution runs was never a candidate for it.
   *
   * IMMUTABLE only. A STABLE function — e.g. `date_trunc('month', current_date)`, which depends on
   * the current date — is not constant-folded and PostgreSQL does null-extend it when it matches a
   * grouping key. VOLATILE is not safe either — `GROUP BY random()` is legal SQL, and the
   * key-matching itself is structural (`equal()`), not a volatility check.
   *
   * [PgNodeExpression.CoerceViaIo], [PgNodeExpression.CoerceToDomain],
   * [PgNodeExpression.ArrayCoerceExpr], [PgNodeExpression.RowExpr],
   * [PgNodeExpression.SqlValueFunction], and [PgNodeExpression.NextValExpr] are deliberately not
   * treated as foldable: none of them expose a function/operator OID this class can check
   * immutability for. This matters concretely for [PgNodeExpression.CoerceViaIo]: an I/O-based cast
   * function can itself be STABLE (e.g. `timestamptz`'s output function depends on the session's
   * `TimeZone` setting).
   *
   * @param depth remaining recursion budget, mirroring [MAX_EXPRESSION_DEPTH]; returns `false` (not
   *   provably folding) once exhausted
   */
  private fun foldsToConst(expression: PgNodeExpression, depth: Int = MAX_EXPRESSION_DEPTH): Boolean {
    if (depth <= 0) return false
    return when (expression) {
      is PgNodeExpression.Const -> true
      is PgNodeExpression.FuncExpr ->
        isFoldableToConst(expression.functionOid) && expression.arguments.all { foldsToConst(it, depth - 1) }

      is PgNodeExpression.OpExpr ->
        isFoldableToConst(expression.operatorFunctionOid) &&
          expression.arguments.all { foldsToConst(it, depth - 1) }

      is PgNodeExpression.RelabelType -> foldsToConst(expression.argument, depth - 1)
      is PgNodeExpression.ArrayExpr -> expression.elements.all { foldsToConst(it, depth - 1) }
      else -> false
    }
  }

  /**
   * Returns `true` if [expression] itself, or any descendant reachable via [safetyWalkChildren], is
   * a [PgNodeExpression.Aggref], [PgNodeExpression.GroupingFunc], or [PgNodeExpression.WindowFunc].
   *
   * A pure presence check used by [isSafeFromGroupingSetNullExtension]'s aggregate-domination
   * requirement — it does not recurse into one of these three node types' own arguments once found,
   * since finding the node itself already answers the question.
   */
  private fun containsDominatingConstruct(expression: PgNodeExpression, depth: Int): Boolean {
    if (depth <= 0) return false
    return when (expression) {
      is PgNodeExpression.Aggref, is PgNodeExpression.GroupingFunc, is PgNodeExpression.WindowFunc -> true
      else -> safetyWalkChildren(expression).any { containsDominatingConstruct(it, depth - 1) }
    }
  }

  /**
   * Returns the child expressions of [expression] relevant to [isSafeFromGroupingSetNullExtension]
   * and [containsDominatingConstruct], deliberately narrower than the full structural
   * [PgNodeExpression.children]:
   * - [PgNodeExpression.Aggref] and [PgNodeExpression.GroupingFunc] are terminal for the domination
   *   check — finding one already answers the question, so their own arguments are never walked.
   * - [PgNodeExpression.JsonExpr] is lossy-parsed and hardcoded unsafe by its callers, so its
   *   children are never consulted here either.
   */
  private fun safetyWalkChildren(expression: PgNodeExpression): List<PgNodeExpression> = when (expression) {
    is PgNodeExpression.Aggref, is PgNodeExpression.GroupingFunc, is PgNodeExpression.JsonExpr -> emptyList()
    else -> expression.children
  }
}
