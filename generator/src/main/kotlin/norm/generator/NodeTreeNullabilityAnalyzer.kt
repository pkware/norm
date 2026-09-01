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
 * @param isAlwaysNonNull Returns `true` for function OIDs that never return `null` for ANY
 *   combination of argument values, including when every argument is `null` (e.g., `concat`, which
 *   renders a `null` argument as an empty string) — but ONLY for the ORDINARY (non-`VARIADIC`)
 *   calling form. `concat(VARIADIC arr)` IS `null` when `arr` itself is `null` (verified live on
 *   PostgreSQL 16, 17, and 18): `isNonNull`'s [PgNodeExpression.FuncExpr] branch checks
 *   [PgNodeExpression.FuncExpr.isVariadic] before trusting this callback at all, and this
 *   parameter's own guarantee never covers that form. See
 *   [PgCatalogLoader.alwaysNonNullFunctionOids]'s KDoc for why the non-`VARIADIC` guarantee must
 *   be unconditional in every argument position — `concat_ws` is deliberately NOT eligible here
 *   despite also being non-strict, because it depends on WHICH argument is `null` (only a `null`
 *   separator, its first argument, makes the result `null`); see
 *   [isNonNullIffFirstArgumentNonNull] for how that case is modeled instead.
 * @param isNeverNullForNonNullInput Returns `true` for function/operator OIDs that are proven TOTAL
 *   on non-null input — every combination of non-null arguments produces a non-null result (an
 *   ERROR is fine; only a silent `null` return disqualifies a candidate). `pg_proc.proisstrict`
 *   alone cannot answer this: STRICT only guarantees NULL-in => NULL-out, never the converse, so
 *   this is required as an ADDITIONAL conjunct alongside [isStrict] below, never a substitute for
 *   it. See [PgCatalogLoader.neverNullForNonNullInputOids] for the safe-list this is normally
 *   backed by, and why omission from that list is always the safe default. That safe-list's
 *   verification (see `SafeListSweepTest`) covers only the ORDINARY, element-wise calling
 *   convention — `isNonNull`'s [PgNodeExpression.FuncExpr] branch never consults this
 *   parameter at all for a `VARIADIC` call (see [PgNodeExpression.FuncExpr.isVariadic]'s KDoc):
 *   the array argument being non-null says nothing about whether an element inside it is, and no
 *   function on the safe-list this backs is variadic today, so trusting it for that shape has
 *   never been verified.
 * @param isLagLeadWithDefault Returns `true` for the 3-argument overloads of `lag` and `lead` window
 *   functions, which return non-null when both the value and default arguments are non-null.
 * @param isFoldableToConst Returns `true` for function/operator OIDs that are IMMUTABLE and not
 *   set-returning (`pg_proc.provolatile = 'i' AND NOT proretset`). Used by
 *   [isSafeFromGroupingSetNullExtension]'s [foldsToConst] leg — see that method's KDoc.
 * @param isNonNullIffFirstArgumentNonNull Returns `true` for function OIDs that are non-null if and
 *   only if their first argument is non-null, regardless of any other argument's nullability, in
 *   the ORDINARY (non-`VARIADIC`) calling form — used for [isNonNull] evaluation of
 *   [PgNodeExpression.FuncExpr] when [PgNodeExpression.FuncExpr.isVariadic] is `false`. Currently
 *   backs `concat_ws`: its first argument is the separator, and `concat_ws(null, 'x', 'y')` is
 *   `null` even though the later, individually-null-tolerant arguments are non-null (verified live
 *   on PostgreSQL 16, 17, and 18). `concat_ws(',', VARIADIC arr)` is a DIFFERENT case this
 *   parameter's guarantee does NOT cover: it is `null` when `arr` itself is `null` even though the
 *   literal separator is non-null (also verified live on PostgreSQL 16, 17, and 18). See
 *   [PgCatalogLoader.nonNullIffFirstArgumentNonNullFunctionOids] for the safe-list this is normally
 *   backed by, and why it is intentionally separate from [isAlwaysNonNull]. Also consulted by
 *   [isSafeFromGroupingSetNullExtension] for the identical non-`VARIADIC` `FuncExpr` shape.
 * @param hasGroupingSets `true` when the query block this analyzer evaluates uses GROUPING SETS,
 *   CUBE, or ROLLUP (see [PgNodeTreeParser.hasGroupingSets]). When `true`, [extractColumnNullability]
 *   forces a result column nullable when it is itself a grouping key, or when its expression is not
 *   provably immune to the grouping-set null-extension mechanism — see [isSafeFromGroupingSetNullExtension]
 *   for the reasoning. Defaults to `false` (ordinary [isNonNull] evaluation only) for query blocks
 *   without grouping sets.
 * @param isSubLinkSubqueryColumnNotNull Returns `true` when [subselectBlock] — the raw `{QUERY ...}`
 *   text of an `ANY_SUBLINK`'s or `ALL_SUBLINK`'s `:subselect` (see
 *   [PgNodeExpression.SubLink.subselectBlock]) — produces EXACTLY ONE non-junk output column and
 *   that column is provably non-null. Used by [isNonNull]'s `SubLink` branch as the third, most
 *   expensive leg of the identical `ANY_SUBLINK`/`ALL_SUBLINK` nullability rule (see that branch's
 *   own comment for the full three-condition rule and why each condition is required). Defaults to
 *   `{ false }` — every existing construction site and unit test that does not explicitly wire this
 *   callback stays conservative (nullable), which is also the correct behavior for a nested sublink
 *   once the caller's own depth budget for this analysis is exhausted (see
 *   `ColumnNullabilityAnalyzer`'s wiring of this callback for that budget).
 * @param forceNewNullable `true` when a `RETURNING WITH (OLD AS o, NEW AS n)` reference to `NEW`
 *   (`Var.returningType == `[PgNodeExpression.VAR_RETURNING_TYPE_NEW]`) must be treated as
 *   unconditionally nullable, the same way [isNonNull]'s `Var` branch ALWAYS treats `OLD`
 *   (`VAR_RETURNING_TYPE_OLD`) regardless of this flag. Set by the caller when the enclosing
 *   statement is a plain `DELETE` (`NEW` never exists — the row is gone — verified live: `NEW.col`
 *   is `NULL` for every row a `DELETE` returns) or a `MERGE` (an individual result row's `NEW` may
 *   or may not exist depending on which `WHEN` clause matched — e.g. `WHEN MATCHED THEN DELETE`
 *   leaves no `NEW` row — a fact this analyzer cannot isolate per-row any more than it can for an
 *   ordinary, non-`OLD`/`NEW` `MERGE` column; see [PgCatalogLoader.mergeAbsentVarnos]'s
 *   KDoc for that companion safety net). Left `false` (the default) for a plain `UPDATE`/`INSERT`,
 *   where the row a `RETURNING` clause reports on always has BOTH an `OLD` and a `NEW` state, so
 *   `NEW` is exactly as trustworthy as an ordinary column reference.
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
   * [isNonNull] for accurate expression-level nullability. Returns `true` (nullable) when
   * `isNonNull` returns `false`.
   *
   * Before any of that, every target-list entry's expression is run through
   * [substituteGroupRteVars] against [PgNodeTreeParser.parseGroupRteExpressions]'s result. On
   * PostgreSQL 16 and 17 that map is always empty (no GROUP RTE exists), so this is a no-op and
   * every entry's expression is exactly what [PgNodeTreeParser.parseTargetList] parsed. On
   * PostgreSQL 18+, this restores the same tree shape 16/17 already have — the real grouping-key
   * expression, not a `Var` referencing the synthesized `*GROUP*` RTE — so every rule below
   * ([groupingSortGroupRefs], [groupingKeyExpressions], [isEffectivelyNonNull],
   * [isSafeFromGroupingSetNullExtension], [isNonNull]) runs identically regardless of which
   * PostgreSQL version produced [nodeTreeText]. This applies to a PLAIN `GROUP BY` exactly as much
   * as to `GROUPING SETS`/`CUBE`/`ROLLUP` — PostgreSQL 18 creates a GROUP RTE for a plain `GROUP BY`
   * too (verified live) — regardless of [hasGroupingSets].
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
    val parsedEntries = parser.parseTargetList(nodeTreeText)
    if (parsedEntries.isEmpty()) return emptyList()
    val groupRteExpressions = parser.parseGroupRteExpressions(nodeTreeText)
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
   * - [entry] IS a grouping key itself: its [TargetEntry.sortGroupRef] is non-zero and appears in
   *   [groupingSortGroupRefs] (from [PgNodeTreeParser.parseGroupingSortGroupRefs]); or
   * - [entry]'s expression structurally equals one of [groupingKeyExpressions] — a *duplicate*
   *   occurrence of a grouping key expression that PostgreSQL did not assign the matching
   *   `ressortgroupref` to (see that parameter's KDoc for why this is a distinct case from the
   *   one above, not a redundant restatement of it); or
   * - [entry]'s expression is not proven [isSafeFromGroupingSetNullExtension].
   *
   * All three conditions are load-bearing and independent. The first alone would miss a *derived*
   * expression over a key (e.g. `upper(lower(a))` when the key is `lower(a)`, which has
   * `sortGroupRef == 0` — it does not match the key textually, only structurally, which
   * [isSafeFromGroupingSetNullExtension] is what actually catches). The second is not subsumed by
   * [isSafeFromGroupingSetNullExtension] either — that method proves an expression's *result*
   * cannot be forced null by null-extending some deeper subexpression, which says nothing about
   * the expression *as a whole* being wholesale swapped for `NULL` because it happens to
   * structurally repeat the grouping key. The third would miss a bare-`Const` grouping key (e.g.
   * `GROUP BY ROLLUP('ALL'::text)`) — a `Const` is [isSafeFromGroupingSetNullExtension] by
   * definition (see that method), yet PostgreSQL still null-extends it when it IS the grouping
   * key, which only the first condition (or, for an unref'd duplicate Const, the second) catches.
   *
   * @param groupingKeyExpressions the expressions of every entry whose own [TargetEntry.sortGroupRef]
   *   IS a grouping key (per [groupingSortGroupRefs]), excluding any that are a bare
   *   [PgNodeExpression.Const] or that [foldsToConst] — PostgreSQL's structural matching
   *   (`search_indexed_tlist_for_non_var` in `setrefs.c`) explicitly refuses to match a `Const`
   *   node (see [isSafeFromGroupingSetNullExtension]'s KDoc), and an expression that folds to a
   *   `Const` before that matching pass runs (e.g. `upper('a')`) is, by the time the pass runs,
   *   already a `Const` too — verified live on EVERY supported version (16, 17, and 18): `SELECT
   *   upper('a') AS u1, upper('a') AS u2, ... GROUP BY ROLLUP(upper('a'))` leaves the un-ref'd
   *   duplicate `u2` as `'A'`, never `NULL`, in the ROLLUP summary row, unlike a duplicate that
   *   does NOT fold (see the `date_trunc` case in [isSafeFromGroupingSetNullExtension]'s KDoc,
   *   where BOTH occurrences are null-extended). Without this exclusion, a duplicate literal like
   *   `SELECT 'ALL'::text AS l1, 'ALL'::text AS l2, ... GROUP BY ROLLUP('ALL'::text)` would be
   *   wrongly forced nullable for `l2` — verified live on every supported version that `l2` stays
   *   `'ALL'`, never `NULL`, even though `l1` (the ref'd occurrence) does become `NULL`.
   *
   *   On PostgreSQL 18, [entry] arrives here already having been run through
   *   [substituteGroupRteVars] (see [extractColumnNullability]'s own KDoc) — every target-list
   *   `Var` that PostgreSQL 18's parse-analysis phase rewrote into a reference to the synthesized
   *   `*GROUP*` RTE has already been resolved back to the real expression it stands for, restoring
   *   the same tree shape PostgreSQL 16/17 produce directly. `u2`/`l2` therefore arrive here as the
   *   genuine `FuncExpr`/`Const` PostgreSQL 16/17 always showed, not a bare `Var`, so this exclusion
   *   rescues them identically on every supported version — see `QueryAnalysisTest`'s `duplicate
   *   bare Const grouping key stays non-null...` and `duplicate IMMUTABLE-folding call stays
   *   non-null...` tests, which pin this as an unconditional (not version-branched) assertion.
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
   * row belonging to a grouping set that omits it.
   *
   * Only meaningful when the enclosing query block has GROUPING SETS, CUBE, or ROLLUP
   * ([hasGroupingSets]); see [isEffectivelyNonNull] for how the two nullability conditions combine.
   *
   * Null-extension is a **structural, planner-level substitution**: PostgreSQL scans the target
   * list for stable, non-folded subexpressions that match a grouping key and replaces their
   * computed value with `NULL` outright for rows outside that key's grouping set — it does not
   * evaluate the subexpression's own semantics first. This means even a construct that is
   * *semantically* always non-null under ordinary evaluation (e.g. `EXISTS(...)`, `ARRAY[...]`,
   * `IS NULL`) can still be replaced with a literal `NULL` if it matches a grouping key. Whether an
   * expression CAN match is governed by two special cases PostgreSQL's matching applies before
   * falling through to ordinary structural equality:
   * - `Aggref`/`GroupingFunc` — aggregates are illegal inside `GROUP BY`, so no expression
   *   containing one can itself be a grouping key, and neither can any expression built on top of
   *   one, because the aggregate/grouping value it depends on cannot be null-extended out from
   *   under it.
   * - `Const` — PostgreSQL's grouping-key matching specifically refuses to match a bare constant
   *   (there would be no point: a constant is trivially recomputable), so a lone `Const` is never
   *   itself null-extended. This does NOT extend to a `Const` wrapped in a non-folded coercion
   *   chain (e.g. a `text`-to-`timestamptz` cast, which is a real function call, not a no-op) —
   *   that wrapping expression is a stable, matchable subexpression like any other, and the `Const`
   *   underneath it does not make it safe.
   *
   * So `safe(e)` is: [foldsToConst] → safe (a third, independent leg — see that method); a
   * NON-`VARIADIC` [PgNodeExpression.FuncExpr] whose function is [isAlwaysNonNull] → safe (a
   * fourth, independent leg — see the note near the bottom of this KDoc); otherwise
   * `Aggref`/`GroupingFunc` → safe (matches the first special case); `Const` → safe (matches the
   * second, though [foldsToConst] already subsumes it); `WindowFunc` → safe iff every child is
   * itself safe (a window function can never itself be a grouping key — window functions, like
   * aggregates, are illegal inside `GROUP BY` — but unlike `Aggref` it does NOT get blanket safety:
   * its arguments are evaluated over already-grouped, potentially null-extended rows, e.g.
   * `first_value(b) OVER (...)` is genuinely nullable, verified live — see the ground truth in
   * `QueryAnalysisTest`); everything else, **including a bare `Var`**, → safe iff `e`'s parsed
   * descendants include at least one `Aggref`/`GroupingFunc`/`WindowFunc` (per the first special
   * case — a `WindowFunc` counts here too, since it likewise can never itself be a grouping key) AND
   * every parsed child of `e` is itself safe (the whole subtree is dominated by that
   * aggregate/window, modulo constants and foldable subexpressions). A bare `Var` has no
   * descendants, so it is never safe under this rule (correct: a bare column reference is exactly
   * what a grouping key most commonly is, or is derived from). `count(*) + 1` is safe (its `OpExpr`
   * has an `Aggref` descendant and both children — `Aggref`, `Const` — are themselves safe);
   * `count(*) || some_stable_cast(a_const)` is NOT safe, because the cast side has no
   * `Aggref`/`WindowFunc` descendant AND does not [foldsToConst] (a stable cast survives constant
   * folding) even though the `||` as a whole has an `Aggref` — safety is required of every child
   * independently, not just the subtree as a whole, otherwise a matchable non-aggregate side would
   * be missed.
   *
   * This walk is only sound for a [PgNodeExpression] subtype whose parsed representation retains
   * every child expression the underlying Postgres node actually has — for a subtype that drops a
   * child, this method cannot rule out an unseen child changing the answer.
   * [PgNodeExpression.CaseExpr] is the motivating example that IS handled faithfully:
   * [PgNodeExpression.CaseExpr.testExpression] and [PgNodeExpression.CaseExpr.whenConditions] exist
   * on that type purely so this walk (not [isNonNull], which correctly ignores them, since a `CASE`
   * result's nullability never depends on its own test/condition expressions) can see a `Var` that
   * appears only in a `CASE`'s test expression or a `WHEN` condition, e.g.
   * `CASE a WHEN 'x' THEN 1 ELSE 2 END` or `CASE WHEN a = 'x' THEN 1 ELSE 2 END`.
   *
   * [PgNodeExpression.JsonExpr] and [PgNodeExpression.Unknown] are the two subtypes that drop
   * information this method cannot recover by walking harder — [PgNodeExpression.JsonExpr] does not
   * retain a `JSON_VALUE`/`JSON_QUERY`/`JSON_EXISTS` `PASSING` clause's values, so a `Var` living
   * only there (e.g. `JSON_EXISTS(doc, '\$.a ? (@ == \$v)' PASSING a AS v)`) is invisible; parsing
   * the `PASSING` clause was deliberately not attempted (parser work against an unconfirmed node
   * shape for marginal precision gain). Both are therefore hardcoded UNSAFE unconditionally,
   * regardless of an `Aggref` elsewhere in the tree — an `Aggref` sibling cannot rescue a subtree
   * that might independently contain a hidden, matchable `Var`. The same treatment applies once
   * [depth] is exhausted.
   *
   * A fourth, independent leg alongside [foldsToConst]: a NON-`VARIADIC` [PgNodeExpression.FuncExpr]
   * whose function is [isAlwaysNonNull] (e.g. `concat` — see
   * [PgCatalogLoader.alwaysNonNullFunctionOids]) is safe from having its OWN RESULT forced `null`
   * by a deeper subexpression being null-extended — by that list's own definition, `concat` renders
   * a `null` argument as an empty string, so null-extending one of its ARGUMENTS (e.g. `a` inside
   * `concat(a, '-')` when `a` alone, not the whole `concat` call, is the grouping key — verified
   * live, PostgreSQL 16/17/18: `concat(a, '-')` stays `'-'`, never `null`, in that case) cannot make
   * the call's result `null`. This is a DIFFERENT scenario from `concat(a, '-')` ITSELF being
   * null-extended wholesale because it structurally repeats the grouping key expression (e.g.
   * `GROUP BY ROLLUP(concat(a, '-'))`, verified live to null-extend a duplicate, un-ref'd
   * occurrence too, not just the one PostgreSQL attached `ressortgroupref` to) — there,
   * PostgreSQL's substitution replaces the ENTIRE call's result before `concat` ever runs, so its
   * argument-null-tolerance is irrelevant and provides no protection. This leg does not (and, from
   * inside a single expression's own subtree, structurally cannot) distinguish the two; ruling out
   * the second is [isEffectivelyNonNull]'s job via its `groupingKeyExpressions` structural-duplicate
   * check, which this leg's safety claim depends on to stay sound. Deferring to ordinary [isNonNull]
   * evaluation for the first scenario independently reaches the same conclusion via the identical
   * [isAlwaysNonNull] check in its own [PgNodeExpression.FuncExpr] branch. `concat_ws` is
   * deliberately NOT on [isAlwaysNonNull]'s list — despite also being non-strict, it is non-null
   * only when its first argument (the separator) is non-null, so it gets no dedicated leg here and
   * falls through to the generic aggregate/window domination rule below like any other `FuncExpr`,
   * where a `Var` in ANY of its argument positions — including the separator — correctly makes it
   * unsafe; see [PgCatalogLoader.alwaysNonNullFunctionOids]'s KDoc for why this distinction is
   * load-bearing. The `VARIADIC` exclusion matters for the same reason [isNonNull] excludes it:
   * `concat(VARIADIC arr)` is `null` when `arr` itself is `null` (verified live on PostgreSQL 16,
   * 17, and 18) — a `VARIADIC` call gets NO short-circuit here at all and falls through to the
   * generic rule, where its sole argument (the array — a `Var` for a column, or an `ArrayExpr`
   * for a literal) is evaluated on its own merits, correctly unsafe if it can be null-extended.
   * This does NOT extend to a function merely on the (much
   * larger, STRICT-only) [isNeverNullForNonNullInput] safe-list — that list only proves totality
   * for NON-NULL arguments, and says nothing about whether the function's result stays non-null
   * when one of ITS OWN ARGUMENTS is individually null-extended (the first scenario above), which
   * is exactly the scenario this leg exists to guard against for the (much narrower) functions
   * that ARE on [isAlwaysNonNull]'s list.
   *
   * Several `when` branches below answer the same argument-independence question the fourth leg does:
   * null-extension substitutes `NULL` for an ARGUMENT's value, so any rule that proves a result
   * non-null without consulting its arguments' nullability already answers it. The
   * [isNonNullIffFirstArgumentNonNull] branch is the one conditional case — safe iff its FIRST
   * argument is itself safe, since a `concat_ws` separator can be a grouping key.
   * [immuneByNoGroupingKeyMatch] is a structurally different leg, for constructs with no
   * per-node-kind rule at all, e.g. `now()`.
   *
   * `XML_IS_XMLFOREST` and `XML_IS_XMLPI` are excluded because neither is total over `null` input
   * (measurements in [evaluateXmlExpr]); admitting them produced a real wrong non-null — `SELECT
   * xmlforest(lower(a) AS q), count(*) FROM t2 GROUP BY ROLLUP(a)`, which live PostgreSQL 16, 17 and
   * 18 all return `NULL` for in the rollup summary row. PostgreSQL has no equality operator for `xml`
   * or for `json` (verified live), so neither an `XmlExpr` nor a default-`RETURNING`
   * `JSON_OBJECT`/`JSON_ARRAY` (which yields `json`) can itself BE a grouping key; `jsonb` does have
   * one, so a `RETURNING jsonb` call can be.
   *
   * The self-match guard runs before every leg because the legs prove only that an expression's own
   * result survives null-extension of a DEEPER subexpression, never that the expression itself is not
   * replaced by `NULL` wholesale. Running it at every node the walk reaches, not only at
   * [isEffectivelyNonNull]'s entry root, fixes a measured wrong non-null: `SELECT count(*)::text ||
   * concat(a, b) FROM t2 GROUP BY ROLLUP(concat(a, b))` reported non-null where live PostgreSQL 16
   * and 18 return `NULL`.
   *
   * @param groupingKeyExpressions the same set [isEffectivelyNonNull] receives; see that method's
   *   identically-named parameter.
   * @param depth remaining recursion budget, mirroring [MAX_EXPRESSION_DEPTH]; returns `false`
   *   (safe: assume UNSAFE — i.e. possibly null-extended) once exhausted
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
   * under `GROUP BY ROLLUP(a)` is immune without [containsDominatingConstruct].
   *
   * The lossy nodes are [PgNodeExpression.JsonExpr] and [PgNodeExpression.Unknown], for the reason
   * [isSafeFromGroupingSetNullExtension] gives, plus [PgNodeExpression.SubLink], which that method
   * need not name: [PgNodeExpression.SubLink.subselectBlock] keeps the subselect as unparsed raw text
   * and [safetyWalkChildren] walks only [PgNodeExpression.SubLink.outerOperand], so a correlated
   * `Var` inside the subselect is invisible to the `Var` check.
   *
   * The match is sound in this direction: [PgNodeExpression]'s subtypes retain a SUBSET of the fields
   * PostgreSQL's own `equal()` compares (e.g. [PgNodeExpression.Const] keeps only `isNull`), so this
   * class's structural equality is COARSER than PostgreSQL's — a false "no match" verdict can never
   * arise from a field this parser dropped.
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
   * or a [PgNodeExpression.RelabelType] (a no-op type reinterpretation, not a function call) over
   * one, or a [PgNodeExpression.ArrayExpr] whose every element does.
   *
   * This matters because PostgreSQL's grouping-set null-extension substitution
   * (`search_indexed_tlist_for_non_var` in `setrefs.c`) runs at the END of planning and explicitly
   * refuses to match a `Const` (`if (IsA(node, Const)) return NULL`), while constant folding itself
   * (`eval_const_expressions`, from `preprocess_expression`) runs EARLY, well before that
   * substitution. An expression that will fold to a `Const` by the time the substitution runs was
   * therefore NEVER a candidate for it — safe regardless of whether it contains an
   * `Aggref`/`GroupingFunc`/`WindowFunc`, unlike the general rule in [isSafeFromGroupingSetNullExtension].
   *
   * IMMUTABLE only. A STABLE function — e.g. `date_trunc('month', current_date)`, which depends on
   * the current date — is NOT constant-folded, survives to become a genuine, matchable
   * subexpression, and PostgreSQL DOES null-extend it when it matches a grouping key (verified
   * live). VOLATILE is not safe either — `GROUP BY random()` is legal SQL, and the key-matching
   * itself is structural (`equal()`), not a volatility check.
   *
   * [PgNodeExpression.CoerceViaIo], [PgNodeExpression.CoerceToDomain],
   * [PgNodeExpression.ArrayCoerceExpr], [PgNodeExpression.RowExpr],
   * [PgNodeExpression.SqlValueFunction], and [PgNodeExpression.NextValExpr] are deliberately NOT
   * treated as foldable: none of them expose a function/operator OID this class can check
   * immutability for (unlike [PgNodeExpression.FuncExpr]/[PgNodeExpression.OpExpr]), so treating any
   * of them as folding would be an unverified guess. This matters concretely for
   * [PgNodeExpression.CoerceViaIo]: an I/O-based cast function can itself be STABLE (e.g.
   * `timestamptz`'s output function depends on the session's `TimeZone` setting).
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
   * Returns the immediate parsed child expressions of [expression] for
   * [isSafeFromGroupingSetNullExtension] and [containsDominatingConstruct].
   *
   * [PgNodeExpression.Var], [PgNodeExpression.Const], [PgNodeExpression.Aggref],
   * [PgNodeExpression.GroupingFunc], [PgNodeExpression.SqlValueFunction],
   * [PgNodeExpression.NextValExpr], [PgNodeExpression.JsonExpr], and [PgNodeExpression.Unknown] are
   * resolved directly by their callers without consulting this method (each is either a genuine
   * leaf or hardcoded by the lossy-parsing rule), so they return an empty list here defensively
   * rather than being omitted from the `when`.
   */
  private fun safetyWalkChildren(expression: PgNodeExpression): List<PgNodeExpression> = when (expression) {
    is PgNodeExpression.FuncExpr -> expression.arguments
    is PgNodeExpression.OpExpr -> expression.arguments
    is PgNodeExpression.ScalarArrayOpExpr -> expression.arguments
    is PgNodeExpression.CoalesceExpr -> expression.arguments
    is PgNodeExpression.NullIfExpr -> expression.arguments
    is PgNodeExpression.MinMaxExpr -> expression.arguments
    is PgNodeExpression.WindowFunc -> expression.arguments
    is PgNodeExpression.SubLink -> listOfNotNull(expression.outerOperand)
    is PgNodeExpression.CaseExpr ->
      expression.resultExpressions +
        listOfNotNull(expression.defaultResult, expression.testExpression) +
        expression.whenConditions

    is PgNodeExpression.BoolExpr -> expression.arguments
    is PgNodeExpression.RelabelType -> listOf(expression.argument)
    is PgNodeExpression.CoerceViaIo -> listOf(expression.argument)
    is PgNodeExpression.ArrayCoerceExpr -> listOf(expression.argument)
    is PgNodeExpression.CollateExpr -> listOf(expression.argument)
    is PgNodeExpression.CoerceToDomain -> listOf(expression.argument)
    is PgNodeExpression.NullTest -> listOf(expression.argument)
    is PgNodeExpression.BooleanTest -> listOf(expression.argument)
    is PgNodeExpression.DistinctExpr -> expression.arguments
    is PgNodeExpression.ArrayExpr -> expression.elements
    is PgNodeExpression.RowExpr -> expression.arguments
    is PgNodeExpression.FieldSelect -> listOf(expression.argument)
    is PgNodeExpression.JsonIsPredicate -> listOf(expression.argument)
    is PgNodeExpression.JsonConstructorExpr -> expression.arguments + listOfNotNull(expression.function)
    is PgNodeExpression.XmlExpr -> expression.arguments
    is PgNodeExpression.Var,
    is PgNodeExpression.Const,
    is PgNodeExpression.Aggref,
    is PgNodeExpression.GroupingFunc,
    is PgNodeExpression.SqlValueFunction,
    is PgNodeExpression.NextValExpr,
    is PgNodeExpression.JsonExpr,
    is PgNodeExpression.Unknown,
    -> emptyList()
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
        // A PostgreSQL 18+ RETURNING WITH (OLD AS o, ...) reference to the OLD row must never be
        // treated as non-null on the strength of the source column's own NOT NULL constraint or
        // outer-join structure — see PgNodeExpression.Var.returningType's KDoc for why the OLD row
        // itself may not exist for this result row at all (e.g. a MERGE ... WHEN NOT MATCHED THEN
        // INSERT action), a fact neither of those signals captures. The same applies to NEW
        // whenever forceNewNullable says so — see that constructor parameter's KDoc.
        //
        // levelsUp == 0 is required because a Var with levelsUp > 0 indexes an ENCLOSING query's
        // range table (see PgNodeExpression.Var.levelsUp's KDoc), not the current block's — this
        // block's isSourceColumnNotNull, groupRteMap, and qual narrowing are all keyed against the
        // CURRENT block's varnos, so resolving an outer-level varno against them would be sound in
        // neither direction (a collision could read the wrong column's constraint entirely, in
        // either the non-null or nullable direction). qualProvenNonNullVars already excludes these
        // for its own WHERE-clause narrowing (see that method's KDoc); this makes the target-entry
        // evaluation path agree, rather than silently trusting a levelsUp > 0 Var it happens to
        // reach through an untouched path (e.g. an ANY_SUBLINK's own subselect target list — see
        // isSubLinkSubqueryColumnNotNull).
        expression.levelsUp == 0 &&
          expression.returningType != PgNodeExpression.VAR_RETURNING_TYPE_OLD &&
          !(expression.returningType == PgNodeExpression.VAR_RETURNING_TYPE_NEW && forceNewNullable) &&
          !isOuterJoinNullable(expression.nullingRelations) &&
          isSourceColumnNotNull(expression.varno, expression.varattno)

      is PgNodeExpression.Const -> !expression.isNull
      is PgNodeExpression.FuncExpr ->
        if (expression.isVariadic) {
          if (isAlwaysNonNull(expression.functionOid) || isNonNullIffFirstArgumentNonNull(expression.functionOid)) {
            // VARIADIC passes the array argument itself as one value, not exploded into
            // elements (see PgNodeExpression.FuncExpr.isVariadic's KDoc) — isAlwaysNonNull's
            // "regardless of any argument" and isNonNullIffFirstArgumentNonNull's "only the
            // first argument matters" both assume the ordinary calling form and are unsound
            // here: concat(VARIADIC arr) and concat_ws(',', VARIADIC arr) are both null when
            // arr itself is null (verified live on PostgreSQL 16, 17, and 18). Requiring every
            // argument non-null is sound for both functions in this form (the separator is
            // still one of the arguments) and preserves real precision (concat_ws(',',
            // VARIADIC ARRAY['a', NULL]) is 'a', still non-null).
            expression.arguments.all(recurse)
          } else {
            // Deliberately does NOT fall through to the isStrict/isNeverNullForNonNullInput leg
            // below. That safe-list's "total on non-null input" guarantee (see
            // PgCatalogLoader.neverNullForNonNullInputOids's KDoc and SafeListSweepTest) was
            // verified for the ORDINARY, element-wise calling convention. For a VARIADIC call,
            // "every argument non-null" only means the array Datum itself is non-null —
            // recurse() on the array (an ArrayExpr) is unconditionally true regardless of NULL
            // elements inside it (an array container is never NULL merely because one of its
            // elements is), so that leg would prove nothing about NULL elements even if
            // reached, and no per-function verification exists for whether an internal NULL
            // element could still make a safe-listed function return NULL when invoked this
            // way. Not reachable today — verified empirically that no name on
            // NEVER_NULL_FUNCTION_SIGNATURES resolves to a `provariadic <> 0` function on
            // PostgreSQL 16, 17, or 18 — but must not silently start trusting the list the
            // moment a variadic name is added to it.
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
          // ANY_SUBLINK (`x = ANY (subquery)` / `x IN (subquery)`) is three-valued: PostgreSQL
          // returns NULL, not FALSE, when the subquery yields a NULL row and no row matches —
          // verified live on PostgreSQL 17: `CREATE TABLE t (id INT PRIMARY KEY, a TEXT NOT NULL);
          // CREATE TABLE u (v TEXT); INSERT INTO t VALUES (1,'x'); INSERT INTO u VALUES ('q'),
          // (NULL); SELECT a = ANY (SELECT v FROM u) FROM t;` is NULL, not FALSE. Proving a
          // non-null result therefore requires ALL THREE of: the outer operand is non-null (an
          // ANY_SUBLINK with a null outer operand is NULL outright, same as any comparison); the
          // comparison operator behind the sublink is both isStrict AND isNeverNullForNonNullInput
          // — a non-strict or non-total operator could itself manufacture a NULL from non-null
          // operands, same two-predicate proof OpExpr/ScalarArrayOpExpr require above; and the
          // subquery's single output column is itself provably non-null — a NULL row in the
          // subquery is exactly what makes the whole expression NULL when no row matches, per the
          // repro above. testExpressionOperatorOid is null for the multi-column `(a, b) IN (SELECT
          // p, q FROM w)` row-comparison form (a BOOLEXPR testexpr with no single top-level
          // operator), so that form always falls through to nullable here rather than needing its
          // own special case. Ordered cheapest-first: subLinkType and outerOperand are already
          // computed above; the two OID predicates are cheap map lookups; isSubLinkSubqueryColumnNotNull
          // is the only leg that re-enters full query-block analysis, so it is checked last.
          //
          // ALL_SUBLINK (`x op ALL (subquery)`) gets the IDENTICAL proof, being ANY's dual over AND
          // instead of OR: `x op ALL (S)` is NULL only when some comparison is NULL and none is FALSE,
          // which the same three conditions rule out. An empty S is TRUE for ALL (FALSE for ANY) —
          // non-null either way, so no empty-subquery case needs handling. `NOT IN` desugars to a
          // BOOLEXPR not around an ANY_SUBLINK, never an ALL_SUBLINK, so a negation cannot hide inside
          // an ALL testexpr. The multi-column row-comparison ALL forms fail automatically, because
          // testExpressionOperatorOid is null for both their shapes — see
          // PgNodeExpression.SubLink.testExpressionOperatorOid.
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
   * - `PARSE`/`SCALAR`/`SERIALIZE`: strict single-argument constructs, non-null only when there IS an
   *   argument and every argument is non-null. This is the original bug report: `JSON_SERIALIZE` was
   *   reported non-null regardless of its argument.
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
    // a SUCCESSFUL match, not the "no match" (EMPTY)/"error" (ERROR) case the ON EMPTY/ON ERROR
    // clauses control, so no combination of those codes rules the successful-match-to-JSON-null
    // case out. Verified live (PostgreSQL 17 and 18): `JSON_VALUE('{"name": null}'::jsonb, '$.name'
    // RETURNING TEXT ERROR ON EMPTY ERROR ON ERROR) IS NULL` is `true`.
    PgNodeExpression.JSON_VALUE_OP -> false

    else -> false
  }

  /**
   * Returns `true` only for a `JsonBehaviorType` code POSITIVELY VERIFIED (live, PostgreSQL 17 and
   * 18) to make a `JSON_QUERY` `ON EMPTY`/`ON ERROR` clause produce a definite, non-null outcome —
   * an ALLOW-list. An unrecognized code defaults to nullable, the safe direction.
   *
   * The four allowed codes and why each is safe:
   * - [PgNodeExpression.JSON_BEHAVIOR_ERROR]: raises a runtime error rather than returning a value at
   *   all for the row. Verified live: `:expr <>` (absent) for this code, so
   *   [PgNodeExpression.JsonExpr.onEmptyDefault]/[PgNodeExpression.JsonExpr.onErrorDefault] parses to
   *   `null` here, and `null?.let(recurse) != false` is `true` unconditionally — no default
   *   expression exists to recurse into.
   * - [PgNodeExpression.JSON_BEHAVIOR_EMPTY_ARRAY]/[PgNodeExpression.JSON_BEHAVIOR_EMPTY_OBJECT]:
   *   substitute Postgres's own internal `[]`/`{}` `jsonb` constant, never a user-supplied
   *   expression. Verified live: `:expr` for these codes is always a `{CONST ... :constisnull
   *   false ...}` block, so `recurse` on it is unconditionally `true` — checked anyway, defensively,
   *   rather than special-cased to skip [PgNodeExpression.JsonExpr.onEmptyDefault]/`onErrorDefault`
   *   entirely.
   * - [PgNodeExpression.JSON_BEHAVIOR_DEFAULT]: the ONE code among these four backed by a genuinely
   *   user-supplied expression (`DEFAULT expr ON EMPTY`/`ON ERROR`) — verified live to always carry
   *   a real `:expr` block, which is why [emptyOk]/[errorOk] recurse into it rather than trusting the
   *   behavior code alone; `DEFAULT null::jsonb ON EMPTY` is legal and must NOT be treated as
   *   non-null.
   *
   * Deliberately NOT on this list: [PgNodeExpression.JSON_BEHAVIOR_NULL] (explicitly nullable by
   * definition); `JSON_BEHAVIOR_TRUE`/`FALSE`/`UNKNOWN` — verified live that Postgres rejects all
   * three for a `JSON_QUERY` `ON EMPTY`/`ON ERROR` clause outright, so they never appear here; and
   * `JSON_TABLE`'s per-column `ON EMPTY`/`ON ERROR` — verified live that a `JSON_TABLE` column
   * resolves to a plain `VAR` against an `RTE_TABLEFUNC` range-table entry in the outer query's
   * target list, never a [PgNodeExpression.JsonExpr] node this method ever sees, so this allow-list
   * has no bearing on it either way.
   */
  private fun isKnownNonNullJsonBehavior(behaviorType: Int): Boolean =
    behaviorType == PgNodeExpression.JSON_BEHAVIOR_ERROR ||
      behaviorType == PgNodeExpression.JSON_BEHAVIOR_EMPTY_ARRAY ||
      behaviorType == PgNodeExpression.JSON_BEHAVIOR_EMPTY_OBJECT ||
      behaviorType == PgNodeExpression.JSON_BEHAVIOR_DEFAULT

  /**
   * Returns `true` only for a `JsonBehaviorType` code POSITIVELY VERIFIED (live, PostgreSQL 17) to
   * make `JSON_EXISTS`'s `ON ERROR` clause produce a definite, non-null (`true`/`false`) outcome, or
   * raise an error rather than returning a value at all. `JSON_EXISTS` has no `ON EMPTY` clause.
   *
   * [PgNodeExpression.JSON_BEHAVIOR_TRUE]/[PgNodeExpression.JSON_BEHAVIOR_FALSE] are the codes for an
   * explicit `TRUE`/`FALSE ON ERROR` clause. Verified live: with no `ON ERROR` clause written at
   * all, Postgres materializes `:btype 4` ([PgNodeExpression.JSON_BEHAVIOR_FALSE]) — the SQL-standard
   * default — so an absent clause is exactly as safe as writing `FALSE ON ERROR` explicitly.
   * Deliberately NOT on this list: [PgNodeExpression.JSON_BEHAVIOR_UNKNOWN], which produces a
   * genuine SQL NULL on a path error (verified live), and [PgNodeExpression.JSON_BEHAVIOR_NULL]/
   * `EMPTY_ARRAY`/`EMPTY_OBJECT`/`DEFAULT`, which Postgres's parser rejects outright for
   * `JSON_EXISTS`'s `ON ERROR` clause and so never appear here.
   */
  private fun isKnownNonNullJsonExistsErrorBehavior(behaviorType: Int): Boolean =
    behaviorType == PgNodeExpression.JSON_BEHAVIOR_ERROR ||
      behaviorType == PgNodeExpression.JSON_BEHAVIOR_TRUE ||
      behaviorType == PgNodeExpression.JSON_BEHAVIOR_FALSE

  /**
   * Evaluates an `XMLELEMENT`/`XMLFOREST`/`XMLPI`/`XMLCONCAT`/`XMLROOT`/`XMLPARSE`/`XMLSERIALIZE`
   * construct, branching on [PgNodeExpression.XmlExpr.op]. Only `XMLELEMENT` is total over `null`
   * input. Measured live on PostgreSQL 16, 17 and 18, which agree:
   * - `xmlelement(name e, NULL::text)` is NOT `null` — a null child renders as empty content, and a
   *   null `xmlattributes` value omits that attribute, so the element tag itself always materializes.
   * - `xmlforest(NULL::text AS q)` IS `null`, while `xmlforest(NULL::text AS q, 'x' AS r)` is NOT —
   *   a null field is omitted and the result nulls only once EVERY field is gone, hence
   *   [Iterable.any]. `xmlforest()` is a syntax error, and `any` on an empty list would answer
   *   `false` (nullable) anyway.
   * - `xmlpi(name php, NULL::text)` IS `null`, while the content-less `xmlpi(name php)` is NOT, which
   *   [Iterable.all] states exactly: vacuously `true` for the zero-argument form, content required
   *   otherwise.
   *
   * [PgNodeExpression.XmlExpr.arguments] merges the node's `:named_args` with its `:args` (see
   * `PgNodeTreeParser.parseXmlExpr`), so an `XMLFOREST` field value — which lives in `:named_args` —
   * is visible to the [Iterable.any] check rather than silently absent. Any other op code falls
   * through to `false` (nullable), the safe default.
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
     * `true` if [expression] contains an ORDINARY `Var` (`returningType == 0` — i.e. NOT an `OLD`
     * or `NEW` reference, which carry their own independent, already-safe handling — see
     * [PgNodeExpression.Var.returningType]'s KDoc) whose `varno` is anything OTHER THAN
     * [relationVarno].
     *
     * Used by [PgCatalogLoader.mergeAbsentVarnos]'s caller to decide whether a `MERGE`'s
     * `RETURNING` list needs per-relation match-optionality resolved AT ALL: a `RETURNING` that
     * only reads the target relation's own columns (always present, whichever `WHEN` clause
     * matched) or `OLD`/`NEW` references (already forced nullable/handled independently by
     * [PgNodeExpression.Var.returningType]) never needs [PgCatalogLoader.mergeAbsentVarnos]'s
     * `EXPLAIN` resolution at all — which matters because that resolution can itself fail to
     * attribute a `MERGE`'s join (e.g. a non-table `USING` source, such as a `VALUES` list) even
     * when the `RETURNING` list never actually depended on knowing which side that join favors.
     *
     * Exhausts every [PgNodeExpression] variant explicitly, so the compiler's own exhaustiveness
     * check over the sealed [PgNodeExpression] hierarchy guarantees every node type is LISTED here.
     * That guarantee is necessary but not sufficient: exhaustiveness only proves no variant was
     * left out of the `when`, not that a listed variant's own child expressions are walked. A
     * variant that genuinely carries no [PgNodeExpression] child (e.g. [PgNodeExpression.Const])
     * belongs in the childless branch; one that does (e.g. [PgNodeExpression.JsonExpr]'s
     * `argument`) must recurse into every such child, or a `Var` buried inside it silently
     * disappears from this check — this exact mistake, for [PgNodeExpression.JsonExpr], is what
     * let a `MERGE`'s `RETURNING JSON_QUERY(source.column, ...)` skip `EXPLAIN` resolution and
     * report a genuinely nullable expression as NOT NULL.
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
      return when (expression) {
        is PgNodeExpression.Var ->
          expression.returningType == PgNodeExpression.VAR_RETURNING_TYPE_NORMAL &&
            expression.varno != relationVarno

        is PgNodeExpression.Const,
        is PgNodeExpression.SqlValueFunction,
        is PgNodeExpression.NextValExpr,
        is PgNodeExpression.Unknown,
        -> false

        is PgNodeExpression.FuncExpr -> expression.arguments.any(recurse)
        is PgNodeExpression.OpExpr -> expression.arguments.any(recurse)
        is PgNodeExpression.ScalarArrayOpExpr -> expression.arguments.any(recurse)
        is PgNodeExpression.CoalesceExpr -> expression.arguments.any(recurse)
        is PgNodeExpression.NullIfExpr -> expression.arguments.any(recurse)
        is PgNodeExpression.MinMaxExpr -> expression.arguments.any(recurse)
        is PgNodeExpression.WindowFunc -> expression.arguments.any(recurse)
        is PgNodeExpression.Aggref -> expression.arguments.any(recurse)
        is PgNodeExpression.GroupingFunc -> expression.arguments.any(recurse)
        is PgNodeExpression.JsonExpr ->
          recurse(expression.argument) ||
            expression.onEmptyDefault?.let(recurse) == true ||
            expression.onErrorDefault?.let(recurse) == true

        is PgNodeExpression.SubLink -> expression.outerOperand?.let(recurse) == true
        is PgNodeExpression.CaseExpr ->
          expression.resultExpressions.any(recurse) ||
            listOfNotNull(expression.defaultResult, expression.testExpression).any(recurse) ||
            expression.whenConditions.any(recurse)

        is PgNodeExpression.BoolExpr -> expression.arguments.any(recurse)
        is PgNodeExpression.RelabelType -> recurse(expression.argument)
        is PgNodeExpression.CoerceViaIo -> recurse(expression.argument)
        is PgNodeExpression.ArrayCoerceExpr -> recurse(expression.argument)
        is PgNodeExpression.CollateExpr -> recurse(expression.argument)
        is PgNodeExpression.CoerceToDomain -> recurse(expression.argument)
        is PgNodeExpression.NullTest -> recurse(expression.argument)
        is PgNodeExpression.BooleanTest -> recurse(expression.argument)
        is PgNodeExpression.DistinctExpr -> expression.arguments.any(recurse)
        is PgNodeExpression.ArrayExpr -> expression.elements.any(recurse)
        is PgNodeExpression.RowExpr -> expression.arguments.any(recurse)
        is PgNodeExpression.FieldSelect -> recurse(expression.argument)
        is PgNodeExpression.JsonIsPredicate -> recurse(expression.argument)
        is PgNodeExpression.JsonConstructorExpr ->
          expression.arguments.any(recurse) || expression.function?.let(recurse) == true

        is PgNodeExpression.XmlExpr -> expression.arguments.any(recurse)
      }
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
