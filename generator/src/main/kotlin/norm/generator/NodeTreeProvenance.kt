package norm.generator

/**
 * Recursion budget for [NodeTreeProvenanceResolver]'s chained-CTE follow.
 *
 * Not required to prevent a true infinite loop — PostgreSQL's grammar already forbids a CTE
 * referencing a later-declared one, so no reference cycle exists among the CTEs this resolver
 * enters. This is a defensive bound on stack/work for a pathologically long chain of CTEs, the same
 * role [ColumnNullabilityAnalyzer]'s `VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET` plays for view chains.
 */
private const val MAX_PROVENANCE_CHAIN_DEPTH = 50

/**
 * One `Var`-into-CTE-range-table-entry hop: the referenced CTE's [name] and how many query levels up
 * its declaring `:cteList` lives ([ctelevelsup]) relative to the block the reference was written in.
 *
 * [resolveNodeTreeProvenanceExpression] replays a [NodeTreeColumnProvenance.hops] list against the
 * user's own SQL text, so a nested `WITH` that shadows an outer CTE of the same name resolves to the
 * declaration actually used, not merely a same-named one elsewhere in the statement.
 */
internal data class CteHop(val name: String, val ctelevelsup: Int)

/**
 * Where a single output column's expression can be found, verbatim, in the user's original SQL
 * text: at 1-based position [bodyPosition] among the output columns (`:targetList`, or
 * `:returningList` for a data-modifying CTE) of the CTE [hops]'s last entry names.
 *
 * [hops] is the full chain of `Var`-into-CTE hops, not just the final entry, because a nested `WITH`
 * can shadow an outer CTE of the same name with a different body; only replaying the whole path
 * distinguishes which declaration was meant. Always non-empty.
 *
 * Not the expression text itself: resolution answers only "where"; extracting and cross-validating
 * the source text against the user's original SQL is a separate step.
 *
 * @property cteName The last hop's CTE name, for a caller that only needs "where the text lives".
 */
internal data class NodeTreeColumnProvenance(val hops: List<CteHop>, val bodyPosition: Int) {

  init {
    require(hops.isNotEmpty()) { "NodeTreeColumnProvenance requires at least one CTE hop" }
  }

  val cteName: String get() = hops.last().name

  /** Convenience constructor for a direct reference into a CTE declared at the same query level. */
  constructor(cteName: String, bodyPosition: Int) : this(listOf(CteHop(cteName, 0)), bodyPosition)
}

/**
 * Resolves each of a query's output columns to the CTE body position that expression was written
 * at, working entirely from the query's own parsed node tree.
 *
 * Returns `null` (no provenance) rather than guessing whenever the honest answer is unavailable:
 * - an outer-query reference (`:varlevelsup != 0`), whose `varno` addresses an enclosing query's
 *   range table, not this block's;
 * - a `USING`/`NATURAL`-merged join column, which PostgreSQL represents as `COALESCE(left, right)`
 *   with no single side to attribute to;
 * - a self-referencing or recursive CTE, whose `resno` position can be fed by a different
 *   expression on every iteration;
 * - a query (main or CTE body) with a top-level set operation, whose result depends on every branch;
 * - a range-table entry this resolver does not recognize as a CTE or a `JOIN`.
 *
 * @param parser the node-tree parser to use; defaults to a fresh, stateless instance
 */
internal class NodeTreeProvenanceResolver(private val parser: PgNodeTreeParser = PgNodeTreeParser()) {

  /**
   * Resolves every non-junk output column of [nodeTreeText], in `SELECT`/`RETURNING` order, to
   * either its [NodeTreeColumnProvenance] or `null` (no CTE provenance for that column).
   *
   * Reads `:returningList` first, falling back to `:targetList` — the same order
   * [ColumnNullabilityAnalyzer.analyzeNodeTree] uses, since a topmost `RETURNING` populates both and
   * `:targetList` there holds the values being written, not the columns being returned.
   *
   * @return one entry per non-junk output column, in position order; every entry is `null` when
   *   [nodeTreeText]'s outermost statement has a top-level set operation.
   */
  fun resolveColumnProvenance(nodeTreeText: String): List<NodeTreeColumnProvenance?> {
    val entries = outputEntries(nodeTreeText).filter { !it.isJunk }.sortedBy { it.resultNumber }
    if (entries.isEmpty()) return emptyList()
    if (parser.hasSetOperations(nodeTreeText)) return entries.map { null }
    // A single-element stack: nodeTreeText's own :cteList is the only scope until the walk
    // descends into a CTE body — see resolveVar for why the stack grows from there.
    val outermostScope = listOf(parser.parseCteList(nodeTreeText).associateBy { it.name })
    return entries.map { entry -> resolveVar(entry.expression, nodeTreeText, outermostScope) }
  }

