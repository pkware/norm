package norm.generator

import org.intellij.lang.annotations.Language
import java.sql.SQLException
import java.util.UUID

/**
 * Recursion budget for [ColumnNullabilityAnalyzer.subLinkSubqueryColumnNotNull]: how many levels
 * of NESTED `ANY_SUBLINK` (a `SubLink` whose own subselect contains another `SubLink`) are
 * resolved before defaulting to nullable — see that method's KDoc.
 *
 * This is NOT required to prevent an infinite loop: [PgNodeExpression.SubLink.subselectBlock] is
 * always extracted, via [PgNodeTreeParser.extractFieldExpression], as a genuine SUBSTRING of its
 * enclosing `SubLink`'s own text, strictly shorter than it — [PgNodeTreeParser] has no mechanism to
 * produce a cyclic or self-referential node-tree text, so this recursion is provably bounded by the
 * ORIGINAL query text's finite length regardless of this constant's value, or even its presence.
 * The budget exists instead as a defensive bound on STACK DEPTH and repeated analysis WORK for a
 * pathologically deep (if syntactically legal) chain of nested `= ANY (...)` sublinks, the same
 * role [NodeTreeNullabilityAnalyzer.MAX_EXPRESSION_DEPTH] plays for a deeply nested parsed
 * expression tree elsewhere in this codebase. Deliberately small: this analysis is only ever needed
 * for the (typically shallow) nullability proof of an `IN`/`= ANY` subquery's single output column,
 * not for arbitrarily deep query nesting in general — see `QueryAnalysisTest`'s four-level-nesting
 * test for a query shape that is semantically NOT NULL end-to-end but is reported nullable at this
 * budget, pinning that the budget's specific VALUE (not merely its presence) is what is enforced.
 */
private const val SUBLINK_ANALYSIS_DEPTH_BUDGET = 3

/**
 * Recursion budget for [ColumnNullabilityAnalyzer.resolveViewColumnNullability]: how many levels of
 * NESTED view-over-view resolution (view A selects from view B, which selects from view C, ...) are
 * followed before defaulting to nullable — see that method's KDoc.
 *
 * Unlike [SUBLINK_ANALYSIS_DEPTH_BUDGET], this bound IS required to prevent a crash, not merely
 * excess work: [resolveViewColumnNullability] recurses through real JVM stack frames (via
 * [ColumnNullabilityAnalyzer.analyzeViewNodeTree] → [ColumnNullabilityAnalyzer.analyzeNodeTree] →
 * [ColumnNullabilityAnalyzer.isColumnNotNull] → [ColumnNullabilityAnalyzer.resolveViewColumnNullability]
 * again for the next view down the chain), and PostgreSQL permits a genuinely unbounded view-nesting
 * depth via repeated `CREATE VIEW`.
 *
 * The chosen value must be safe on a SMALL thread stack, not merely the (typically 8 MB) main
 * thread a JVM usually starts on: a worker thread — a Gradle build's own task-execution thread
 * pool, for instance — commonly gets a stack far smaller than that, and this class has no way to
 * know, or control, the stack size of whatever thread eventually calls it.
 *
 * Measured live (JDK 26) directly against THIS budget's own worst case — a chain of exactly `50`
 * levels, resolved on a thread built with an explicit small stack (`Thread(..., stackSize = ...)`),
 * the shape `QueryAnalysisTest.ViewNullability`'s small-stack-thread pin exercises: the exact
 * byte-level failure boundary is NOISY across repeated runs, even on an identical JVM build and an
 * unchanged constant — `java.lang.StackOverflowError` was observed at stack sizes as high as `~230
 * KiB` in some runs, while `256 KiB` and above succeeded consistently across every run tried. This
 * noise is consistent with per-level cost on the order of a few KiB (roughly `230 KiB / 50 ≈ 4.6
 * KiB` per level in the worst observed case) rather than a precise, reproducible constant — several
 * real JVM stack frames per level (see the call chain above), each holding a handful of local
 * variables and closures, whose exact count and size can shift with JIT tiering state. `512 KiB` —
 * the stack size the small-stack-thread pin actually uses — sits at roughly `2x` the highest
 * observed failure point, a margin chosen for consistency ACROSS RUNS rather than a single
 * best-case measurement; every run attempted at `512 KiB` succeeded. A prior, now-corrected version
 * of this KDoc understated the true cost per level by extrapolating from an UNGUARDED, pre-fix
 * 199-level chain's failure point instead of measuring this budget's own shipped worst case
 * directly — that number is no longer reproducible on shipped code at all, since this guard now
 * truncates before a chain can ever reach 199 levels.
 *
 * `50` was chosen as a round number far beyond any view-nesting depth a real schema is expected to
 * have (single digits, occasionally low tens), not as a value tuned close to any stack-safety edge.
 * [ColumnNullabilityAnalyzer.viewColumnNullabilityInProgress]'s cycle guard, not this budget, is
 * what protects the (much shallower, but genuinely possible via `CREATE OR REPLACE VIEW`) case of
 * an actual cycle — see that property's own KDoc for that separate guard.
 *
 * KNOWN, DELIBERATE LIMITATION: this guard's own DEPTH CHECK runs before
 * [ColumnNullabilityAnalyzer.resolveViewColumnNullability]'s memo lookup — required so a relid whose
 * OWN entry depth is at or past this budget always truncates regardless of memo state, which closes
 * ONE concrete non-determinism (verified live, PostgreSQL 18.4: on a 52-view pass-through chain,
 * `resolve(chain_51)` alone gives `[true]`; `resolve(chain_1)` first, then `resolve(chain_51)`, used
 * to give `[false]` instead — now both give `[true]`, matching).
 *
 * This ordering does NOT make every possible resolution order agree in general, and no fix that
 * avoids extra bookkeeping per cached answer can: an UNTAINTED, permanently-memoized answer for a
 * relid that sits STRICTLY SHALLOWER than where truncation would occur — not the relid the depth
 * check itself intercepts, but one reached a few frames before it — is still reused as-is, because a
 * memo hit performs zero recursion and so never revisits this guard at all. Concretely, on that same
 * 52-view chain: resolving each view in ASCENDING order (shallowest first) reports NO view nullable
 * at all, because each deeper view's walk immediately hits an already-correct, already-cached answer
 * for its immediate predecessor and never recurses far enough to reach this guard; resolving in
 * DESCENDING order (deepest first) reports the two deepest views nullable, because the first,
 * deepest resolution walks all the way down unaided and genuinely crosses the budget. Both answers
 * are individually SOUND (a straight, unbroken pass-through chain really is NOT NULL end to end, so
 * `[false]`/NOT NULL is the mathematically accurate answer here, and `[true]`/nullable is merely the
 * more conservative one — neither direction is ever WRONG in the unsafe sense of claiming NOT NULL
 * when the true answer is nullable) but the two SCAN ORDERS can still disagree with each other for
 * the small number of views sitting within [VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET] steps of the
 * deepest point in a chain that itself exceeds the budget.
 *
 * This residual is accepted deliberately, for the same reason the budget itself is: no real schema
 * nests views anywhere near this depth, and closing it fully would require tracking, per cached
 * answer, how much additional depth its OWN computation would have needed to fully verify — an
 * "iterative worklist"-shaped mechanism this guard's single, narrow job (preventing
 * `java.lang.StackOverflowError`) does not warrant. This is NOT a gap to close with more machinery (a
 * dedicated large-stack thread, a full graph pre-sort, etc.); the guard's only job is preventing that
 * crash on a shape that does not occur in practice, and it does that job.
 *
 * Catalog scan order is NO LONGER the source of run-to-run non-determinism this once implied,
 * though: [PgCatalogLoader.loadViewColumnNullability]'s own sweep (the production caller that walks
 * every view/matview column in a schema through this SAME shared analyzer) is ordered deterministically
 * by relid then attnum — see `loadViewColumnNamesByRelidAndAttnum`'s `ORDER BY` — rather than left to
 * whatever order PostgreSQL's planner happens to return rows in. So while the CHOICE of scan order
 * still determines which of the (still individually sound) answers a deep chain's boundary-adjacent
 * views get, that choice is now FIXED for a given schema: the same schema produces the same generated
 * Kotlin types on every run, not merely on every run where the planner happens to agree with itself.
 */
internal const val VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET = 50

/**
 * Drives per-column nullability analysis for a SQL query on behalf of [loader]: fetching the
 * query's own parsed node tree (via `prosqlbody` or a probe function, see
 * [queryColumnNullabilityViaProsqlbody]'s own KDoc), then recursively resolving CTE bodies,
 * subqueries, and `MERGE` actions to feed [NodeTreeNullabilityAnalyzer] the source-column
 * not-null information it needs to evaluate each result column's expression.
 *
 * Split out from [loader]'s own catalog-loading responsibilities (schema introspection, function
 * metadata, safe-list lookups) because this is a distinct concern: [loader] answers "what does the
 * catalog say", while this class answers "is this specific query's result column nullable" by
 * combining catalog answers with the query's own parsed structure.
 */
internal class ColumnNullabilityAnalyzer(private val loader: PgCatalogLoader) {
  private val connection get() = loader.connection
  private val nodeTreeParser get() = loader.nodeTreeParser
  private val columnNotNullByRelidAndAttnum get() = loader.columnNotNullByRelidAndAttnum
  private val aggregateHasNonNullInitialValue get() = loader.aggregateHasNonNullInitialValue
  private val alwaysNonNullFunctionOids get() = loader.alwaysNonNullFunctionOids
  private val neverNullForNonNullInputOids get() = loader.neverNullForNonNullInputOids
  private val lagLeadWithDefaultOids get() = loader.lagLeadWithDefaultOids
  private val immutableFunctionOids get() = loader.immutableFunctionOids
  private val nonNullIffFirstArgumentNonNullFunctionOids get() = loader.nonNullIffFirstArgumentNonNullFunctionOids
  private val isStrictFunction get() = loader.isStrictFunction

