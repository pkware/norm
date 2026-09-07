package norm.generator

import norm.generator.NodeTreeNullabilityAnalyzer.Companion.MAX_EXPRESSION_DEPTH

/**
 * Evaluates nullability of result columns from a PostgreSQL `pg_node_tree` text
 * (from `pg_rewrite.ev_action` or `pg_proc.prosqlbody`).
 *
 * Uses [PgNodeTreeParser] to parse the `:targetList` and then recursively evaluates each
 * expression via [isNonNull]. This covers outer-join-induced nullability (VAR nodes with
 * non-empty `varnullingrels`), aggregate nullability, strict-function propagation, and more.
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
 * @param isAlwaysNonNull Returns `true` for function OIDs that never return `null` for any
 *   combination of argument values, including when every argument is `null` (e.g. `concat`, which
 *   renders a `null` argument as an empty string) — but only for the ordinary (non-`VARIADIC`)
 *   calling form. `concat(VARIADIC arr)` is `null` when `arr` itself is `null` (PostgreSQL 16-18);
 *   `isNonNull`'s [PgNodeExpression.FuncExpr] branch checks [PgNodeExpression.FuncExpr.isVariadic]
 *   before trusting this callback for that form.
 * @param isNeverNullForNonNullInput Returns `true` for function/operator OIDs that are proven total
 *   on non-null input — every combination of non-null arguments produces a non-null result (an
 *   error is fine; only a silent `null` return disqualifies a candidate). `pg_proc.proisstrict`
 *   alone cannot answer this: strict only guarantees NULL-in => NULL-out, never the converse, so
 *   this must be checked alongside [isStrict], never as a substitute for it. Verified only for the
 *   ordinary, element-wise calling convention: `isNonNull`'s [PgNodeExpression.FuncExpr] branch
 *   never consults this parameter for a `VARIADIC` call, since the array argument being non-null
 *   says nothing about whether an element inside it is.
 * @param isLagLeadWithDefault Returns `true` for the 3-argument overloads of `lag` and `lead` window
 *   functions, which return non-null when both the value and default arguments are non-null.
 * @param isFoldableToConst Returns `true` for function/operator OIDs that are IMMUTABLE and not
 *   set-returning (`pg_proc.provolatile = 'i' AND NOT proretset`).
 * @param isNonNullIffFirstArgumentNonNull Returns `true` for function OIDs that are non-null if and
 *   only if their first argument is non-null, regardless of any other argument's nullability, in
 *   the ORDINARY (non-`VARIADIC`) calling form — used for [isNonNull] evaluation of
 *   [PgNodeExpression.FuncExpr] when [PgNodeExpression.FuncExpr.isVariadic] is `false`. Backs
 *   `concat_ws`: its first argument is the separator, and `concat_ws(null, 'x', 'y')` is `null`
 *   even though the later, individually-null-tolerant arguments are non-null (PostgreSQL 16-18).
 *   `concat_ws(',', VARIADIC arr)` is a different case this parameter's guarantee does not cover:
 *   it is `null` when `arr` itself is `null` even though the literal separator is non-null (also
 *   true on PostgreSQL 16-18).
 * @param hasGroupingSets `true` when the query block this analyzer evaluates uses GROUPING SETS,
 *   CUBE, or ROLLUP (see [PgNodeTreeParser.hasGroupingSets]). When `true`, [extractColumnNullability]
 *   forces a result column nullable when it is itself a grouping key, or when its expression is not
 *   provably immune to the grouping-set null-extension mechanism. Defaults to `false` (ordinary
 *   [isNonNull] evaluation only) for query blocks without grouping sets.
 * @param isSubLinkSubqueryColumnNotNull Returns `true` when [subselectBlock] — the raw `{QUERY ...}`
 *   text of an `ANY_SUBLINK`'s or `ALL_SUBLINK`'s `:subselect` (see
 *   [PgNodeExpression.SubLink.subselectBlock]) — produces exactly one non-junk output column and
 *   that column is provably non-null. Defaults to `{ false }`: every construction site that does
 *   not wire this callback stays conservative (nullable), which is also correct for a nested
 *   sublink once the caller's own depth budget for this analysis is exhausted.
 * @param forceNewNullable `true` when a `RETURNING WITH (OLD AS o, NEW AS n)` reference to `NEW`
 *   (`Var.returningType == `[PgNodeExpression.VAR_RETURNING_TYPE_NEW]`) must be treated as
 *   unconditionally nullable. Set by the caller when the enclosing statement is a plain `DELETE`
 *   (`NEW` never exists — the row is gone; `NEW.col` is `NULL` for every row a `DELETE` returns) or
 *   a `MERGE` (an individual result row's `NEW` may or may not exist depending on which `WHEN`
 *   clause matched — e.g. `WHEN MATCHED THEN DELETE` leaves no `NEW` row). Left `false` (the
 *   default) for a plain `UPDATE`/`INSERT`, where the row a `RETURNING` clause reports on always
 *   has both an `OLD` and a `NEW` state, so `NEW` is exactly as trustworthy as an ordinary column
 *   reference.
 */