  /** `:returningList` when non-empty, else `:targetList` — see [resolveColumnProvenance]. */
  private fun outputEntries(queryBlock: String): List<TargetEntry> {
    val returningEntries = parser.parseReturningList(queryBlock)
    return returningEntries.ifEmpty { parser.parseTargetList(queryBlock) }
  }

  /**
   * Resolves a single output column's [expression] to a [NodeTreeColumnProvenance].
   *
   * [expression] must be a bare [PgNodeExpression.Var]; any other shape is a real computed
   * expression with no CTE involved, so this returns `null` immediately.
   *
   * The walk stays within [queryBlock] until a hop crosses into a CTE's own body, at which point
   * [queryBlock] is updated to that body: a `Var` into a `JOIN` range-table entry follows its
   * `:joinaliasvars` (bailing if that entry is not itself a bare `Var`); a `Var` into a CTE
   * range-table entry records that CTE's name and attribute number as the current best answer, then
   * looks up the CTE body's target entry at that position. If that entry is also a bare `Var`, the
   * walk continues into whatever it references, updating the best answer only when that is another
   * CTE reference. Landing on anything else stops the walk and returns the current best answer.
   *
   * [scopeStack] tracks lexical `WITH`-clause nesting: index `0` is [queryBlock]'s own `:cteList`,
   * index `1` the block one level up that declared it, and so on. Scope belongs to a CTE's
   * declaration site, not to the hop path taken to reach it — hopping into a sibling CTE declared in
   * the same `:cteList` is not a nesting level, matching PostgreSQL's own `:ctelevelsup` for that
   * reference. So resolving a reference against `scopeStack[reference.ctelevelsup]` and then
   * entering that CTE's body rebuilds the stack as `scopeStack.drop(reference.ctelevelsup)` with the
   * body's own `:cteList` pushed on front, rather than prepending onto the full accumulated
   * [scopeStack]; otherwise stale frames attribute a chained reference to the wrong same-named CTE,
   * and a chain of three or more sibling CTEs resolves to nothing.
   *
   * A `:ctelevelsup` deeper than [scopeStack] bails rather than reading past what has been tracked —
   * a real reference's levelsup can never exceed the number of `WITH` clauses actually enclosing it.
   */
  private fun resolveVar(
    expression: PgNodeExpression,
    queryBlock: String,
    scopeStack: List<Map<String, NodeTreeCteDefinition>>,
  ): NodeTreeColumnProvenance? {
    var currentQueryBlock = queryBlock
    var currentScopeStack = scopeStack
    var currentVar = expression as? PgNodeExpression.Var ?: return null
    if (currentVar.levelsUp != 0) return null
    val hops = mutableListOf<CteHop>()
    var resolvedPosition: Int? = null
    var depth = 0
    while (true) {
      if (depth++ >= MAX_PROVENANCE_CHAIN_DEPTH) return null
      when (val rangeTableEntry = parser.parseRangeTableEntries(currentQueryBlock)[currentVar.varno] ?: return null) {
        is RangeTableEntry.Join -> {
          val aliasVar = rangeTableEntry.joinAliasVars.getOrNull(currentVar.varattno - 1) as? PgNodeExpression.Var
            ?: return null
          if (aliasVar.levelsUp != 0) return null
          currentVar = aliasVar
        }
        is RangeTableEntry.Cte -> {
          val reference = rangeTableEntry.reference
          if (reference.selfReference) return null
          val scope = currentScopeStack.getOrNull(reference.ctelevelsup) ?: return null
          val definition = scope[reference.name] ?: return null
          if (definition.recursive) return null
          if (parser.hasSetOperations(definition.queryBlock)) return null
          hops.add(CteHop(reference.name, reference.ctelevelsup))
          resolvedPosition = currentVar.varattno
          val bodyEntry = outputEntries(definition.queryBlock).find { it.resultNumber == currentVar.varattno }
            ?: return null
          if (bodyEntry.isJunk) return null
          val bodyVar = bodyEntry.expression as? PgNodeExpression.Var
          if (bodyVar == null || bodyVar.levelsUp != 0) {
            return NodeTreeColumnProvenance(hops, resolvedPosition)
          }
          currentQueryBlock = definition.queryBlock
          val ownScope = parser.parseCteList(definition.queryBlock).associateBy { it.name }
          // drop, not prepend-onto-the-full-stack: see this method's KDoc on why scope belongs to
          // the declaration site (reference.ctelevelsup levels up from here), never the hop path.
          currentScopeStack = listOf(ownScope) + currentScopeStack.drop(reference.ctelevelsup)
          currentVar = bodyVar
        }
        else ->
          return if (hops.isEmpty()) null else NodeTreeColumnProvenance(hops, resolvedPosition!!)
      }
    }
  }
}