  /**
   * Memoized per-relid view-column nullability, populated by [resolveViewColumnNullability]. One
   * entry per RESOLVED relid: index `i` (0-based) of the value list corresponds to attnum `i + 1`
   * (see [resolveViewColumnNullability]'s resno-contiguity argument for why this indexing is safe),
   * and each entry is `true` when that column may be `null`. A `null` VALUE (as opposed to an
   * absent key) means [relid] is not a view or materialized view at all — no `_RETURN` rule exists
   * for it in `pg_rewrite` — so [isColumnNotNull] must fall through to ordinary base-table
   * resolution for it instead.
   *
   * On-demand and memoized here, rather than an eager whole-database map the way the `#256` fix's
   * predecessor computed it. This does NOT mean a single schema-wide sweep skips resolving
   * unreferenced views — [PgCatalogLoader.loadViewColumnNullability] resolves every view/matview
   * column in its target schema on every call, by design (measured ~1.5s for 361 views on one real
   * schema) — what memoization buys is that resolving the SAME relid more than once is free after
   * the first: once directly, when that sweep reaches it, and again whenever ANY other view or
   * query analyzed by this SAME [ColumnNullabilityAnalyzer] instance selects from it — a nested
   * view chain sharing a common base view, or two sibling queries selecting from the same view,
   * both reuse the first resolution's answer instead of re-evaluating the node tree, and (within one
   * top-level resolution) a branching/diamond view graph that references the SAME view from two
   * different paths — see [viewColumnNullabilityTaintedRelids]'s KDoc — never re-walks it twice
   * either. Written to UNCONDITIONALLY by every successful [resolveViewColumnNullability] call
   * (never skipped, even for a [viewColumnNullabilityTaintedRelids]-tainted answer — see that
   * property's own KDoc for why skipping memoization outright, rather than tainting-then-evicting,
   * was tried and rejected: it makes a branching view graph re-walk shared ancestors exponentially
   * many times once ANY branch is deep enough to taint). The single-threaded JDBC [connection] this
   * class is built on means no synchronization is needed around this mutable map.
   */
  private val viewColumnNullabilityMemo = mutableMapOf<Int, List<Boolean>?>()

  /**
   * Relids currently being resolved by [resolveViewColumnNullability] — guards against infinite
   * recursion through a view dependency cycle.
   *
   * This guard is LOAD-BEARING, not merely defensive: PostgreSQL genuinely permits constructing a
   * cyclic view via `CREATE OR REPLACE VIEW`, which does NOT require every relation the new
   * definition references to already have existed when the ORIGINAL view was created — only that
   * they exist at the moment of the `CREATE OR REPLACE` itself. Verified live, PostgreSQL 18.4:
   * ```sql
   * CREATE VIEW b AS SELECT v FROM a;
   * CREATE OR REPLACE VIEW a AS SELECT v FROM b;  -- succeeds: a genuine mutual cycle, a <-> b
   * CREATE OR REPLACE VIEW self_v AS SELECT v FROM self_v;  -- succeeds: a direct self-cycle
   * ```
   * `private`: nothing outside this class needs to seed it directly — see
   * `QueryAnalysisTest.ViewNullability`'s dependency-cycle pin, which builds a REAL cycle via
   * `CREATE OR REPLACE VIEW` against a live database rather than reaching into this field.
   *
   * A view can never actually be QUERIED once it participates in a real cycle — PostgreSQL itself
   * refuses with `ERROR: infinite recursion detected in rules for relation "..."` — so this guard's
   * placeholder answer is never wrong in a way that matters for a genuinely cyclic view: there is no
   * "true" nullability to under-approximate, since no row can ever be produced from it at all. What
   * this guard protects is [resolveViewColumnNullability] ITSELF from an infinite Kotlin recursion
   * while proving that.
   */
  private val viewColumnNullabilityInProgress = mutableSetOf<Int>()

  /**
   * Current depth of NESTED [resolveViewColumnNullability] recursion (view-over-view resolution) —
   * see [VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET]'s KDoc for why this bound exists and how its
   * value was chosen. Incremented/decremented around the recursive portion of
   * [resolveViewColumnNullability] only; the single-threaded JDBC [connection] this class is built
   * on means no synchronization is needed around this mutable counter.
   *
   * Also used to detect the OUTERMOST call in a chain of recursive [resolveViewColumnNullability]
   * calls — the frame that entered at depth `0` and, once its own `finally` block brings this back
   * to `0`, evicts every [viewColumnNullabilityTaintedRelids] entry the WHOLE traversal accumulated;
   * see that property's KDoc.
   */
  private var viewColumnNullabilityRecursionDepth = 0

  /**
   * Total number of TAINT EVENTS [resolveViewColumnNullability] has produced since this
   * [ColumnNullabilityAnalyzer] was constructed — monotonically increasing, never reset. A taint
   * event is any of: [viewColumnNullabilityInProgress]'s cycle guard firing, the recursion-depth
   * guard firing, or a memo READ (the fast path at this method's very first line) landing on a relid
   * already in [viewColumnNullabilityTaintedRelids].
   *
   * Every frame CURRENTLY ON THE STACK when a taint event fires is an ancestor of it and has an
   * ANSWER BUILT FROM IT: the tainted relid's own (placeholder or cached-but-tainted) value feeds
   * into whatever `Var` evaluation was asking about it, which feeds into that frame's own
   * [resolveViewColumnNullability] result, and so on up the call chain — see
   * [resolveViewColumnNullability]'s use of this counter, which snapshots it on entry and compares
   * on exit: if the count changed anywhere DURING that frame's own execution window, a taint event
   * happened somewhere in its subtree (not necessarily an immediate child — any descendant, or a
   * sibling reached through a shared memo entry), so [viewColumnNullabilityTaintedRelids] gains this
   * frame's own relid too, propagating the taint one level further up. Comparing snapshots (not the
   * raw value) is what scopes the check to each frame's own window, so counting monotonically
   * forever, across unrelated LATER top-level resolutions too, is still correct: an unrelated
   * resolution's own entry/exit window simply won't straddle a taint event that happened elsewhere.
   */
  private var viewColumnNullabilityTaintEventCount = 0

  /**
   * Relids whose CURRENT [viewColumnNullabilityMemo] entry was computed from — directly, or
   * transitively through another tainted relid's cached answer — a placeholder produced by either
   * of [resolveViewColumnNullability]'s two guards, rather than a genuine, order-independent
   * evaluation of the view's own defining query.
   *
   * This is the fix for a real, reproduced defect in an earlier version of this guard, which simply
   * SKIPPED memoizing an affected relid's answer outright. Skipping memoization is unsound in a
   * different way than never guarding at all: on a BRANCHING view graph (e.g. `g_k AS SELECT a.v1,
   * b.v2 FROM g_{k-1} a JOIN g_{k-1} b ...`), a deep-enough truncating prefix makes every ancestor of
   * the truncation un-memoizable, so EVERY reference to a shared, tainted ancestor re-walks its
   * entire subtree from scratch — doubling the work at every branching level and turning what should
   * be linear-in-view-count work into exponential (measured: 2.1s / 7.7s / 31.0s at branching depths
   * 5 / 7 / 9 over a 46-deep truncating prefix, versus 92ms for the identical branching shape with no
   * truncating prefix at all).
   *
   * The fix instead memoizes EVERY successfully-computed answer unconditionally (so a branching
   * graph still resolves each distinct relid at most once per top-level call — see
   * [viewColumnNullabilityMemo]'s KDoc), tracks which of those answers are tainted in THIS set, and
   * evicts exactly the tainted ones once the OUTERMOST [resolveViewColumnNullability] call (the one
   * that started at recursion depth `0`) finishes — see that method's own use of
   * [viewColumnNullabilityRecursionDepth] to detect this moment. Cross-call order dependence (a
   * later, unrelated, possibly-shallower query reusing a poisoned answer left behind by an earlier,
   * deeper one) is eliminated this way, while intra-traversal caching — the property that keeps a
   * branching graph linear — survives untouched for the (overwhelmingly common) untainted case.
   *
   * The one subtlety this depends on: a memo READ (not just a memo WRITE) of an already-tainted
   * relid must ALSO count as a taint event for the reading frame — otherwise a SIBLING frame that
   * merely reads a tainted answer via the ordinary memo-hit fast path (having done no recursion of
   * its own) would see no counter movement across its own entry/exit window and incorrectly treat
   * itself as untainted. [resolveViewColumnNullability]'s memo-hit branch is written to account for
   * this; `QueryAnalysisTest.ViewNullability`'s `top → {a, mid}` / `a → mid` / `mid → self-cycle`
   * fixture exists specifically because it is the shape that would fail without it — `top` reaches
   * the tainted `mid` twice, once transitively through `a` and once directly, and only the SECOND
   * reference is a pure memo read.
   */
  private val viewColumnNullabilityTaintedRelids = mutableSetOf<Int>()

  /**
   * Replaces `?` parameter placeholders in [sql] with typed non-null sentinel values.
   *
   * Uses `PreparedStatement.getParameterMetaData()` to determine the PostgreSQL type of each
   * parameter, then builds a non-null literal of that type (e.g., `0::int4`, `''::text`).
   *
   * @return The SQL with `?` replaced by typed sentinels, or `null` if parameter metadata
   *   cannot be obtained (caller should fall back to NULL replacement).
   */
  private fun buildViewSqlWithSentinels(sql: String): String? {
    if ('?' !in sql) return sql
    return try {
      val sentinels = connection.prepareStatement(sql).use { preparedStatement ->
        val parameterMetaData = preparedStatement.parameterMetaData
        (1..parameterMetaData.parameterCount).map { index ->
          nonNullSentinel(parameterMetaData.getParameterTypeName(index))
        }
      }
      replaceParameterPlaceholdersWithSentinels(sql, sentinels)
    } catch (_: SQLException) {
      null
    }
  }