internal class NodeTreeNullabilityAnalyzer(
  private val isStrict: (Int) -> Boolean,
  private val hasNonNullInitialValue: (Int) -> Boolean,
  private val isSourceColumnNotNull: (varno: Int, varattno: Int) -> Boolean,
  private val isOuterJoinNullable: (nullingRelations: Set<Int>) -> Boolean,
  private val isAlwaysNonNull: (Int) -> Boolean = { false },
  private val isNeverNullForNonNullInput: (Int) -> Boolean = { false },
  private val isLagLeadWithDefault: (Int) -> Boolean = { false },
  private val isFoldableToConst: (Int) -> Boolean = { false },
  private val isNonNullIffFirstArgumentNonNull: (Int) -> Boolean = { false },
  private val isSubLinkSubqueryColumnNotNull: (subselectBlock: String) -> Boolean = { false },
  private val hasGroupingSets: Boolean = false,
  private val forceNewNullable: Boolean = false,
) {

  private val parser = PgNodeTreeParser()

  /**
   * Extracts per-column nullability from a `pg_node_tree` text using full expression evaluation.
   *
   * Uses [PgNodeTreeParser] to parse the target list, then evaluates each non-junk entry with
   * [isNonNull]. Returns `true` (nullable) when `isNonNull` returns `false`.
   *
   * Before that, every target-list entry's expression is run through [substituteGroupRteVars]
   * against [groupExpressions]'s result. On PostgreSQL 16/17 that map is always empty (no GROUP
   * RTE exists), so this is a no-op. On PostgreSQL 18+, a plain `GROUP BY` (not only `GROUPING
   * SETS`/`CUBE`/`ROLLUP`) creates a synthesized `*GROUP*` RTE, and every target-list `Var` that
   * references it is resolved back to the real grouping-key expression, restoring the same tree
   * shape 16/17 produce directly.
   *
   * The caller must fold CTE column not-null information into [isSourceColumnNotNull] so that `Var`
   * nodes referencing CTE range-table entries resolve correctly.
   *
   * @param nodeTreeText the raw text value of `pg_rewrite.ev_action`
   * @return one `Boolean` per result column (in column order), where `true` means the column may
   *   be `null`
   */
  fun extractColumnNullability(nodeTreeText: String): List<Boolean> {
    val parsedEntries = parser.parseTargetList(nodeTreeText)
    if (parsedEntries.isEmpty()) return emptyList()
    val groupRteExpressions = parser.parseRangeTableEntries(nodeTreeText).groupExpressions(parser)
    val entries = if (groupRteExpressions.isEmpty()) {
      parsedEntries
    } else {
      parsedEntries.map { entry ->
        entry.copy(expression = substituteGroupRteVars(entry.expression, groupRteExpressions))
      }
    }
    val groupingSortGroupRefs = if (hasGroupingSets) parser.parseGroupingSortGroupRefs(nodeTreeText) else emptySet()
    val groupingKeyExpressions = if (hasGroupingSets) {
      entries
        .filter { it.sortGroupRef != 0 && it.sortGroupRef in groupingSortGroupRefs }
        .map { it.expression }
        .filterNot { it is PgNodeExpression.Const || foldsToConst(it) }
        .toSet()
    } else {
      emptySet()
    }

    return entries
      .filter { !it.isJunk }
      .sortedBy { it.resultNumber }
      .map { entry -> !isEffectivelyNonNull(entry, groupingSortGroupRefs, groupingKeyExpressions) }
  }

  /**
   * Evaluates whether [entry] is guaranteed non-null, applying the GROUPING SETS/CUBE/ROLLUP
   * override before falling back to ordinary [isNonNull] evaluation.
   *
   * When [hasGroupingSets] is `true`, [entry] is forced nullable when any of:
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
   * @param groupingKeyExpressions the expressions of every entry whose own [TargetEntry.sortGroupRef]
   *   is a grouping key (per [groupingSortGroupRefs]), excluding any that are a bare
   *   [PgNodeExpression.Const] or that [foldsToConst] — PostgreSQL's structural matching
   *   (`search_indexed_tlist_for_non_var` in `setrefs.c`) refuses to match a `Const` node, and an
   *   expression that folds to a `Const` before that matching pass runs (e.g. `upper('a')`) is, by
   *   then, already a `Const` too: on PostgreSQL 16, 17, and 18, `SELECT upper('a') AS u1,
   *   upper('a') AS u2, ... GROUP BY ROLLUP(upper('a'))` leaves the un-ref'd duplicate `u2` as
   *   `'A'`, never `NULL`, unlike a duplicate that does not fold, e.g.
   *   `date_trunc('month', current_date)`, where both occurrences are null-extended. Without this
   *   exclusion, a duplicate literal like `SELECT 'ALL'::text AS l1, 'ALL'::text AS l2, ... GROUP BY
   *   ROLLUP('ALL'::text)` would be wrongly forced nullable for `l2` on every supported version:
   *   `l2` stays `'ALL'`, never `NULL`, even though `l1` (the ref'd occurrence) does become `NULL`.
   *
   *   On PostgreSQL 18, [entry] arrives here already run through [substituteGroupRteVars], so
   *   `u2`/`l2` arrive as the genuine `FuncExpr`/`Const` PostgreSQL 16/17 always showed, not a bare
   *   `Var` referencing the synthesized `*GROUP*` RTE, so this exclusion rescues them identically
   *   on every supported version.
   */
  private fun isEffectivelyNonNull(
    entry: TargetEntry,
    groupingSortGroupRefs: Set<Int>,
    groupingKeyExpressions: Set<PgNodeExpression>,
  ): Boolean {
    if (hasGroupingSets) {
      val isGroupingKey = (entry.sortGroupRef != 0 && entry.sortGroupRef in groupingSortGroupRefs) ||
        entry.expression in groupingKeyExpressions
      if (isGroupingKey || !isSafeFromGroupingSetNullExtension(entry.expression, groupingKeyExpressions)) return false
    }
    return isNonNull(entry.expression)
  }

  /**
   * Returns `true` if [expression] is provably immune to PostgreSQL's GROUPING SETS/CUBE/ROLLUP
   * null-extension mechanism — i.e. it cannot be the *value* PostgreSQL replaces with `NULL` for a
   * row belonging to a grouping set that omits it. Only meaningful when [hasGroupingSets] is `true`.
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
   * @param groupingKeyExpressions the same set [isEffectivelyNonNull] receives.
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
      is PgNodeExpression.Var ->
        // An OLD row may not exist for this result row at all (e.g. MERGE ... WHEN NOT MATCHED
        // THEN INSERT), so its NOT NULL constraint and outer-join structure prove nothing; the
        // same applies to NEW whenever forceNewNullable says so.
        //
        // levelsUp == 0 is required because a Var with levelsUp > 0 indexes an ENCLOSING query's
        // range table, not the current block's — this block's isSourceColumnNotNull, groupRteMap,
        // and qual narrowing are all keyed against the CURRENT block's varnos, so resolving an
        // outer-level varno against them could read the wrong column's constraint entirely.
        expression.levelsUp == 0 &&
          expression.returningType != PgNodeExpression.VAR_RETURNING_TYPE_OLD &&
          !(expression.returningType == PgNodeExpression.VAR_RETURNING_TYPE_NEW && forceNewNullable) &&
          !isOuterJoinNullable(expression.nullingRelations) &&
          isSourceColumnNotNull(expression.varno, expression.varattno)

      is PgNodeExpression.Const -> !expression.isNull
      is PgNodeExpression.FuncExpr ->
        if (expression.isVariadic) {
          if (isAlwaysNonNull(expression.functionOid) || isNonNullIffFirstArgumentNonNull(expression.functionOid)) {
            // VARIADIC passes the array argument itself as one value, not exploded into elements,
            // so both guarantees are unsound here as stated: concat(VARIADIC arr) and
            // concat_ws(',', VARIADIC arr) are both null when arr itself is null (PostgreSQL
            // 16-18). Requiring every argument non-null is sound for both in this form (the
            // separator is still one of the arguments) and preserves real precision
            // (concat_ws(',', VARIADIC ARRAY['a', NULL]) is 'a', still non-null).
            expression.arguments.all(recurse)
          } else {
            // Does not fall through to isStrict/isNeverNullForNonNullInput: that safe-list's
            // "total on non-null input" guarantee was verified only for the ordinary, element-wise
            // calling convention. For a VARIADIC call, recurse() on the array argument is
            // unconditionally true regardless of NULL elements inside it, so that leg would prove
            // nothing about an internal NULL element even if reached.
            false
          }
        } else {
          isAlwaysNonNull(expression.functionOid) ||
            (
              isNonNullIffFirstArgumentNonNull(expression.functionOid) &&
                expression.arguments.firstOrNull()?.let(recurse) == true
              ) ||
            (
              isStrict(expression.functionOid) &&
                isNeverNullForNonNullInput(expression.functionOid) &&
                expression.arguments.all(recurse)
              )
        }

      is PgNodeExpression.OpExpr ->
        isStrict(expression.operatorFunctionOid) &&
          isNeverNullForNonNullInput(expression.operatorFunctionOid) &&
          expression.arguments.all(recurse)

      is PgNodeExpression.ScalarArrayOpExpr ->
        isStrict(expression.operatorFunctionOid) &&
          isNeverNullForNonNullInput(expression.operatorFunctionOid) &&
          expression.arguments.all(recurse)

      is PgNodeExpression.CoalesceExpr -> expression.arguments.any(recurse)
      is PgNodeExpression.NullIfExpr -> false // can always return null
      is PgNodeExpression.MinMaxExpr -> expression.arguments.all(recurse)
      is PgNodeExpression.Aggref -> hasNonNullInitialValue(expression.aggregateFunctionOid)
      is PgNodeExpression.WindowFunc -> evaluateWindowFunc(expression, recurse)
      is PgNodeExpression.SubLink ->
        expression.subLinkType == PgNodeExpression.SUBLINK_TYPE_EXISTS ||
          expression.subLinkType == PgNodeExpression.SUBLINK_TYPE_ARRAY ||
          // ANY_SUBLINK (`x = ANY (subquery)` / `x IN (subquery)`) is three-valued: on PostgreSQL
          // 17, `a = ANY (SELECT v FROM u)` is NULL, not FALSE, when u.v is nullable, u has no
          // matching row, and a NULL row is present. Proving non-null requires all three: the
          // outer operand is non-null; the comparison operator is both isStrict and
          // isNeverNullForNonNullInput; and the subquery's single output column is itself provably
          // non-null. testExpressionOperatorOid is null for the multi-column `(a, b) IN (SELECT p,
          // q FROM w)` row-comparison form, so that form always falls through to nullable.
          //
          // ALL_SUBLINK (`x op ALL (subquery)`) gets the identical proof, being ANY's dual over AND
          // instead of OR. An empty subquery is TRUE for ALL (FALSE for ANY) — non-null either way.
          // `NOT IN` desugars to a BOOLEXPR not around an ANY_SUBLINK, never an ALL_SUBLINK. The
          // multi-column row-comparison ALL forms fail automatically for the same reason as above.
          //
          // ROWCOMPARE_SUBLINK (`(a, id) < (SELECT v, 1 FROM u)`, no ALL/ANY keyword) is excluded
          // despite sharing SUBLINK's shape: an EMPTY subquery yields NULL for ROWCOMPARE, so no
          // combination of the three conditions can rescue it.
          (
            (
              expression.subLinkType == PgNodeExpression.SUBLINK_TYPE_ANY ||
                expression.subLinkType == PgNodeExpression.SUBLINK_TYPE_ALL
              ) &&
              expression.outerOperand?.let(recurse) == true &&
              expression.testExpressionOperatorOid?.let { operatorOid ->
                isStrict(operatorOid) && isNeverNullForNonNullInput(operatorOid)
              } == true &&
              expression.subselectBlock?.let(isSubLinkSubqueryColumnNotNull) == true
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
      is PgNodeExpression.JsonConstructorExpr -> evaluateJsonConstructorExpr(expression, recurse)
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
    // NTILE is strict and always returns non-null from non-null input, so it is on the
    // isNeverNullForNonNullInput safe-list. Other strict window functions (FIRST_VALUE,
    // LAST_VALUE, NTH_VALUE, LAG/LEAD 1-2 arg) can return null at frame boundaries and are
    // excluded by omission from that safe-list.
    if (isStrict(expression.windowFunctionOid) &&
      isNeverNullForNonNullInput(expression.windowFunctionOid) &&
      expression.arguments.all(recurse)
    ) {
      return true
    }
    return false
  }

  /**
   * Evaluates a `JSON_OBJECT`/`JSON_ARRAY`/`JSON_OBJECTAGG`/`JSON_ARRAYAGG`/`JSON()`/`JSON_SCALAR`/
   * `JSON_SERIALIZE` constructor, branching on [PgNodeExpression.JsonConstructorExpr.type].
   *
   * - `OBJECT`/`ARRAY`: unconditionally non-null, including the zero-argument forms. `ABSENT ON
   *   NULL`/`NULL ON NULL` only change the JSON document's CONTENT — whether a key with a JSON-`null`
   *   value is omitted or kept — never whether the SQL-level result is `null`, so `:absent_on_null`
   *   and `:unique` are deliberately not parsed at all.
   * - `OBJECTAGG`/`ARRAYAGG`: [PgNodeExpression.JsonConstructorExpr.arguments] is always EMPTY for
   *   these two, so an arguments-based rule would be vacuously true; the underlying
   *   [PgNodeExpression.Aggref]/[PgNodeExpression.WindowFunc] in
   *   [PgNodeExpression.JsonConstructorExpr.function] is recursed into instead, reaching the existing
   *   rules that already report an aggregate over an empty group nullable.
   * - `PARSE`/`SCALAR`/`SERIALIZE`: strict single-argument constructs, non-null only when there is an
   *   argument and every argument is non-null.
   * - Any other code falls through to `false` (nullable), the safe default.
   */
  private fun evaluateJsonConstructorExpr(
    expression: PgNodeExpression.JsonConstructorExpr,
    recurse: (PgNodeExpression) -> Boolean,
  ): Boolean = when (expression.type) {
    PgNodeExpression.JSON_CONSTRUCTOR_TYPE_OBJECT, PgNodeExpression.JSON_CONSTRUCTOR_TYPE_ARRAY -> true

    PgNodeExpression.JSON_CONSTRUCTOR_TYPE_OBJECTAGG, PgNodeExpression.JSON_CONSTRUCTOR_TYPE_ARRAYAGG ->
      expression.function?.let(recurse) == true

    PgNodeExpression.JSON_CONSTRUCTOR_TYPE_PARSE,
    PgNodeExpression.JSON_CONSTRUCTOR_TYPE_SCALAR,
    PgNodeExpression.JSON_CONSTRUCTOR_TYPE_SERIALIZE,
    -> expression.arguments.isNotEmpty() && expression.arguments.all(recurse)

    else -> false
  }

  private fun evaluateJsonExpr(
    expression: PgNodeExpression.JsonExpr,
    recurse: (PgNodeExpression) -> Boolean,
  ): Boolean = when (expression.op) {
    PgNodeExpression.JSON_EXISTS_OP -> recurse(expression.argument) &&
      isKnownNonNullJsonExistsErrorBehavior(
        expression.onError,
      )

    // JSON_TABLE_OP (3) has no branch: its JsonExpr nodes never reach this method — see
    // PgNodeExpression.JSON_TABLE_OP. The `else -> false` below would be the safe answer anyway.
    PgNodeExpression.JSON_QUERY_OP -> {
      val emptyOk = isKnownNonNullJsonBehavior(expression.onEmpty) && expression.onEmptyDefault?.let(recurse) != false
      val errorOk = isKnownNonNullJsonBehavior(expression.onError) && expression.onErrorDefault?.let(recurse) != false
      recurse(expression.argument) && emptyOk && errorOk
    }

    // JSON_VALUE unwraps a path match to an SQL/JSON `null` value into a genuine SQL NULL. That is
    // a successful match, not the "no match" (EMPTY)/"error" (ERROR) case the ON EMPTY/ON ERROR
    // clauses control, so no combination of those codes rules the successful-match-to-JSON-null
    // case out. PostgreSQL 17 and 18: `JSON_VALUE('{"name": null}'::jsonb, '$.name'
    // RETURNING TEXT ERROR ON EMPTY ERROR ON ERROR) IS NULL` is `true`.
    PgNodeExpression.JSON_VALUE_OP -> false

    else -> false
  }

  /**
   * Returns `true` only for a `JsonBehaviorType` code confirmed (on PostgreSQL 17 and
   * 18) to make a `JSON_QUERY` `ON EMPTY`/`ON ERROR` clause produce a definite, non-null outcome —
   * an allow-list. An unrecognized code defaults to nullable, the safe direction.
   *
   * The four allowed codes:
   * - [PgNodeExpression.JSON_BEHAVIOR_ERROR]: raises a runtime error rather than returning a value.
   * - [PgNodeExpression.JSON_BEHAVIOR_EMPTY_ARRAY]/[PgNodeExpression.JSON_BEHAVIOR_EMPTY_OBJECT]:
   *   substitute Postgres's own internal `[]`/`{}` `jsonb` constant, never a user-supplied
   *   expression.
   * - [PgNodeExpression.JSON_BEHAVIOR_DEFAULT]: the one code backed by a genuinely user-supplied
   *   expression (`DEFAULT expr ON EMPTY`/`ON ERROR`), which is why [emptyOk]/[errorOk] recurse into
   *   it rather than trusting the behavior code alone; `DEFAULT null::jsonb ON EMPTY` is legal and
   *   must not be treated as non-null.
   *
   * Deliberately not on this list: [PgNodeExpression.JSON_BEHAVIOR_NULL] (explicitly nullable by
   * definition); `JSON_BEHAVIOR_TRUE`/`FALSE`/`UNKNOWN` (Postgres rejects all three for a
   * `JSON_QUERY` `ON EMPTY`/`ON ERROR` clause, so they never appear here); and `JSON_TABLE`'s
   * per-column `ON EMPTY`/`ON ERROR` (a `JSON_TABLE` column resolves to a plain `VAR`, never a
   * [PgNodeExpression.JsonExpr] this method ever sees).
   */
  private fun isKnownNonNullJsonBehavior(behaviorType: Int): Boolean =
    behaviorType == PgNodeExpression.JSON_BEHAVIOR_ERROR ||
      behaviorType == PgNodeExpression.JSON_BEHAVIOR_EMPTY_ARRAY ||
      behaviorType == PgNodeExpression.JSON_BEHAVIOR_EMPTY_OBJECT ||
      behaviorType == PgNodeExpression.JSON_BEHAVIOR_DEFAULT

  /**
   * Returns `true` only for a `JsonBehaviorType` code confirmed (on PostgreSQL 17) to
   * make `JSON_EXISTS`'s `ON ERROR` clause produce a definite, non-null (`true`/`false`) outcome, or
   * raise an error rather than returning a value at all. `JSON_EXISTS` has no `ON EMPTY` clause.
   *
   * With no `ON ERROR` clause written at all, Postgres materializes
   * [PgNodeExpression.JSON_BEHAVIOR_FALSE] — the SQL-standard default — so an absent clause is
   * exactly as safe as writing `FALSE ON ERROR` explicitly. Deliberately not on this list:
   * [PgNodeExpression.JSON_BEHAVIOR_UNKNOWN], which produces a genuine SQL NULL on a path error, and
   * [PgNodeExpression.JSON_BEHAVIOR_NULL]/`EMPTY_ARRAY`/`EMPTY_OBJECT`/`DEFAULT`, which Postgres's
   * parser rejects outright for `JSON_EXISTS`'s `ON ERROR` clause.
   */
  private fun isKnownNonNullJsonExistsErrorBehavior(behaviorType: Int): Boolean =
    behaviorType == PgNodeExpression.JSON_BEHAVIOR_ERROR ||
      behaviorType == PgNodeExpression.JSON_BEHAVIOR_TRUE ||
      behaviorType == PgNodeExpression.JSON_BEHAVIOR_FALSE

  /**
   * Evaluates an `XMLELEMENT`/`XMLFOREST`/`XMLPI`/`XMLCONCAT`/`XMLROOT`/`XMLPARSE`/`XMLSERIALIZE`
   * construct, branching on [PgNodeExpression.XmlExpr.op]. Only `XMLELEMENT` is total over `null`
   * input. PostgreSQL 16, 17 and 18 agree:
   * - `xmlelement(name e, NULL::text)` is not `null` — a null child renders as empty content, and a
   *   null `xmlattributes` value omits that attribute, so the element tag itself always materializes.
   * - `xmlforest(NULL::text AS q)` is `null`, while `xmlforest(NULL::text AS q, 'x' AS r)` is not —
   *   a null field is omitted and the result nulls only once every field is gone.
   * - `xmlpi(name php, NULL::text)` is `null`, while the content-less `xmlpi(name php)` is not.
   *
   * [PgNodeExpression.XmlExpr.arguments] merges the node's `:named_args` with its `:args`, so an
   * `XMLFOREST` field value — which lives in `:named_args` — is visible to the check rather than
   * silently absent. Any other op code falls through to `false` (nullable), the safe default.
   */
  private fun evaluateXmlExpr(expression: PgNodeExpression.XmlExpr, recurse: (PgNodeExpression) -> Boolean): Boolean =
    when (expression.op) {
      PgNodeExpression.XML_IS_XMLELEMENT -> true

      PgNodeExpression.XML_IS_XMLFOREST -> expression.arguments.any(recurse)

      PgNodeExpression.XML_IS_XMLPI,
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
     * Returns the `(varno, varattno)` pairs that the query's `WHERE` clause proves non-null.
     *
     * A row only survives `WHERE` when the qual evaluates to TRUE, so every top-level `AND`
     * conjunct is known to be non-null, and non-nullness propagates down through strict
     * operators and functions to the leaf `Var`s.
     *
     * Deliberately excluded, because they do not prove non-nullness for every surviving row:
     * quals under `OR` or `NOT`, `ON` clauses of joins (see [PgNodeTreeParser.parseWhereQuals]),
     * `HAVING`, and `Var`s with `levelsUp` greater than `0` (whose `varno` belongs to an
     * enclosing query's range table).
     *
     * Outer-join nullability is not considered here; the caller's `isOuterJoinNullable` check in
     * the [isNonNull] `Var` branch still applies and still wins.
     *
     * @param nodeTreeText the raw text of the query block to inspect
     * @param isStrict returns `true` when the function or operator OID is strict
     */
    internal fun qualProvenNonNullVars(nodeTreeText: String, isStrict: (Int) -> Boolean): Set<Pair<Int, Int>> {
      val parser = PgNodeTreeParser()
      val whereQuals = parser.parseWhereQuals(nodeTreeText) ?: return emptySet()
      return buildSet {
        collectConjunctProvenVars(whereQuals, this, isStrict, MAX_EXPRESSION_DEPTH)
      }
    }

    /**
     * `true` if [expression] contains an ordinary `Var` (`returningType == 0` — i.e. not an `OLD`
     * or `NEW` reference, which carry their own independent, already-safe handling) whose `varno`
     * is anything other than [relationVarno].
     *
     * A `RETURNING` that only reads the target relation's own columns (always present, whichever
     * `WHEN` clause matched) or `OLD`/`NEW` references never needs a `MERGE`'s per-relation
     * match-optionality resolved via `EXPLAIN` at all. Every non-`Var` branch delegates to
     * [PgNodeExpression.children], so no variant can silently keep a child unwalked — e.g. a
     * `RETURNING JSON_QUERY(source.column, ...)` is still walked into.
     *
     * @param depth remaining recursion budget; exhausting it answers `true` (needs resolving)
     *   rather than `false`, the same fail-toward-conservative default every depth guard in this
     *   file uses
     */
    internal fun containsVarOutsideRelation(
      expression: PgNodeExpression,
      relationVarno: Int,
      depth: Int = MAX_EXPRESSION_DEPTH,
    ): Boolean {
      if (depth <= 0) return true
      val recurse = { childExpression: PgNodeExpression ->
        containsVarOutsideRelation(childExpression, relationVarno, depth - 1)
      }
      if (expression !is PgNodeExpression.Var) return expression.children.any(recurse)
      return expression.returningType == PgNodeExpression.VAR_RETURNING_TYPE_NORMAL &&
        expression.varno != relationVarno
    }

    /**
     * Collects `Var`s proven non-null by [expression] when [expression] is a top-level `WHERE`
     * conjunct (i.e. it must evaluate to TRUE for the row to survive).
     */
    private fun collectConjunctProvenVars(
      expression: PgNodeExpression,
      sink: MutableSet<Pair<Int, Int>>,
      isStrict: (Int) -> Boolean,
      depth: Int,
    ) {
      if (depth <= 0) return
      when {
        expression is PgNodeExpression.BoolExpr && expression.boolOperator == PgNodeExpression.BOOL_OPERATOR_AND ->
          expression.arguments.forEach { collectConjunctProvenVars(it, sink, isStrict, depth - 1) }

        expression is PgNodeExpression.NullTest && expression.nullTestType == PgNodeExpression.NULL_TEST_IS_NOT_NULL ->
          collectStrictLeafVars(expression.argument, sink, isStrict, depth - 1)

        // The conjunct must be TRUE, therefore non-null, so the same propagation applies. This
        // covers a bare boolean column, an OpExpr, and a FuncExpr.
        else -> collectStrictLeafVars(expression, sink, isStrict, depth - 1)
      }
    }

    /**
     * Given that [expression] is known to be non-null, collects the `Var`s that must also be
     * non-null for that to hold.
     */
    private fun collectStrictLeafVars(
      expression: PgNodeExpression,
      sink: MutableSet<Pair<Int, Int>>,
      isStrict: (Int) -> Boolean,
      depth: Int,
    ) {
      if (depth <= 0) return
      when (expression) {
        is PgNodeExpression.Var ->
          if (expression.levelsUp == 0) sink.add(expression.varno to expression.varattno)

        is PgNodeExpression.RelabelType -> collectStrictLeafVars(expression.argument, sink, isStrict, depth - 1)
        is PgNodeExpression.CoerceViaIo -> collectStrictLeafVars(expression.argument, sink, isStrict, depth - 1)
        is PgNodeExpression.CollateExpr -> collectStrictLeafVars(expression.argument, sink, isStrict, depth - 1)
        is PgNodeExpression.CoerceToDomain -> collectStrictLeafVars(expression.argument, sink, isStrict, depth - 1)
        is PgNodeExpression.ArrayCoerceExpr -> collectStrictLeafVars(expression.argument, sink, isStrict, depth - 1)

        is PgNodeExpression.FuncExpr ->
          if (isStrict(expression.functionOid)) {
            expression.arguments.forEach { collectStrictLeafVars(it, sink, isStrict, depth - 1) }
          }

        is PgNodeExpression.OpExpr ->
          if (isStrict(expression.operatorFunctionOid)) {
            expression.arguments.forEach { collectStrictLeafVars(it, sink, isStrict, depth - 1) }
          }

        is PgNodeExpression.ScalarArrayOpExpr ->
          if (isStrict(expression.operatorFunctionOid)) {
            if (expression.useOr) {
              // ANY: every operand (scalar and array) must be non-null for a strict operator to
              // produce the non-null TRUE this conjunct requires.
              expression.arguments.forEach { collectStrictLeafVars(it, sink, isStrict, depth - 1) }
            } else {
              // ALL: `x <> ALL(ARRAY[]::text[])` is TRUE even when `x` is `null`, so the scalar
              // operand is not proven. A `null` array yields `null` for both ANY and ALL, so the
              // array operand always is proven.
              expression.arguments.getOrNull(1)?.let { collectStrictLeafVars(it, sink, isStrict, depth - 1) }
            }
          }

        // Notable NULL-tolerant cases that do not propagate non-nullness to their arguments:
        // CoalesceExpr, NullIfExpr, MinMaxExpr, CaseExpr, DistinctExpr, BooleanTest, SubLink.
        else -> Unit
      }
    }
  }
}
