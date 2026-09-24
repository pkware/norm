package norm.generator

/**
 * Recursion budget for [NodeTreeProvenanceResolver]'s chained-CTE follow.
 *
 * Not required to prevent a true infinite loop — PostgreSQL's grammar already forbids a CTE
 * referencing a later-declared one, so no reference cycle exists among the CTEs this resolver
 * enters. This is a defensive bound on stack/work for a pathologically long chain of CTEs.
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
 * Lexical `WITH`-clause nesting for a CTE-provenance walk: index `0` is the current query block's own
 * scope, index `1` the block one level up that declared it, and so on. [Frame] is whatever a resolver
 * uses to look up a CTE's declaration within one scope — a `Map<String, NodeTreeCteDefinition>` for the
 * node-tree side, a `List<CteDefinition>` for the SQL-text side.
 *
 * A CTE's scope belongs to its declaration site, not to the hop path taken to reach it: entering a
 * CTE's body rebuilds the stack as [frames]`.drop(levelsUp)` with the body's own scope pushed on
 * front ([entering]), rather than prepending onto the full accumulated stack. Prepending onto the full
 * stack instead would attribute a chained reference to the wrong same-named CTE for a chain of three or
 * more sibling CTEs.
 *
 * A `:ctelevelsup` deeper than the tracked frames bails ([frameAt] returning `null`) rather than
 * reading past what has been tracked — a real reference's levelsup can never exceed the number of
 * `WITH` clauses actually enclosing it.
 */
internal class CteScopeStack<Frame> private constructor(private val frames: List<Frame>) {
  constructor(outermost: Frame) : this(listOf(outermost))

  /** The frame [levelsUp] scopes up from the current one, or `null` if `levelsUp` is out of range. */
  fun frameAt(levelsUp: Int): Frame? = frames.getOrNull(levelsUp)

  /**
   * The scope stack after entering a CTE body declared [levelsUp] scopes up, whose own scope is
   * [ownScope].
   *
   * @throws IllegalArgumentException if `levelsUp` is not a currently tracked frame.
   */
  fun entering(levelsUp: Int, ownScope: Frame): CteScopeStack<Frame> {
    require(levelsUp in frames.indices) { "levelsUp $levelsUp outside ${frames.size} tracked scopes" }
    return CteScopeStack(listOf(ownScope) + frames.drop(levelsUp))
  }
}

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
   * See [PgNodeTreeParser.resultProjection] for which list this reads.
   *
   * @return one entry per non-junk output column, in position order; every entry is `null` when
   *   [nodeTreeText]'s outermost statement has a top-level set operation.
   */
  fun resolveColumnProvenance(nodeTreeText: String): List<NodeTreeColumnProvenance?> {
    val entries = parser.resultProjection(nodeTreeText).entries
    if (entries.isEmpty()) return emptyList()
    if (parser.hasSetOperations(nodeTreeText)) return entries.map { null }
    // A single-frame stack: nodeTreeText's own :cteList is the only scope until the walk
    // descends into a CTE body.
    val outermostScope = CteScopeStack(parser.parseCteList(nodeTreeText).associateBy { it.name })
    // Every column's walk starts from nodeTreeText's own range table and re-enters the same CTE
    // bodies, so parsing each block once here is what keeps the cost proportional to the number of
    // distinct blocks rather than to columns times hops. Local to this call: nothing outlives it.
    val rangeTables = mutableMapOf<String, Map<Int, RangeTableEntry>>()
    return entries.map { entry -> resolveVar(entry.expression, nodeTreeText, outermostScope, rangeTables) }
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
   * [scopeStack] tracks lexical `WITH`-clause nesting; see [CteScopeStack] for the invariant it
   * maintains as the walk enters each CTE's body.
   *
   * [rangeTables] holds each already-parsed query block's range table, keyed by that block's text.
   * The caller owns it and shares one instance across the columns of a single
   * [resolveColumnProvenance] call; entries are read, never modified, so a block reached by more
   * than one column is parsed once.
   */
  private fun resolveVar(
    expression: PgNodeExpression,
    queryBlock: String,
    scopeStack: CteScopeStack<Map<String, NodeTreeCteDefinition>>,
    rangeTables: MutableMap<String, Map<Int, RangeTableEntry>>,
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
      val rangeTable = rangeTables.getOrPut(currentQueryBlock) { parser.parseRangeTableEntries(currentQueryBlock) }
      when (val rangeTableEntry = rangeTable[currentVar.varno] ?: return null) {
        is RangeTableEntry.Join -> {
          val aliasVar = rangeTableEntry.joinAliasVars.getOrNull(currentVar.varattno - 1) as? PgNodeExpression.Var
            ?: return null
          if (aliasVar.levelsUp != 0) return null
          currentVar = aliasVar
        }
        is RangeTableEntry.Cte -> {
          val reference = rangeTableEntry.reference
          if (reference.selfReference) return null
          val scope = currentScopeStack.frameAt(reference.ctelevelsup) ?: return null
          val definition = scope[reference.name] ?: return null
          if (definition.recursive) return null
          if (parser.hasSetOperations(definition.queryBlock)) return null
          hops.add(CteHop(reference.name, reference.ctelevelsup))
          resolvedPosition = currentVar.varattno
          val bodyEntry = parser.resultProjection(definition.queryBlock).entries
            .find { it.resultNumber == currentVar.varattno }
            ?: return null
          val bodyVar = bodyEntry.expression as? PgNodeExpression.Var
          if (bodyVar == null || bodyVar.levelsUp != 0) {
            return NodeTreeColumnProvenance(hops, resolvedPosition)
          }
          currentQueryBlock = definition.queryBlock
          val ownScope = parser.parseCteList(definition.queryBlock).associateBy { it.name }
          currentScopeStack = currentScopeStack.entering(reference.ctelevelsup, ownScope)
          currentVar = bodyVar
        }
        else ->
          return if (hops.isEmpty()) null else NodeTreeColumnProvenance(hops, resolvedPosition!!)
      }
    }
  }
}