  /**
   * For [nodeTree]'s OWN outermost statement — never recursing into a CTE it declares; each CTE's
   * own body resolves its own MERGE independently (see [analyzeCteBodyNullability]) — determines
   * which of its two base-table relations (identified by `:rtable` varno) can be entirely absent
   * for some result row, via [explainMergeSideNullability] rather than `:mergeActionList`/text
   * inspection.
   *
   * A `MERGE`'s match-optionality (`WHEN NOT MATCHED BY SOURCE`, `WHEN NOT MATCHED [BY TARGET]
   * THEN INSERT`) is invisible to `:varnullingrels` — verified live: a `MERGE ... WHEN NOT MATCHED
   * BY SOURCE THEN DELETE RETURNING src.col` has an EMPTY `:varnullingrels` on `src.col`'s `Var`,
   * identical to an ordinary, always-present reference. [explainMergeSideNullability]'s KDoc has
   * the full reasoning for why `EXPLAIN`'s own join type answers this precisely instead.
   *
   * @param sql the EXACT (already sentinel-substituted) statement text to run `EXPLAIN` against —
   *   the WHOLE top-level statement, including any leading `WITH` clause, so a `MERGE` nested
   *   inside a CTE resolves through the SAME call as a top-level one, keyed by ITS OWN
   *   target/source relation names
   * @return an EMPTY map when [nodeTree]'s own outermost statement is not a `MERGE` at all; a map
   *   from varno to whether THAT relation can be entirely absent (containing the target and/or
   *   source varno, per [MergeSideNullability]) when it IS a `MERGE` and `EXPLAIN` successfully
   *   attributed the join; `null` when it's a `MERGE` but `EXPLAIN` could not resolve it (e.g. a
   *   `USING` clause with more than one relation of its own) — the caller must then treat this
   *   `MERGE` as entirely untrustworthy, never guessing at a partial answer
   */
  private fun mergeAbsentVarnos(
    nodeTree: String,
    rangeTable: Map<Int, Int>,
    @Language("PostgreSQL") sql: String,
  ): Map<Int, Boolean>? {
    if (nodeTreeParser.parseCommandType(nodeTree) != PgNodeTreeParser.COMMAND_TYPE_MERGE) return emptyMap()
    val targetVarno = nodeTreeParser.parseResultRelation(nodeTree)
    // A RETURNING list that only reads the target relation's own columns, or OLD/NEW references,
    // never needs EXPLAIN's resolution at all — see containsVarOutsideRelation's KDoc. Skipping it
    // here matters beyond saving an EXPLAIN round trip: a MERGE whose USING source is not a plain
    // base table (e.g. a VALUES list or a subquery) can never be resolved below, but that must not
    // block a RETURNING list that never depended on knowing which side of that join is nullable.
    val returningEntries = nodeTreeParser.parseReturningList(nodeTree)
    if (returningEntries.none { NodeTreeNullabilityAnalyzer.containsVarOutsideRelation(it.expression, targetVarno) }) {
      return emptyMap()
    }
    val targetRelid = rangeTable[targetVarno] ?: return null
    // A simple `MERGE INTO target USING source ON ...` has exactly one OTHER base-table :rtable
    // entry besides the target — the source. A `USING` clause with more than one relation of its
    // own (e.g. a join or subquery source) has no single relation this method can attribute a
    // join side to, so it bails rather than guess.
    val sourceEntries = rangeTable.filterKeys { it != targetVarno }
    if (sourceEntries.size != 1) return null
    val (sourceVarno, sourceRelid) = sourceEntries.entries.single()
    val targetName = resolveTableName(targetRelid) ?: return null
    val sourceName = resolveTableName(sourceRelid) ?: return null
    val mapping = explainMergeSideNullability(connection, sql, targetName, sourceName) ?: return null
    return buildMap {
      put(targetVarno, mapping.targetCanBeAbsent)
      put(sourceVarno, mapping.sourceCanBeAbsent)
    }
  }

  /**
   * Determines which result columns of a SQL query can be NULL, using full expression evaluation,
   * by way of PostgreSQL's `prosqlbody` — the analyzed query tree PostgreSQL stores for a
   * SQL-standard (`BEGIN ATOMIC ... END`) function body.
   *
   * `prosqlbody` holds the same post-parse-analysis `{QUERY ...}` node shape `pg_rewrite.ev_action`
   * does (neither is rewriter-expanded, so nested views stay relation RTEs in both), but — unlike
   * `CREATE VIEW` — PostgreSQL populates it for `UPDATE`/`DELETE`/`MERGE ... RETURNING` and for
   * data-modifying CTEs, because only `CREATE VIEW` itself rejects a data-modifying statement, not
   * a SQL-standard function body. [analyzeNodeTree] reads the same field shape either way, since
   * [PgNodeTreeParser]'s extraction methods locate each field by a depth-one scan starting at the
   * first unescaped `{`, ignoring whatever wrapping parentheses precede or follow it.
   *
   * The function is created with ZERO arguments: a real `$n` parameter would appear as a `PARAM`
   * node in the tree, which [NodeTreeNullabilityAnalyzer] has no case for and would fall through to
   * its `Unknown` (safe-nullable) handling for every expression that touches it — silently
   * widening every parameter-touching column. [sql]'s own `?` placeholders are therefore replaced
   * with typed non-null sentinel literals internally, via [buildViewSqlWithSentinels], before this
   * method is ever invoked.
   *
   * A statement with no result columns at all (an `INSERT`/`UPDATE`/`DELETE`/`MERGE` without
   * `RETURNING`) fails PostgreSQL's `RETURNS SETOF record` check on function creation — there is
   * nothing to probe, and the [SQLException] is caught here rather than propagated.
   *
   * @param sql the SQL query or DML statement to analyze; any `?` parameter placeholder is
   *   replaced with a typed non-null sentinel literal internally (see [buildViewSqlWithSentinels])
   * @return one boolean per result column in `SELECT`/`RETURNING` order (`true` means nullable), or
   *   `null` if [sql] has no result columns to probe or the probe itself failed for any reason —
   *   the caller must treat `null` as "this path has no answer", never as "zero columns."
   */
  internal fun queryColumnNullabilityViaProsqlbody(@Language("PostgreSQL") sql: String): List<Boolean>? {
    val substitutedSql = buildViewSqlWithSentinels(sql) ?: replaceParameterPlaceholders(sql)
    val functionName = "norm_nullability_${UUID.randomUUID().toString().replace("-", "")}"
    return try {
      connection.createStatement().use { statement ->
        statement.execute(
          "CREATE FUNCTION pg_temp.$functionName() RETURNS SETOF record LANGUAGE sql " +
            // The newline before "; END" is load-bearing, not style: [substitutedSql] is caller-
            // supplied SQL text that can legitimately end in a trailing `--` line comment (ordinary
            // in a queries.sql), which extends to end of LINE. Without a newline separating it from
            // "; END", the comment swallows the terminator too, and PostgreSQL sees unterminated
            // input instead of a syntax error naming the real cause. Verified live.
            "BEGIN ATOMIC $substitutedSql\n; END",
        )
      }
      try {
        val nodeTree = connection.createStatement().use { statement ->
          statement.executeQuery(
            "SELECT prosqlbody::text FROM pg_proc " +
              // "pg_temp" is a per-session ALIAS, not a literal schema name — the real catalog row
              // is named "pg_temp_N", so 'pg_temp'::regnamespace fails to resolve at all (verified
              // live: "ERROR: schema "pg_temp" does not exist"). pg_my_temp_schema() returns the
              // current session's actual temp schema OID directly.
              "WHERE proname = '$functionName' AND pronamespace = pg_my_temp_schema()",
          ).use { resultSet ->
            check(resultSet.next()) { "No pg_proc row found for probe function $functionName" }
            resultSet.getString(1)
          }
        }
        val rangeTable = nodeTreeParser.parseRangeTable(nodeTree)
        val mergeAbsent = mergeAbsentVarnos(nodeTree, rangeTable, substitutedSql) ?: return null
        // '?' in sql, not substitutedSql: a sentinel-substituted CONST is byte-identical to a
        // hand-written literal once embedded in the SQL text — the parsed tree retains no memory
        // of which one it was. trustAssignedExpressions=false whenever the ORIGINAL sql had ANY
        // parameter blocks analyzeNodeTree's :targetList-to-:returningList substitution (see its
        // KDoc) for the WHOLE statement, not just the specific assignment a parameter feeds,
        // because there is no structural way from here to tell which assignment(s) it was.
        analyzeNodeTree(
          nodeTree,
          applyQualNarrowing = true,
          sql = substitutedSql,
          trustAssignedExpressions = '?' !in sql,
          mergeAbsentVarnos = mergeAbsent,
        )
      } finally {
        connection.createStatement().use { statement ->
          statement.execute("DROP FUNCTION IF EXISTS pg_temp.$functionName()")
        }
      }
    } catch (_: SQLException) {
      null
    }
  }

