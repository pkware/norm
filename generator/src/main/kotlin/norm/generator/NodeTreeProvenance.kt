package norm.generator

/**
 * Recursion budget for [NodeTreeProvenanceResolver]'s chained-CTE follow: how many `Var`/`RANGETBLENTRY`
 * hops (crossing into another CTE's own body, or across a `JOIN`'s `:joinaliasvars`) are followed
 * before giving up and returning no resolution.
 *
 * Not required to prevent a true infinite loop — a plain (non-recursive, non-self-referencing) CTE
 * can only reference an EARLIER-declared CTE in the same `WITH` clause, so PostgreSQL's own grammar
 * already forbids a genuine reference cycle among the CTEs this resolver is willing to enter (a
 * self-referencing or recursive CTE is refused outright, see [NodeTreeProvenanceResolver]'s own
 * KDoc). This budget is a defensive bound on stack/work for a pathologically long, if legal, chain
 * of CTEs each merely selecting from the previous one — the same defensive role
 * [ColumnNullabilityAnalyzer]'s `VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET` plays for view chains.
 */
private const val MAX_PROVENANCE_CHAIN_DEPTH = 50

/**
 * One `Var`-into-CTE-range-table-entry hop [NodeTreeProvenanceResolver] followed while chasing a
 * column's provenance, in the SAME shape [NodeTreeCteReference] carries: the referenced CTE's
 * [name] and how many query levels up its declaring `:cteList` lives ([ctelevelsup]) relative to
 * the query block the reference was written in — `0` for that block's OWN `WITH` clause, more for
 * an enclosing one.
 *
 * [resolveNodeTreeProvenanceExpression] replays a [NodeTreeColumnProvenance.hops] list step by
 * step against the user's own SQL TEXT, maintaining an analogous stack of lexically-nested `WITH`
 * clauses, to find the EXACT declaration [NodeTreeProvenanceResolver] resolved against — not
 * merely a same-named one anywhere in the statement. This is what lets a nested `WITH` that
 * shadows an outer CTE of the same name resolve correctly instead of bailing on the mere
 * existence of a duplicate name (#238).
 */
internal data class CteHop(val name: String, val ctelevelsup: Int)

/**
 * Where a single output column's expression can be found, verbatim, in the user's ORIGINAL SQL
 * text: at 1-based position [bodyPosition] among the OUTPUT columns (`:targetList`, or — for a
 * data-modifying CTE — `:returningList`) of the CTE [hops]' LAST entry names.
 *
 * [hops] is the FULL sequence of `Var`-into-CTE hops [NodeTreeProvenanceResolver] followed to
 * reach that CTE — never just its final entry — because a nested `WITH` can shadow an outer CTE of
 * the SAME name with a DIFFERENT body; only replaying the WHOLE path (each hop's name AND
 * [CteHop.ctelevelsup]) against the user's SQL text can tell which declaration was actually meant,
 * rather than merely which name. Always non-empty: [NodeTreeProvenanceResolver] never returns a
 * [NodeTreeColumnProvenance] without having followed at least one hop.
 *
 * This is deliberately NOT the expression text itself: [NodeTreeProvenanceResolver] answers only
 * "where", working purely from the parsed node tree. Extracting and cross-validating the actual
 * source text at this position against the user's original SQL is a separate, later step.
 *
 * @property cteName The LAST hop's CTE name — the one [bodyPosition] indexes into. Convenience for
 *   a caller that only cares "where does the text live", never used by
 *   [resolveNodeTreeProvenanceExpression] itself for scope resolution (see [hops] for why the
 *   name alone is not enough to disambiguate a shadowed one).
 */
internal data class NodeTreeColumnProvenance(val hops: List<CteHop>, val bodyPosition: Int) {

  init {
    require(hops.isNotEmpty()) { "NodeTreeColumnProvenance requires at least one CTE hop" }
  }

  val cteName: String get() = hops.last().name