  /**
   * Computes per-column nullability from [nodeTree] — the `pg_rewrite.ev_action` text of a
   * temporary view, or the `pg_proc.prosqlbody` text of a temporary probe function; both share the
   * same post-parse-analysis `{QUERY ...}` node shape (see [queryColumnNullabilityViaProsqlbody]'s
   * KDoc).
   *
   * Reads the `:targetList` first — this covers every plain `SELECT`, including one that reaches
   * this function only because it CONTAINS a data-modifying CTE (the outer statement is still a
   * `SELECT`, so PostgreSQL's own `CREATE VIEW` restriction, and by extension nothing here, ever
   * blocked it). Falls back to `:returningList` when the outer statement's OWN target list is
   * empty — true only for a topmost `UPDATE`/`DELETE`/`MERGE ... RETURNING`: a plain `SELECT`
   * always has a non-empty target list, or [connection] would have rejected it as a query with no
   * result columns before ever reaching this point.
   *
   * @param applyQualNarrowing When `false`, disables `WHERE`-clause qual narrowing
   *   ([NodeTreeNullabilityAnalyzer.qualProvenNonNullVars]) for this entire call — the top-level
   *   query AND every nested CTE body and subquery reached from it. Threaded through rather than a
   *   fixed `true` so a future caller with a rewritten body whose `WHERE`/`ON` predicate no longer
   *   corresponds to what `RETURNING` sees can suppress it; every current caller passes `true`.
   */
  private fun analyzeNodeTree(
    nodeTree: String,
    applyQualNarrowing: Boolean,
    @Language("PostgreSQL") sql: String,
    trustAssignedExpressions: Boolean = true,
    mergeAbsentVarnos: Map<Int, Boolean> = emptyMap(),
  ): List<Boolean> {
    val rangeTable = nodeTreeParser.parseRangeTable(nodeTree) // varno → relid (base tables only)
    // GROUP BY queries use an *GROUP* RTE (rtekind 9) whose target list VARs reference the group
    // entry varno instead of the base table varno directly. Resolve these back to their base table
    // column so isSourceColumnNotNull can check pg_attribute.attnotnull correctly.
    //
    // EXCEPTION: When GROUPING SETS, CUBE, or ROLLUP is used, GROUP BY columns can receive NULL
    // for rows where the column is not part of the current grouping set. PostgreSQL 18 enforces
    // this via a *GROUP* RTE (rtekind 9): target list VARs reference the GROUP RTE instead of the
    // base table, so parseRangeTable() can't find them and they fall back to nullable on their
    // own. PostgreSQL 16/17 have no GROUP RTE, so this exception skips GROUP RTE resolution
    // entirely; the actual nullability override for grouping keys (including EXPRESSION keys
    // such as `ROLLUP(lower(a))`, which never produce a bare {VAR } target-list entry to remap
    // here) is applied by NodeTreeNullabilityAnalyzer.extractColumnNullability via the
    // hasGroupingSets flag passed to buildAnalyzer below.
    val hasGroupingSets = nodeTreeParser.hasGroupingSets(nodeTree)
    val groupRteMap = if (hasGroupingSets) {
      emptyMap()
    } else {
      nodeTreeParser.parseGroupRteMap(nodeTree) // (groupVarno, attrPos) → (baseVarno, baseVarattno)
    }
    // For subquery RTEs (rtekind 1), the outer VAR's varno is not in rangeTable.
    // Resolve their nullability by recursively analyzing each subquery's target list.
    // The map is keyed by (varno, varattno) for direct lookup in isSourceColumnNotNull.
    val subqueryColumnNotNull = buildSubqueryColumnNotNull(nodeTree, applyQualNarrowing, sql)
    val cteColumnNotNull = buildCteColumnNotNull(nodeTree, applyQualNarrowing, sql)
    // A non-zero :resultRelation means this is an INSERT/UPDATE/DELETE/MERGE, not a SELECT — see
    // parseResultRelation's KDoc. Its :targetList holds the value expressions being WRITTEN to
    // each explicitly-assigned column of the target relation (keyed by :resno = the column's
    // attribute number), which is exactly what a :returningList Var referencing that SAME
    // (resultRelationVarno, attno) pair actually reads back — NOT the column's general catalog
    // constraint, which says nothing about what THIS statement is about to write. See
    // targetListByResno's use below.
    val resultRelationVarno = nodeTreeParser.parseResultRelation(nodeTree)
    val targetListByResno = if (resultRelationVarno == 0 || !trustAssignedExpressions) {
      // !trustAssignedExpressions means the ORIGINAL sql (before sentinel substitution) contained
      // a `?` parameter placeholder somewhere — see queryColumnNullabilityViaProsqlbody's call
      // site KDoc. A sentinel-substituted CONST is byte-identical, in the parsed tree, to a
      // hand-written literal: there is no structural signal left to tell "the caller supplied
      // this at runtime, and could supply NULL" from "the query text itself guarantees this value"
      // for any SPECIFIC assignment, so trusting :targetList at all is unsafe for the WHOLE
      // statement once ANY parameter exists anywhere in it. Verified live: `INSERT INTO t(name)
      // VALUES (?) RETURNING name` reports NOT NULL if the sentinel substitution is trusted here,
      // even though the caller can bind an actual `NULL` for that exact parameter.
      emptyMap()
    } else {
      // rangeTable[resultRelationVarno] is only present for an ordinary base-table target (rtekind
      // 0) — never null for a real INSERT/UPDATE/DELETE/MERGE, since PostgreSQL requires a real
      // relation to write to, but defensively treated as "substitution unsafe" (empty map) rather
      // than trusting an assignment against a target this class cannot even identify.
      val targetRelid = rangeTable[resultRelationVarno]
      if (targetRelid != null && isSubstitutionSafeForRelation(targetRelid)) {
        nodeTreeParser.parseTargetList(nodeTree).associate { it.resultNumber to it.expression }
      } else {
        emptyMap()
      }
    }
    // GROUPING SETS/CUBE/ROLLUP null-extend grouping keys AFTER the WHERE clause has already
    // filtered rows, so a qual can never prove a grouped result column non-null. This matters
    // even for a NOT NULL base column, because null-extension overrides the base column's own
    // constraint. Suppress qual narrowing for the entire block rather than trying to map a
    // (possibly expression) grouping key back to its null-extended leaf Vars — that is more
    // machinery than this warrants, and a subtle mistake there would reintroduce an unsound
    // narrowing. This is conservative by construction: every non-key output column of a
    // grouping-sets query is an aggregate, so suppressing narrowing here costs nothing real.
    //
    // A non-zero resultRelationVarno (an UPDATE/DELETE/MERGE) suppresses narrowing for a similar
    // reason: a qual that looks like it proves a RETURNING column non-null may in fact be testing
    // the value the statement's own SET clause (or, for MERGE, an update/insert action) is about
    // to overwrite.
    val qualNotNullVars = if (applyQualNarrowing && !hasGroupingSets && resultRelationVarno == 0) {
      NodeTreeNullabilityAnalyzer.qualProvenNonNullVars(nodeTree, isStrictFunction)
    } else {
      emptySet()
    }
    val plainIsSourceColumnNotNull = { varno: Int, varattno: Int ->
      if (mergeAbsentVarnos[varno] == true) {
        // A MERGE relation EXPLAIN determined can be entirely absent for some result row (see
        // mergeAbsentVarnos' KDoc) can never be proven non-null here, regardless of what a qual or
        // this column's own catalog constraint would otherwise say — those both describe the
        // relation's rows WHEN PRESENT, which says nothing about whether this specific result row
        // has one at all.
        false
      } else if (isProvenByQuals(qualNotNullVars, groupRteMap, varno, varattno)) {
        true
      } else {
        val relid = rangeTable[varno]
        if (relid != null) {
          isColumnNotNull(relid to varattno)
        } else {
          val baseVar = groupRteMap[varno to varattno]
          if (baseVar != null) {
            val baseRelid = rangeTable[baseVar.first]
            baseRelid != null && isColumnNotNull(baseRelid to baseVar.second)
          } else {
            subqueryColumnNotNull[varno to varattno] == true ||
              cteColumnNotNull[varno to varattno] == true
          }
        }
      }
    }
    val forceNewNullable = forcesNewNullable(nodeTree)
    val analyzer = buildAnalyzer(
      hasGroupingSets = hasGroupingSets,
      forceNewNullable = forceNewNullable,
      applyQualNarrowing = applyQualNarrowing,
      isSourceColumnNotNull = plainIsSourceColumnNotNull,
    )
    // :returningList must be checked FIRST, not as a fallback for an empty :targetList: an INSERT
    // or UPDATE's OWN :targetList holds the value expressions being WRITTEN to each assigned
    // column — a completely different, and typically shorter or differently-shaped, list than its
    // RETURNING projection — so it is very often non-empty even when :returningList is what this
    // call actually needs to read (verified live: `INSERT INTO t(name) VALUES ('test') RETURNING
    // *` against `t(id, name)` has a one-entry :targetList for "name" alone, but a two-entry
    // :returningList for "id, name"). A plain `SELECT` never populates :returningList at all, so
    // this ordering only ever matters for the DML-with-RETURNING case
    // [queryColumnNullabilityViaProsqlbody] reaches at the top level.
    val returningEntries = nodeTreeParser.parseReturningList(nodeTree)
    if (returningEntries.isNotEmpty()) {
      // A SEPARATE analyzer whose isSourceColumnNotNull substitutes a Var referencing
      // (resultRelationVarno, resno) with the ASSIGNED expression's own nullability — evaluated by
      // the PLAIN analyzer, deliberately NOT this substituting one, so a self-referencing
      // assignment (`SET note = note || 'x'`) reads note's OLD (plain, un-substituted) value for
      // that inner reference rather than looping back into its own substitution forever. A column
      // the statement never assigns (no entry in targetListByResno) falls through to the identical
      // plain catalog/qual/subquery/CTE resolution [analyzer] itself uses, which is exactly correct
      // for that column's pass-through (unmodified) value.
      val returningAnalyzer =
        buildAnalyzer(
          hasGroupingSets = false,
          forceNewNullable = forceNewNullable,
          applyQualNarrowing = applyQualNarrowing,
        ) { varno, varattno ->
          val assignedExpression = if (varno == resultRelationVarno) targetListByResno[varattno] else null
          if (assignedExpression != null) {
            analyzer.isNonNull(assignedExpression)
          } else {
            plainIsSourceColumnNotNull(varno, varattno)
          }
        }
      return returningEntries
        .filter { !it.isJunk }
        .sortedBy { it.resultNumber }
        .map { entry -> !returningAnalyzer.isNonNull(entry.expression) }
    }
    return analyzer.extractColumnNullability(nodeTree)
  }

  /**
   * `true` when `(relid, attnum)` — [key] — is guaranteed NOT NULL, whether [key] identifies a
   * plain base-table column (checked against [columnNotNullByRelidAndAttnum]'s catalog constraint)
   * or a view/materialized-view column (resolved via [resolveViewColumnNullability]'s full node-tree
   * evaluation of the view's own defining query — see that method's KDoc for why a view column can
   * never be answered from `pg_attribute.attnotnull` alone, which PostgreSQL always reports `false`
   * for a view column regardless of the view's actual definition).
   *
   * `internal`, not `private`: [PgCatalogLoader.loadViewColumnNullability] calls this directly to
   * resolve a whole schema's worth of view columns by name.
   */
  internal fun isColumnNotNull(key: Pair<Int, Int>): Boolean {
    // A negative attribute number is a SYSTEM column (ctid, xmin, xmax, cmin, cmax, tableoid) —
    // pg_attribute rows for these are typically excluded from the catalog queries that populate
    // columnNotNullByRelidAndAttnum (which filters attnum > 0), so a Var referencing one would
    // otherwise fall through to "not found" (nullable) even though every system column is
    // unconditionally non-null for any real, returned row.
    if (key.second < 0) return true
    if (columnNotNullByRelidAndAttnum[key] == true) return true
    val viewNullability = resolveViewColumnNullability(key.first) ?: return false
    return viewNullability.getOrNull(key.second - 1) == false
  }

  /**
   * Resolves [relid]'s per-column nullability by fully evaluating its view definition's own node
   * tree (`pg_rewrite`'s `_RETURN` rule) — the `#256` fix for the previous `pg_depend` name-join,
   * which inherited a same-named source column's `NOT NULL` constraint regardless of what the
   * view's own target-list expression actually computed (e.g. `SELECT NULLIF(v, 'x') AS v FROM u`
   * was reported `NOT NULL` whenever `u.v` was, even though `NULLIF` can always return `null`).
   *
   * @return one nullable flag per user-visible column (`attnum > 0 AND NOT attisdropped`), index
   *   `i` corresponding to attnum `i + 1`. This resno-contiguity is safe because there is no `ALTER
   *   VIEW DROP COLUMN` — only `CREATE OR REPLACE VIEW`, which can only APPEND columns, never drop
   *   or reorder one — so a view's non-junk target-list resnos are always contiguous `1..N` with no
   *   gaps (verified live, PostgreSQL 18.4: an `ORDER BY` expression not already in the SELECT list
   *   produces a `resjunk true` target entry, but it is always APPENDED after every real column, so
   *   filtering junk and sorting by resno — exactly what [analyzeViewNodeTree] already does — never
   *   disturbs this alignment; also verified for `DISTINCT ON` with an unselected key, `ORDER BY` by
   *   an unselected expression AND by ordinal, `GROUP BY` on an unselected key, a view over a table
   *   with a dropped column, `SELECT *` over a table widened after the view was created, and `UNION`
   *   with `ORDER BY`). [alignViewColumnNullability] guards this fact ever silently breaking on a
   *   future PostgreSQL version anyway, since no real SQL is known to violate it today.
   *   Returns `null` when [relid] is not a view or materialized view at all (no `_RETURN` rule).
   */
  internal fun resolveViewColumnNullability(relid: Int): List<Boolean>? {
    if (viewColumnNullabilityRecursionDepth >= VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET) {
      // Recursion depth guard — see VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET's KDoc: a pathologically
      // deep (but not cyclic) chain of nested pass-through views can exhaust the JVM stack before
      // ever revisiting a relid the cycle guard below would catch. A taint event (see
      // viewColumnNullabilityTaintEventCount's KDoc), not memoized directly: it is only correct
      // WHILE this deep, not relid's own true answer.
      //
      // MUST run before the memo lookup below, not after: [relid] itself sitting at or past this
      // depth must always truncate, never returning a cached answer instead — see
      // VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET's KDoc for the reproduced non-determinism this
      // ordering closes, and for the honest limit of what it closes: an UNTAINTED, permanently
      // cached answer for a relid sitting STRICTLY SHALLOWER than this boundary — reached a few
      // frames before truncation would occur, not at the point this specific check intercepts — can
      // still be reused as-is, since a memo hit performs no recursion and never revisits this check.
      // That residual is accepted deliberately; this ordering only guarantees the narrower property
      // that [relid] itself is never the one to skip truncation via its own stale cache entry.
      viewColumnNullabilityTaintEventCount++
      return List(columnCountFor(relid)) { true }
    }
    if (viewColumnNullabilityMemo.containsKey(relid)) {
      // A memo READ of an already-tainted relid is ITSELF a taint event for whichever frame is
      // reading it — see viewColumnNullabilityTaintedRelids' KDoc for why this propagation is
      // required, not merely convenient: without it, a sibling frame that reaches a tainted relid
      // purely through this fast path (no recursion of its own) would see no counter movement across
      // its own entry/exit window and wrongly conclude it is untainted itself.
      if (relid in viewColumnNullabilityTaintedRelids) viewColumnNullabilityTaintEventCount++
      return viewColumnNullabilityMemo[relid]
    }
    if (relid in viewColumnNullabilityInProgress) {
      // Dependency cycle guard — see viewColumnNullabilityInProgress's KDoc: this is load-bearing,
      // since CREATE OR REPLACE VIEW can genuinely construct a cycle. This placeholder is a taint
      // event (see viewColumnNullabilityTaintEventCount's KDoc), not memoized directly: it is only
      // correct WHILE the cycle is being walked, not relid's own true answer.
      viewColumnNullabilityTaintEventCount++
      return List(columnCountFor(relid)) { true }
    }
    val taintEventCountAtEntry = viewColumnNullabilityTaintEventCount
    val isOutermostCall = viewColumnNullabilityRecursionDepth == 0
    viewColumnNullabilityInProgress.add(relid)
    viewColumnNullabilityRecursionDepth++
    try {
      val nodeTree = fetchViewNodeTree(relid)
      if (nodeTree == null) {
        // Not a view at all — this determination did no recursion of its own, so it can never be
        // tainted by anything happening elsewhere; always safe to memoize permanently.
        viewColumnNullabilityMemo[relid] = null
        return null
      }
      val nullability = try {
        if (nodeTreeParser.hasSetOperations(nodeTree)) {
          // See analyzeCteBodyNullability's identical split: a UNION ALL/INTERSECT/EXCEPT at the
          // view's own top level must be resolved branch-by-branch and OR-combined, not by running
          // the ordinary analyzeViewNodeTree path against it directly. The synthetic "cteName"
          // passed here can never collide with a real CTE self-reference the way a WITH RECURSIVE
          // CTE's own name would: this concern is about a NAME collision against a WITH-clause CTE
          // declared inside the view's OWN body, not about whether the view itself can be cyclic
          // (it can, via CREATE OR REPLACE VIEW — see viewColumnNullabilityInProgress's KDoc — but
          // that is a FROM-clause relation self-reference, resolved by the cycle guard above, and
          // has no bearing on this CTE-name-keyed map at all), so previouslyResolved's
          // self-reference entry is always dead weight here, never actually consulted.
          analyzeSetOperationBranches(nodeTree, emptyMap(), "__norm_view_relid_$relid", applyQualNarrowing = true)
        } else {
          analyzeViewNodeTree(nodeTree)
        }
      } catch (_: SQLException) {
        null
      }
      val expectedColumnCount = columnCountFor(relid)
      // Mirrors alignViewColumnNullability's own fallback condition exactly (both a caught
      // SQLException above and an unanalyzable analyzeSetOperationBranches result reach it via
      // `nullability == null`) — treated as a taint event for the SAME reason the two guards above
      // are: an all-nullable answer forced by a TRANSIENT failure (or a structural inability
      // specific to THIS node tree) must not be cached as relid's answer for this analyzer's entire
      // remaining lifetime with no eviction path. This frame's own entry/exit comparison below
      // (shared with every other taint source) is what actually marks relid — and any ancestor whose
      // window spans this moment — tainted; this just supplies the missing signal.
      if (nullability == null || nullability.size != expectedColumnCount) {
        viewColumnNullabilityTaintEventCount++
      }
      val result = alignViewColumnNullability(nullability, expectedColumnCount)
      // ALWAYS memoize — see viewColumnNullabilityMemo's KDoc for why unconditional memoization
      // (rather than skipping it for a tainted answer) is required to keep a branching view graph
      // linear. Whether this frame's OWN window saw a taint event (from any descendant, a sibling
      // reached through a shared, already-tainted memo entry, or its OWN fallback above) instead
      // decides whether relid joins viewColumnNullabilityTaintedRelids — see that property's KDoc.
      viewColumnNullabilityMemo[relid] = result
      if (viewColumnNullabilityTaintEventCount != taintEventCountAtEntry) {
        viewColumnNullabilityTaintedRelids.add(relid)
      }
      return result
    } finally {
      viewColumnNullabilityRecursionDepth--
      viewColumnNullabilityInProgress.remove(relid)
      if (isOutermostCall) {
        // Back to the frame that started this whole traversal at depth 0: evict every tainted entry
        // IT accumulated, so a LATER, independent (and possibly shallower, and therefore able to
        // compute a genuine answer) query against any of them recomputes fresh — see
        // viewColumnNullabilityTaintedRelids' KDoc. Untainted entries this traversal computed stay
        // cached; only the tainted subset is ever evicted.
        for (taintedRelid in viewColumnNullabilityTaintedRelids) {
          viewColumnNullabilityMemo.remove(taintedRelid)
        }
        viewColumnNullabilityTaintedRelids.clear()
      }
    }
  }

  /** The number of user-visible columns (`attnum > 0 AND NOT attisdropped`) [relid] has. */
  private fun columnCountFor(relid: Int): Int = columnNotNullByRelidAndAttnum.keys.count { it.first == relid }

  /**
   * Aligns [nullability] — one flag per non-junk target-list entry, in resno order, as produced by
   * [resolveViewColumnNullability]'s own node-tree evaluation — onto exactly [expectedColumnCount]
   * entries, or falls back to nullable for every column when the two disagree (including when
   * [nullability] is `null`, meaning the analysis that produced it could not answer at all, e.g. a
   * `MERGE`-shaped `analyzeSetOperationBranches` failure).
   *
   * This defensive check has NOT been observed to trigger from any real SQL — see
   * [resolveViewColumnNullability]'s resno-contiguity KDoc for the live sweep this backs — so it is
   * kept as insurance against a future PostgreSQL version violating that argument, rather than as a
   * currently-reachable code path. `internal`, not `private`, purely so it can be unit-tested
   * directly with a synthetic mismatch: no known real SQL reaches this branch, so there is no SQL
   * fixture that could exercise it end-to-end.
   */
  internal fun alignViewColumnNullability(nullability: List<Boolean>?, expectedColumnCount: Int): List<Boolean> =
    if (nullability != null && nullability.size == expectedColumnCount) {
      nullability
    } else {
      List(expectedColumnCount) { true }
    }

  /**
   * `true` when [relid] currently has a (possibly tainted) answer cached in
   * [viewColumnNullabilityMemo] — `internal`, not `private`, purely so a test can assert directly on
   * EVICTION having actually happened.
   *
   * A missing taint propagation is USUALLY value-observable — `QueryAnalysisTest.ViewNullability`
   * has a dedicated pin (`topv`/`y`/a deep chain) that catches one this way, by deliberately sharing
   * a tainted common ancestor between a genuinely NOT NULL view and a truncating one — so this is
   * NOT a fundamental limitation of black-box, output-value comparison in general. It is specific to
   * the `top → {a, mid}` / `a → mid` / `mid → self-cycle` fixture's OWN shape: `mid` is an
   * UNCONDITIONAL self-cycle, so every value derived from it — correctly re-derived, or incorrectly
   * left stale — is the identical safe-nullable placeholder either way, with no dependency on
   * external state to observe through. This direct assertion is what pins that ONE shape's specific
   * hole regardless.
   */
  internal fun isRelidMemoized(relid: Int): Boolean = viewColumnNullabilityMemo.containsKey(relid)

  /**
   * Entry point for [resolveViewColumnNullability]: evaluates [nodeTree] — a view's own
   * `pg_rewrite.ev_action` `_RETURN` rule text — through the SAME [analyzeNodeTree] machinery an
   * ordinary query already uses, rather than widening [analyzeNodeTree]'s own visibility for this
   * one caller.
   *
   * Always passes `applyQualNarrowing = true` and `sql = ""`, and takes [analyzeNodeTree]'s
   * defaults for `trustAssignedExpressions` (`true`: a view has no `?` parameter placeholder to
   * distrust) and `mergeAbsentVarnos` (empty: a view's defining query is always a plain `SELECT`,
   * never a data-modifying statement, so it can never itself be a `MERGE`).
   */
  internal fun analyzeViewNodeTree(nodeTree: String): List<Boolean> =
    analyzeNodeTree(nodeTree, applyQualNarrowing = true, sql = "")

  /**
   * Fetches [relid]'s `_RETURN` rule text (`pg_rewrite.ev_action`, the query PostgreSQL runs when
   * the view is selected from) for [resolveViewColumnNullability], or `null` when [relid] is not a
   * view or materialized view, or otherwise has no such rule.
   *
   * Guarded by `relkind IN ('v', 'm')` so a base table that happens to carry an unrelated
   * `ev_type = '1'` rule is never mistaken for a view — `ev_type = '1'` alone identifies only that a
   * rule is a `_RETURN` rule, not that its relation is actually a view.
   */
  private fun fetchViewNodeTree(relid: Int): String? = try {
    connection.prepareStatement(
      """
      SELECT rw.ev_action::text AS node_tree
      FROM pg_catalog.pg_rewrite rw
      JOIN pg_catalog.pg_class c ON c.oid = rw.ev_class
      WHERE rw.ev_class = ? AND rw.ev_type = '1' AND c.relkind IN ('v', 'm')
      """.trimIndent(),
    ).use { preparedStatement ->
      preparedStatement.setInt(1, relid)
      preparedStatement.executeQuery().use { resultSet ->
        if (resultSet.next()) resultSet.getString("node_tree") else null
      }
    }
  } catch (_: SQLException) {
    null
  }