  /**
   * Convenience constructor for the common single-hop case (a direct reference into a CTE
   * declared at the SAME query level the reference itself is written in, never nested through a
   * shadowing `WITH`) — equivalent to `NodeTreeColumnProvenance(listOf(CteHop(cteName, 0)),
   * bodyPosition)`. Every reference [NodeTreeProvenanceResolver] resolves whose FIRST hop is also
   * its LAST has `ctelevelsup 0` for that hop by construction: the very first hop is always
   * resolved against [NodeTreeProvenanceResolver.resolveColumnProvenance]'s own single-level
   * `outermostScope`, so any `ctelevelsup` other than `0` there has no scope to address and
   * already bails before a [NodeTreeColumnProvenance] is ever constructed.
   */
  constructor(cteName: String, bodyPosition: Int) : this(listOf(CteHop(cteName, 0)), bodyPosition)
}

/**
 * Resolves each of a query's output columns to the CTE body position that expression was written
 * at — see [NodeTreeColumnProvenance] — working entirely from the query's own parsed node tree
 * (`pg_rewrite.ev_action` or `pg_proc.prosqlbody` text; see `ColumnNullabilityAnalyzer`'s KDoc for
 * why both share the same post-parse-analysis `{QUERY ...}` shape).
 *
 * This resolver embodies the plan's "correct or silent" invariant: every gate below returns `null`
 * (no provenance) rather than guessing. In particular, it NEVER attributes a result column to:
 * - an outer-query reference (`:varlevelsup != 0`) — that Var's `varno` refers to an ENCLOSING
 *   query's own range table, not this block's, and interpreting it against the wrong block would
 *   silently resolve to an unrelated CTE or column;
 * - a `USING`/`NATURAL`-merged join column under an outer join — PostgreSQL represents that column
 *   as `COALESCE(left, right)`, whose honest provenance is not "one side's expression"; attributing
 *   it to either side would be a confidently wrong answer, not merely an incomplete one;
 * - a self-referencing (`:self_reference true`) or recursive (`:cterecursive true`) CTE — a
 *   recursive term's `resno` position can be fed by a DIFFERENT expression on every iteration, so no
 *   single body position honestly describes the CTE's whole output;
 * - a query (main or CTE body) with a top-level set operation (`UNION`/`INTERSECT`/`EXCEPT`) — its
 *   result depends on EVERY branch, not just the one branch a positional target-list read would see;
 * - any range-table entry this resolver does not specifically recognize as a CTE or a `JOIN` (a base
 *   table, a derived table, a function/`VALUES`/tablefunc RTE, etc.) — there is no CTE to attribute
 *   to, so the chase simply stops there.
 *
 * @param parser the node-tree parser to use; defaults to a fresh, stateless instance
 */
internal class NodeTreeProvenanceResolver(private val parser: PgNodeTreeParser = PgNodeTreeParser()) {

  /**
   * Resolves every non-junk output column of [nodeTreeText], in `SELECT`/`RETURNING` order, to
   * either its [NodeTreeColumnProvenance] or `null` (no CTE provenance for that column).
   *
   * Reads `:returningList` first, falling back to `:targetList` only when `:returningList` is empty
   * — the same ordering [ColumnNullabilityAnalyzer.analyzeNodeTree] uses, for the identical reason:
   * a topmost `INSERT`/`UPDATE`/`DELETE`/`MERGE ... RETURNING` populates BOTH, and `:targetList`
   * there holds the value expressions being WRITTEN, not the columns being returned.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` or `pg_proc.prosqlbody` text, or a bare
   *   `{QUERY ...}` block
   * @return one entry per non-junk output column, in position order; every entry is `null` when
   *   [nodeTreeText]'s own outermost statement has a top-level set operation, since a positional
   *   read of ANY single branch would silently ignore every other branch
   */
  fun resolveColumnProvenance(nodeTreeText: String): List<NodeTreeColumnProvenance?> {
    val entries = outputEntries(nodeTreeText).filter { !it.isJunk }.sortedBy { it.resultNumber }
    if (entries.isEmpty()) return emptyList()
    if (parser.hasSetOperations(nodeTreeText)) return entries.map { null }
    // A single-element stack: [nodeTreeText]'s own `:cteList` is the ONLY scope in play until the
    // walk descends into a CTE body — see resolveVar's KDoc for why the stack grows from there.
    val outermostScope = listOf(parser.parseCteList(nodeTreeText).associateBy { it.name })
    return entries.map { entry -> resolveVar(entry.expression, nodeTreeText, outermostScope) }
  }