  /**
   * Creates a [NodeTreeNullabilityAnalyzer] pre-configured with this loader's catalog lookups.
   *
   * All constructor arguments except [isSourceColumnNotNull] are identical across every call site
   * in this class. This method captures the common configuration so callers only need to supply
   * the source-column resolution strategy, which varies by context (outer query, CTE body,
   * subquery).
   *
   * @param applyQualNarrowing See [analyzeNodeTree]'s parameter of the same name — passed through
   *   only so [isSubLinkSubqueryColumnNotNull]'s wiring below can apply the SAME qual-narrowing
   *   policy to a `SubLink`'s subselect that the caller applies to everything else.
   * @param depth The [subLinkSubqueryColumnNotNull] recursion budget for a `SubLink` encountered by
   *   the returned analyzer — see that method's KDoc for why a budget is mandatory (a subselect can
   *   itself contain a `SubLink`, whose own subselect can contain another). Defaults to
   *   [SUBLINK_ANALYSIS_DEPTH_BUDGET] for every analyzer built directly from a top-level node tree,
   *   CTE body, or subquery-RTE body; [subLinkSubqueryColumnNotNull] passes `depth - 1` when
   *   building the analyzer for a `SubLink`'s OWN subselect, so the budget only ever decreases
   *   along a chain of NESTED sublinks, never along the unrelated CTE/subquery-RTE recursion this
   *   class already performs independently of it.
   *
   *   No `MERGE`-resolution parameter is threaded through here: a sublink's `:subselect` is, by SQL
   *   grammar, always a `SELECT` — a `MERGE`, `UPDATE`, or `DELETE` can never appear as a sublink's
   *   own subquery body — so [mergeAbsentVarnos] can never apply to anything
   *   [subLinkSubqueryColumnNotNull] reaches, and there is nothing for a `sql`/`EXPLAIN` parameter
   *   here to resolve.
   */
  private fun buildAnalyzer(
    hasGroupingSets: Boolean = false,
    forceNewNullable: Boolean = false,
    applyQualNarrowing: Boolean = true,
    depth: Int = SUBLINK_ANALYSIS_DEPTH_BUDGET,
    isSourceColumnNotNull: (varno: Int, varattno: Int) -> Boolean,
  ): NodeTreeNullabilityAnalyzer = NodeTreeNullabilityAnalyzer(
    isStrict = isStrictFunction,
    hasNonNullInitialValue = { oid -> aggregateHasNonNullInitialValue[oid] == true },
    isSourceColumnNotNull = isSourceColumnNotNull,
    isOuterJoinNullable = { nullingRelations -> nullingRelations.isNotEmpty() },
    isAlwaysNonNull = { oid -> oid in alwaysNonNullFunctionOids },
    isNeverNullForNonNullInput = { oid -> oid in neverNullForNonNullInputOids },
    isLagLeadWithDefault = { oid -> oid in lagLeadWithDefaultOids },
    isFoldableToConst = { oid -> oid in immutableFunctionOids },
    isNonNullIffFirstArgumentNonNull = { oid -> oid in nonNullIffFirstArgumentNonNullFunctionOids },
    isSubLinkSubqueryColumnNotNull = { subselectBlock ->
      subLinkSubqueryColumnNotNull(subselectBlock, applyQualNarrowing, depth)
    },
    hasGroupingSets = hasGroupingSets,
    forceNewNullable = forceNewNullable,
  )

  /**
   * Backs [NodeTreeNullabilityAnalyzer]'s `isSubLinkSubqueryColumnNotNull` callback: `true` when
   * [subselectBlock] — the raw `{QUERY ...}` text of an `ANY_SUBLINK`'s `:subselect` — produces
   * EXACTLY ONE non-junk output column and that column is provably non-null.
   *
   * Set-operation subselects (`UNION`/`INTERSECT`/`EXCEPT`) are rejected outright, the same
   * conservative default [buildSubqueryColumnNotNull] applies to a `FROM`-clause subquery RTE for
   * the identical reason: tracing through would report only the first branch's nullability, not
   * the union across every branch (see that method's own KDoc).
   *
   * Deliberately does NOT resolve a CTE the subselect references via `:ctelevelsup` (an enclosing
   * scope's `WITH` clause) — [analyzeQueryBlockNullability] is called with an EMPTY `resolvedCtes`
   * map, so such a reference falls through to its existing "CTE not found" nullable default, which
   * is already correct: resolving an enclosing CTE from here would require re-entering
   * [resolveCteBodies] for a scope this method has no direct access to without threading
   * substantially more context through every [buildAnalyzer] call site for a case this rule does
   * not need precision for.
   *
   * @param depth remaining recursion budget — see [buildAnalyzer]'s `depth` parameter KDoc.
   *   Returns `false` (safe: nullable) once exhausted, so a `SubLink` nested inside another
   *   `SubLink`'s subselect cannot recurse indefinitely.
   */
  private fun subLinkSubqueryColumnNotNull(subselectBlock: String, applyQualNarrowing: Boolean, depth: Int): Boolean {
    if (depth <= 0) return false
    if (nodeTreeParser.hasSetOperations(subselectBlock)) return false
    val nullability = analyzeQueryBlockNullability(subselectBlock, applyQualNarrowing, emptyMap(), depth - 1)
    return nullability.size == 1 && !nullability[0]
  }

  /**
   * `true` when a `RETURNING WITH (OLD AS o, NEW AS n)` reference to `NEW` in [nodeTree] must be
   * forced nullable — see [NodeTreeNullabilityAnalyzer]'s `forceNewNullable` constructor
   * parameter's KDoc for the full reasoning (a plain `DELETE` never has a `NEW` row at all; a
   * `MERGE` might not, depending on which `WHEN` clause matched a given result row).
   */
  private fun forcesNewNullable(nodeTree: String): Boolean = when (nodeTreeParser.parseCommandType(nodeTree)) {
    PgNodeTreeParser.COMMAND_TYPE_DELETE -> true
    // Only a MERGE with at least one DELETE action can leave NO new row behind for SOME result
    // row — see hasDeleteMergeAction's KDoc. A MERGE with only UPDATE/INSERT actions always
    // writes or inserts a row, so NEW is exactly as trustworthy there as an ordinary column.
    PgNodeTreeParser.COMMAND_TYPE_MERGE -> nodeTreeParser.hasDeleteMergeAction(nodeTree)
    else -> false
  }

  /**
   * Returns `true` when `(varno, varattno)` is proven non-null by the query's `WHERE` clause,
   * either directly or through the GROUP RTE remap (target-list `Var`s point at the GROUP RTE
   * when `hasGroupRTE`, while `WHERE`-clause `Var`s use base relation varnos).
   */
  private fun isProvenByQuals(
    qualNotNullVars: Set<Pair<Int, Int>>,
    groupRteMap: Map<Pair<Int, Int>, Pair<Int, Int>>,
    varno: Int,
    varattno: Int,
  ): Boolean = qualNotNullVars.contains(varno to varattno) ||
    groupRteMap[varno to varattno]?.let { qualNotNullVars.contains(it) } == true