  /** `:returningList` when non-empty, else `:targetList` — see [resolveColumnProvenance]'s KDoc. */
  private fun outputEntries(queryBlock: String): List<TargetEntry> {
    val returningEntries = parser.parseReturningList(queryBlock)
    return returningEntries.ifEmpty { parser.parseTargetList(queryBlock) }
  }

  /**
   * Resolves a single output column's [expression] to a [NodeTreeColumnProvenance], following the
   * algorithm documented on this class.
   *
   * [expression] must be a bare [PgNodeExpression.Var] — any other expression shape (a real
   * computed expression written directly in the OUTER query, with no CTE involved at all) is not a
   * request for CTE provenance in the first place, so this returns `null` immediately rather than
   * attempting to interpret it.
   *
   * The walk otherwise proceeds hop by hop, always within [queryBlock] until a hop crosses into a
   * CTE's own body (at which point [queryBlock] is updated to that body): a `Var` into a `JOIN`
   * range-table entry is followed through its `:joinaliasvars` (bailing outright if that entry is
   * not itself a bare `Var` — see this class's own KDoc); a `Var` into a CTE range-table entry
   * records that CTE's name and the current attribute number as the CURRENT best answer, then looks
   * up the CTE's own body target entry at that position. If that body entry is ALSO a bare `Var`
   * (with `:varlevelsup 0`), the walk continues into whatever range-table entry IT references —
   * updating the current best answer only if THAT turns out to be another CTE reference in turn.
   * Landing on anything else (a base table, a derived table, an unmodeled range-table kind, or a
   * body entry that is a genuine non-`Var` expression) stops the walk and returns the CURRENT best
   * answer, if any.
   *
   * A CTE reference's [NodeTreeCteReference.ctelevelsup] says how many query levels ABOVE the block
   * containing that reference its declaring `:cteList` lives — `0` means [queryBlock]'s OWN `WITH`
   * clause; a nested `WITH c` shadows an enclosing `WITH c` of the same name with a DIFFERENT body,
   * so resolving every reference against one flat, outermost-only CTE map (as this resolver used to)
   * silently attributes a shadowed reference to the WRONG CTE. [scopeStack] tracks this precisely: index
   * `0` is always the CURRENT [queryBlock]'s own `:cteList`, index `1` the query block ONE level up
   * that declared it (if [queryBlock] is itself a CTE body, that is the `:cteList` its own CTE
   * reference was resolved against), and so on.
   *
   * Scope is LEXICAL and belongs to the DECLARATION site, not to the path this resolver happened to
   * walk to get there: hopping from one CTE's body into a SIBLING CTE it references (both declared in
   * the SAME `:cteList`) is not a nesting level — PostgreSQL's own `:ctelevelsup` for that reference
   * already says so (`1`, addressing the shared list one level up, not `0`). So when [reference]
   * resolves against `scopeStack[reference.ctelevelsup]`, entering that CTE's own body must see the
   * scope AS IT EXISTED AT THAT DECLARATION — `scopeStack.drop(reference.ctelevelsup)` — with that
   * body's own `:cteList` pushed onto the front, never [scopeStack] as accumulated by however many
   * hops it took this resolver to arrive here. Blindly prepending onto the FULL [scopeStack] on every
   * hop (as this resolver used to) leaves stale frames from CTEs the new body was never lexically
   * inside of at every index beyond `0`, which both (a) attributes a chained reference to the WRONG
   * same-named CTE from three or more hops in, and (b) makes an ever-deeper stack drift out of step
   * with the FIXED `:ctelevelsup` a sibling-only chain keeps re-using, so a chain of three or more
   * plain (non-nested) CTEs resolves to nothing at all (#238).
   *
   * A [reference]'s `:ctelevelsup` deeper than [scopeStack] is tall (this can only happen for a
   * malformed or not-yet-modeled shape — a real CTE reference's levelsup can never exceed the number
   * of `WITH` clauses actually enclosing it) bails rather than reading past the end of what has
   * actually been tracked.
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
          // drop, not prepend-onto-the-full-stack: see this method's own KDoc on why scope belongs
          // to the DECLARATION site (reference.ctelevelsup levels up from HERE), never the hop path.
          currentScopeStack = listOf(ownScope) + currentScopeStack.drop(reference.ctelevelsup)
          currentVar = bodyVar
        }
        else ->
          return if (hops.isEmpty()) null else NodeTreeColumnProvenance(hops, resolvedPosition!!)
      }
    }
  }
}