  /**
   * Builds a map from `(varno, varattno)` to `true` for CTE result columns that are guaranteed
   * non-null. Analyzes each CTE body with a sub-analyzer using the CTE's own range table.
   *
   * @param applyQualNarrowing See [analyzeNodeTree]'s parameter of the same name. Threaded
   *   through unchanged to every CTE body analyzed here.
   */
  private fun buildCteColumnNotNull(
    nodeTree: String,
    applyQualNarrowing: Boolean = true,
    @Language("PostgreSQL") sql: String,
  ): Map<Pair<Int, Int>, Boolean> {
    val cteRteMap = nodeTreeParser.parseCteRangeTableEntries(nodeTree)
    if (cteRteMap.isEmpty()) return emptyMap()
    val resolvedCtes = resolveCteBodies(nodeTree, applyQualNarrowing, sql)
    if (resolvedCtes.isEmpty()) return emptyMap()

    return buildMap {
      for ((varno, cteName) in cteRteMap) {
        val nullabilities = resolvedCtes[cteName] ?: continue
        nullabilities.forEachIndexed { columnIndex, nullable ->
          put(varno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  /**
   * Analyzes every CTE declared in [nodeTree]'s OWN `:cteList` and returns each one's per-column
   * nullability, keyed by CTE name.
   *
   * Shared by [buildCteColumnNotNull] (resolving a CTE reference in [nodeTree]'s OWN `:rtable`)
   * and [buildSubqueryColumnNotNull] (resolving a CTE reference — `:ctelevelsup 1` — one level
   * DOWN, inside a nested subquery's OWN `:rtable`): a CTE's declaration scope is [nodeTree]'s
   * level regardless of which nesting level actually references it, so both callers resolve
   * against the SAME set of CTE bodies.
   */
  private fun resolveCteBodies(
    nodeTree: String,
    applyQualNarrowing: Boolean,
    @Language("PostgreSQL") sql: String,
  ): Map<String, List<Boolean>> {
    val cteDefinitions = nodeTreeParser.parseCteList(nodeTree)
    if (cteDefinitions.isEmpty()) return emptyMap()
    val resolvedCtes = mutableMapOf<String, List<Boolean>>()
    for (cte in cteDefinitions) {
      val nullabilities = analyzeCteBodyNullability(cte, resolvedCtes, applyQualNarrowing, sql) ?: continue
      resolvedCtes[cte.name] = nullabilities
    }
    return resolvedCtes
  }

  /**
   * @param sql See [mergeAbsentVarnos]'s parameter of the same name — passed through unchanged so
   *   a MERGE nested in [cte]'s own body can be resolved by the SAME EXPLAIN call this parameter
   *   documents, keyed by ITS OWN target/source relation names.
   */
  private fun analyzeCteBodyNullability(
    cte: NodeTreeCteDefinition,
    previouslyResolved: Map<String, List<Boolean>>,
    applyQualNarrowing: Boolean = true,
    @Language("PostgreSQL") sql: String,
  ): List<Boolean>? {
    if (nodeTreeParser.hasSetOperations(cte.queryBlock)) {
      return analyzeSetOperationBranches(cte.queryBlock, previouslyResolved, cte.name, applyQualNarrowing)
    }
    val cteRangeTable = nodeTreeParser.parseRangeTable(cte.queryBlock)
    val mergeAbsent = mergeAbsentVarnos(cte.queryBlock, cteRangeTable, sql) ?: return null
    val analyzer = buildCteBodyAnalyzer(cte.queryBlock, previouslyResolved, applyQualNarrowing, mergeAbsent, sql)
    // :returningList must be checked FIRST, not as a fallback for an empty :targetList — see
    // analyzeNodeTree's identical guard for the full reasoning (an INSERT/UPDATE's OWN :targetList
    // holds the value expressions being WRITTEN, a completely different list from its RETURNING
    // projection, and is very often non-empty even when :returningList is what must be read). A
    // data-modifying CTE body reaches this method with its RAW, un-rewritten :returningList only
    // via [queryColumnNullabilityViaProsqlbody] — `prosqlbody` is the only mechanism that ever
    // populates a node tree for a data-modifying CTE in the first place (see that function's
    // KDoc), so there is no other caller shape this ordering needs to account for.
    val returningEntries = nodeTreeParser.parseReturningList(cte.queryBlock)
    if (returningEntries.isNotEmpty()) {
      return returningEntries
        .filter { !it.isJunk }
        .sortedBy { it.resultNumber }
        .map { entry -> !analyzer.isNonNull(entry.expression) }
    }
    val result = analyzer.extractColumnNullability(cte.queryBlock)
    return result.ifEmpty { null }
  }

  /**
   * Analyzes a `UNION`/`UNION ALL`/`INTERSECT`/`EXCEPT` [queryBlock] branch-by-branch and combines
   * each branch's per-column nullability with OR: a column is nullable in the combined result if
   * ANY branch can produce `null` for it.
   *
   * A `WITH RECURSIVE` CTE's recursive term(s) reference the CTE by name ([cteName]) — resolved by
   * [buildCteBodyAnalyzer] via `previouslyResolved` — creating a genuine fixpoint problem: the
   * recursive term's own nullability depends on the CTE's OWN combined nullability, which this
   * function is what computes. PostgreSQL requires the FIRST branch (the seed/non-recursive term)
   * to never reference the CTE itself, so it alone is computed once, outside the loop, as a known
   * starting point. Every subsequent branch (there is always exactly one recursive term for a
   * `WITH RECURSIVE` CTE, but this handles a plain multi-branch `UNION` identically) is then
   * re-analyzed, feeding back the CURRENT combined result as [cteName]'s own nullability, and the
   * combined result is recomputed — repeated until a pass changes nothing.
   *
   * This converges because the per-column nullability lattice (`false` = NOT NULL, `true` =
   * nullable, ordered `false < true`) is monotone under this loop's own update rule: OR-combining
   * MORE branch results (now including a possibly-wider self-reference) can only ever ADD `true`
   * bits, never remove one. Starting from the seed's own (fixed, correct) nullability — the
   * narrowest value the CTE's self-reference could possibly have — and iterating a
   * monotone-widening step over a `columnCount`-bit lattice reaches its fixpoint in at most
   * `columnCount + 1` passes (each pass either flips at least one more bit `false → true`, or
   * changes nothing and the loop stops), which the `while` condition below checks directly rather
   * than trusting an iteration-count bound alone.
   *
   * @return `null` if [queryBlock] has no analyzable subquery branches at all, or if the seed
   *   branch itself could not be analyzed (an empty result — see [extractColumnNullability]'s
   *   contract). Otherwise, one nullability value per output column, in `SELECT` order.
   */
  private fun analyzeSetOperationBranches(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
    cteName: String,
    applyQualNarrowing: Boolean = true,
  ): List<Boolean>? {
    val subqueryBranches = nodeTreeParser.parseSubqueryRangeTable(queryBlock).values.toList()
    if (subqueryBranches.isEmpty()) return null

    // The seed (first) branch of a recursive CTE structurally cannot reference the CTE itself —
    // PostgreSQL rejects a self-reference in the non-recursive term — so its nullability never
    // depends on the fixpoint loop below and is computed exactly once.
    val seedBlock = subqueryBranches.first()
    val seedAnalyzer = buildCteBodyAnalyzer(seedBlock, previouslyResolved, applyQualNarrowing)
    val seedResult = seedAnalyzer.extractColumnNullability(seedBlock)
    if (seedResult.isEmpty()) return null

    val otherBranches = subqueryBranches.drop(1)
    if (otherBranches.isEmpty()) return seedResult

    // Bounded at columnCount + 1 passes — the maximum this monotone-widening loop can take to
    // reach its fixpoint (see this function's own KDoc for the termination argument) — rather
    // than trusting that argument alone to keep this loop from ever running forever. columnCount
    // itself is seedResult.size, since every branch of a UNION/set operation is required (by
    // PostgreSQL) to have the same column count as every other branch, and the seed's is already
    // known at this point.
    var combined = seedResult
    var previous: List<Boolean>?
    var remainingPasses = seedResult.size + 1
    var anyBranchUnanalyzable = false
    do {
      previous = combined
      val resolvedWithSelfReference = previouslyResolved + (cteName to combined)
      val branchResults = mutableListOf(seedResult)
      for (branchBlock in otherBranches) {
        val branchAnalyzer = buildCteBodyAnalyzer(branchBlock, resolvedWithSelfReference, applyQualNarrowing)
        val result = branchAnalyzer.extractColumnNullability(branchBlock)
        // An empty result means this branch's own nullability could not be determined at all — not
        // "this branch has zero columns" (impossible; every branch of a set operation has the same
        // column count). Silently dropping it from the OR-combination would let the OTHER
        // branches' (possibly narrower, even all-NOT-NULL) answer stand as if this branch
        // contributed nothing, when in truth its contribution is simply unknown. Flagging it here
        // forces every column nullable below instead.
        if (result.isNotEmpty()) branchResults.add(result) else anyBranchUnanalyzable = true
      }
      val columnCount = branchResults.maxOf { it.size }
      combined = (0 until columnCount).map { col -> branchResults.any { it.getOrElse(col) { true } } }
      remainingPasses--
    } while (combined != previous && remainingPasses > 0)
    // combined != previous here means the loop was cut off by remainingPasses running out while
    // the result was still changing — i.e. it did not reach its fixpoint. Returning that
    // still-moving intermediate value could under-report nullability (a later pass might still
    // flip more columns to nullable), so every column is forced nullable instead, the same
    // fallback taken for an unanalyzable branch above.
    val didNotConverge = combined != previous
    return if (anyBranchUnanalyzable || didNotConverge) List(combined.size) { true } else combined
  }

  /**
   * Builds a map from `(varno, varattno)` to `true` for CTE RTE columns that are non-null,
   * based on previously resolved CTE nullabilities.
   */
  private fun buildInnerCteNotNull(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
  ): Map<Pair<Int, Int>, Boolean> {
    val innerCteRtes = nodeTreeParser.parseCteRangeTableEntries(queryBlock)
    return buildMap {
      for ((varno, cteName) in innerCteRtes) {
        val nullabilities = previouslyResolved[cteName] ?: continue
        nullabilities.forEachIndexed { columnIndex, nullable ->
          put(varno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  /**
   * @param applyQualNarrowing See [analyzeNodeTree]'s parameter of the same name.
   * @param mergeAbsentVarnos See [analyzeNodeTree]'s parameter of the same name — [queryBlock]'s
   *   OWN varno-to-canBeAbsent map when [queryBlock] itself is a `MERGE` (resolved by
   *   [analyzeCteBodyNullability] before ever calling this method), empty otherwise.
   * @param sql See [mergeAbsentVarnos]'s (the method, not this parameter) `sql` parameter — passed
   *   through only so a subquery WITHIN [queryBlock] that references a CTE declared in
   *   [queryBlock]'s OWN nested `WITH` clause can resolve THAT (deeper) CTE's `MERGE`, if it has
   *   one, through [buildSubqueryColumnNotNull]. Defaults to an empty string for the (`SELECT`-only,
   *   never `MERGE`-shaped) set-operation branch callers in [analyzeSetOperationBranches], where an
   *   empty `EXPLAIN` target simply fails harmlessly (caught, treated as "cannot resolve") for the
   *   narrow, deeper case of a subquery nested that deep referencing ITS OWN local `MERGE` CTE.
   */
  private fun buildCteBodyAnalyzer(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
    applyQualNarrowing: Boolean = true,
    mergeAbsentVarnos: Map<Int, Boolean> = emptyMap(),
    @Language("PostgreSQL") sql: String = "",
  ): NodeTreeNullabilityAnalyzer {
    val cteRangeTable = nodeTreeParser.parseRangeTable(queryBlock)
    // See analyzeNodeTree's identical guard: GROUPING SETS/CUBE/ROLLUP can null-extend a
    // grouping key even when the underlying base table column is NOT NULL, and even when the
    // WHERE clause proved it non-null before aggregation. The actual override (including
    // EXPRESSION grouping keys) is applied by extractColumnNullability via hasGroupingSets below.
    val hasGroupingSets = nodeTreeParser.hasGroupingSets(queryBlock)
    val groupRteMap = if (hasGroupingSets) {
      emptyMap()
    } else {
      nodeTreeParser.parseGroupRteMap(queryBlock)
    }
    val innerCteNotNull = buildInnerCteNotNull(queryBlock, previouslyResolved)
    val subqueryColumnNotNull = buildSubqueryColumnNotNull(queryBlock, applyQualNarrowing, sql)
    // See analyzeNodeTree's identical guard for why qual narrowing is suppressed whenever
    // hasGroupingSets: a grouping key is exactly the thing a GROUPING SETS/CUBE/ROLLUP query
    // null-extends after WHERE has already run. A non-zero :resultRelation suppresses narrowing
    // for the identical reason analyzeNodeTree's own guard does: a data-modifying CTE body's WHERE
    // clause can prove something about a column its OWN SET clause is about to overwrite — see
    // e.g. `WITH c AS (UPDATE t SET a = NULL FROM u WHERE u.id = t.id AND t.a IS NOT NULL
    // RETURNING t.a) SELECT a FROM c`, verified against real Postgres to return `a = NULL`, not the
    // value the WHERE clause proved before the SET ran.
    val isDml = nodeTreeParser.parseResultRelation(queryBlock) != 0
    val qualNotNullVars = if (applyQualNarrowing && !hasGroupingSets && !isDml) {
      NodeTreeNullabilityAnalyzer.qualProvenNonNullVars(queryBlock, isStrictFunction)
    } else {
      emptySet()
    }
    return buildAnalyzer(
      hasGroupingSets = hasGroupingSets,
      forceNewNullable = forcesNewNullable(queryBlock),
      applyQualNarrowing = applyQualNarrowing,
    ) {
        varno,
        varattno,
      ->
      if (mergeAbsentVarnos[varno] == true) {
        // See analyzeNodeTree's identical guard: a MERGE relation EXPLAIN determined can be
        // entirely absent for some result row can never be proven non-null here.
        false
      } else if (isProvenByQuals(qualNotNullVars, groupRteMap, varno, varattno)) {
        true
      } else {
        val relid = cteRangeTable[varno]
        if (relid != null) {
          isColumnNotNull(relid to varattno)
        } else {
          val baseVar = groupRteMap[varno to varattno]
          if (baseVar != null) {
            val baseRelid = cteRangeTable[baseVar.first]
            baseRelid != null && isColumnNotNull(baseRelid to baseVar.second)
          } else {
            subqueryColumnNotNull[varno to varattno] == true ||
              innerCteNotNull[varno to varattno] == true
          }
        }
      }
    }
  }

  /**
   * Builds a map from `(varno, varattno)` to `true` for columns of subquery RTEs that are
   * guaranteed non-null.
   *
   * For each `rtekind 1` (subquery) entry in the outer query's `:rtable`, extracts the embedded
   * `:subquery {QUERY ...}` block, recursively analyzes it with a fresh [NodeTreeNullabilityAnalyzer]
   * using the **subquery's own range table**, and maps each output column position to its nullability.
   *
   * This allows the outer analyzer's [NodeTreeNullabilityAnalyzer] to correctly evaluate VARs that
   * reference a subquery derived table (`SELECT s.col FROM (...) s`) rather than a base table.
   * Without this, `isSourceColumnNotNull` for a subquery VAR would always return `false` (nullable)
   * because the subquery RTE has no `relid` in the outer range table.
   *
   * @param nodeTree the `pg_rewrite.ev_action` text of the outer query's temporary view
   * @param applyQualNarrowing See [analyzeNodeTree]'s parameter of the same name.
   * @return A map from `(varno, varattno)` pairs to `true` when the subquery column is non-null
   */
  private fun buildSubqueryColumnNotNull(
    nodeTree: String,
    applyQualNarrowing: Boolean = true,
    @Language("PostgreSQL") sql: String = "",
  ): Map<Pair<Int, Int>, Boolean> {
    // Set-operation queries (UNION ALL, INTERSECT, EXCEPT) store their branches as rtekind=1
    // subquery RTEs. Tracing through them would incorrectly report the first branch's nullability
    // as the result's nullability — the true result is the union across ALL branches, some of which
    // may introduce nulls (e.g., a branch with LEFT JOIN). Return empty so the analyzer conservatively
    // treats set-operation output columns as nullable (the correct safe default).
    if (nodeTreeParser.hasSetOperations(nodeTree)) return emptyMap()
    val subqueryRangeTable = nodeTreeParser.parseSubqueryRangeTable(nodeTree)
    if (subqueryRangeTable.isEmpty()) return emptyMap()
    // A subquery nested inside a DML statement's own :targetList (e.g. `INSERT INTO t(name)
    // SELECT name FROM source RETURNING *`, where "source" is a sibling CTE) can reference a CTE
    // declared at NODETREE'S level via `:ctelevelsup 1` — one level UP from the subquery's own
    // scope — inside its OWN `:rtable`, not [nodeTree]'s. Resolved once here, lazily, so a
    // [nodeTree] with no CTEs at all (the overwhelmingly common case) never pays for
    // [resolveCteBodies]'s recursive analysis.
    val resolvedCtes by lazy { resolveCteBodies(nodeTree, applyQualNarrowing, sql) }
    return buildMap {
      for ((outerVarno, subqueryBlock) in subqueryRangeTable) {
        val subNullabilities = analyzeQueryBlockNullability(subqueryBlock, applyQualNarrowing, resolvedCtes)
        subNullabilities.forEachIndexed { columnIndex, nullable ->
          // columnIndex is 0-based; varattno is 1-based
          put(outerVarno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  /**
   * Computes per-column nullability for a single query block ([queryBlock]) — the shared core of
   * [buildSubqueryColumnNotNull] (a `FROM`-clause subquery RTE) and [subLinkSubqueryColumnNotNull]
   * (an `ANY_SUBLINK`'s `:subselect`): given a raw `{QUERY ...}` block, build a resolver over the
   * block's OWN `parseRangeTable`/`parseCteRangeTableEntries`/`parseGroupRteMap` and qual
   * narrowing, then run [NodeTreeNullabilityAnalyzer.extractColumnNullability] against it.
   *
   * @param resolvedCtes CTE bodies [queryBlock] may reference via `:ctelevelsup 1` (one level UP
   *   from [queryBlock]'s own scope) — see [buildSubqueryColumnNotNull]'s own KDoc for why this
   *   only ever resolves ONE level up, never [queryBlock]'s own nested `WITH` clause. Passed as
   *   `emptyMap()` by [subLinkSubqueryColumnNotNull], which deliberately does not attempt this
   *   resolution — see that method's KDoc.
   * @param depth See [buildAnalyzer]'s `depth` parameter of the same name — passed through
   *   unchanged so a `SubLink` inside [queryBlock] gets the correct, already-decremented budget.
   */
  private fun analyzeQueryBlockNullability(
    queryBlock: String,
    applyQualNarrowing: Boolean,
    resolvedCtes: Map<String, List<Boolean>>,
    depth: Int = SUBLINK_ANALYSIS_DEPTH_BUDGET,
  ): List<Boolean> {
    // Parse the block's own base-table range table for isSourceColumnNotNull.
    val subRangeTable = nodeTreeParser.parseRangeTable(queryBlock)
    val subCteRteMap = nodeTreeParser.parseCteRangeTableEntries(queryBlock)
    // See analyzeNodeTree's identical guard: GROUPING SETS/CUBE/ROLLUP can
    // null-extend a grouping key even when the base table column is NOT NULL. The actual
    // override (including EXPRESSION grouping keys) is applied by extractColumnNullability
    // via hasGroupingSets below.
    val hasGroupingSets = nodeTreeParser.hasGroupingSets(queryBlock)
    val groupRteMap = if (hasGroupingSets) {
      emptyMap()
    } else {
      nodeTreeParser.parseGroupRteMap(queryBlock)
    }
    // See analyzeNodeTree's identical guard: suppress qual narrowing whenever
    // hasGroupingSets, because those are exactly what GROUPING SETS/CUBE/ROLLUP null-extends
    // after WHERE has already filtered rows.
    val subQualNotNullVars = if (applyQualNarrowing && !hasGroupingSets) {
      NodeTreeNullabilityAnalyzer.qualProvenNonNullVars(queryBlock, isStrictFunction)
    } else {
      emptySet()
    }
    val subAnalyzer = buildAnalyzer(
      hasGroupingSets = hasGroupingSets,
      applyQualNarrowing = applyQualNarrowing,
      depth = depth,
    ) { subVarno, subVarattno ->
      if (isProvenByQuals(subQualNotNullVars, groupRteMap, subVarno, subVarattno)) {
        true
      } else {
        val relid = subRangeTable[subVarno]
        if (relid != null) {
          isColumnNotNull(relid to subVarattno)
        } else {
          val cteName = subCteRteMap[subVarno]
          cteName != null && resolvedCtes[cteName]?.getOrNull(subVarattno - 1) == false
        }
      }
    }
    return subAnalyzer.extractColumnNullability(queryBlock)
  }

  /**
   * Answers, for [analyzeNodeTree]'s own `:targetList`-to-`:returningList` substitution, whether a
   * `RETURNING` item that merely reads back a `:targetList` assignment can be trusted as what
   * `RETURNING` actually sees for [relid] — `false` whenever a row-level `BEFORE` trigger, a
   * rewrite rule, an `INSTEAD OF` trigger, or an FDW could substitute a different final value.
   *
   * @return `true` only when [relid] and every transitive inheritance/partition descendant is a
   *   plain table with no risky `relkind`, no mutating row-level trigger, and no non-view rewrite
   *   rule — `false` for every other case, INCLUDING the catalog query itself failing to execute
   *   (treated exactly like a confirmed risk: [analyzeNodeTree] must not trust the substitution
   *   when it cannot rule the risk out).
   */
  private fun isSubstitutionSafeForRelation(relid: Int): Boolean = try {
    connection.prepareStatement(
      """
      WITH RECURSIVE descendants(relid) AS (
        SELECT ?::integer
        UNION
        SELECT i.inhrelid::integer
        FROM pg_catalog.pg_inherits i
        JOIN descendants d ON i.inhparent = d.relid
      )
      SELECT
        EXISTS (
          SELECT 1
          FROM pg_catalog.pg_class c
          JOIN descendants d ON c.oid = d.relid
          WHERE c.relkind IN ('v', 'm', 'f')
        ) AS has_risky_relkind,
        EXISTS (
          SELECT 1
          FROM pg_catalog.pg_trigger tg
          JOIN descendants d ON tg.tgrelid = d.relid
          WHERE NOT tg.tgisinternal
            AND (tg.tgtype & 1) = 1
            AND (tg.tgtype & 2) = 2
            AND ((tg.tgtype & 4) = 4 OR (tg.tgtype & 16) = 16)
        ) AS has_mutating_row_trigger,
        EXISTS (
          SELECT 1
          FROM pg_catalog.pg_rewrite rw
          JOIN descendants d ON rw.ev_class = d.relid
          WHERE rw.rulename <> '_RETURN'
        ) AS has_non_view_rewrite_rule
      """.trimIndent(),
    ).use { preparedStatement ->
      preparedStatement.setInt(1, relid)
      preparedStatement.executeQuery().use { resultSet ->
        check(resultSet.next()) { "Expected exactly one row from the substitution-safety EXISTS query" }
        !resultSet.getBoolean("has_risky_relkind") &&
          !resultSet.getBoolean("has_mutating_row_trigger") &&
          !resultSet.getBoolean("has_non_view_rewrite_rule")
      }
    }
  } catch (_: SQLException) {
    false
  }

  /**
   * The bare (unqualified) table name for [relid], via `pg_class.relname` — used to attribute
   * an `EXPLAIN` plan's `"Relation Name"` fields (which are always the REAL table name, never an
   * alias) back to a specific `:rtable` entry this class already resolved structurally, without
   * ever re-parsing the SQL text for a table name or alias. See [mergeAbsentVarnos]'s only caller.
   *
   * @return `null` if [relid] cannot be resolved (should not happen for a real, structural
   *   `:rtable` entry, but treated the same as any other "cannot confirm" case: the caller must
   *   fall back to its own safe default rather than guess)
   */
  private fun resolveTableName(relid: Int): String? = try {
    connection.prepareStatement("SELECT relname FROM pg_catalog.pg_class WHERE oid = ?").use { preparedStatement ->
      preparedStatement.setInt(1, relid)
      preparedStatement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getString(1) else null }
    }
  } catch (_: SQLException) {
    null
  }
}
