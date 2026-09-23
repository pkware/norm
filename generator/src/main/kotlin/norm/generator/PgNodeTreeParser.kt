package norm.generator

/**
 * Extracts query-level sections such as the target list, range table, and CTE list from PostgreSQL
 * `pg_node_tree` text.
 *
 * No method throws on malformed input.
 */
internal class PgNodeTreeParser {

  private val scanner = PgNodeTreeScanner()
  private val expressionParser = PgNodeExpressionParser(scanner)

  /**
   * Parses a single `{NODE_TYPE :field value ...}` expression block into a typed [PgNodeExpression].
   *
   * @param text the full node block text, including surrounding braces
   * @return the parsed expression, or [PgNodeExpression.Unknown] for unrecognized or malformed input
   */
  fun parseExpression(text: String): PgNodeExpression = expressionParser.parseExpression(text)

  /**
   * Returns `true` if the outermost QUERY node in [nodeTreeText] has a `:setOperations` field.
   *
   * PostgreSQL represents `UNION ALL`, `INTERSECT`, and `EXCEPT` queries by adding a `:setOperations`
   * node at the top-level QUERY. When this field is present, the `:rtable` contains one subquery
   * RTE per branch — these are NOT derived tables and must not be traced through individually, because
   * the result nullability depends on the union of ALL branches, not just the first one.
   *
   * Detection is performed by scanning for the literal `:setOperations {` at brace depth 1 in the
   * outermost QUERY block (the direct field level, not nested inside any child node). This ensures
   * we don't misdetect `:setOperations` fields inside deeply nested subqueries.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text
   * @return `true` if the outer query is a set operation query; `false` otherwise
   */
  fun hasSetOperations(nodeTreeText: String): Boolean {
    val markerEnd = scanner.findMarkerAtDepthOne(nodeTreeText, ":setOperations ")
    if (markerEnd == -1) return false
    // The value may be preceded by extra whitespace; a non-`{` value (e.g. `<>` for empty) means
    // this is not a set operation query.
    var index = markerEnd
    while (index < nodeTreeText.length && nodeTreeText[index] == ' ') index++
    return index < nodeTreeText.length && nodeTreeText[index] == '{'
  }

  /**
   * Parses the top-level `WHERE` clause expression from [nodeTreeText].
   *
   * Locates `:jointree {FROMEXPR ...}` at the outermost QUERY level and returns its `:quals`
   * expression. The `ON` clause of a `JOINEXPR` nested in `:fromlist` is deliberately not
   * returned — it sits at a deeper brace level and does not constrain rows the way `WHERE` does.
   *
   * @return the parsed `WHERE` expression, or `null` when the query has no `WHERE` clause
   *   (`:quals <>`), when there is no `:jointree`, or when [nodeTreeText] is malformed
   */
  fun parseWhereQuals(nodeTreeText: String): PgNodeExpression? {
    val jointreeMarkerEnd = scanner.findMarkerAtDepthOne(nodeTreeText, ":jointree ")
    if (jointreeMarkerEnd == -1 ||
      jointreeMarkerEnd >= nodeTreeText.length ||
      nodeTreeText[jointreeMarkerEnd] != '{'
    ) {
      return null
    }
    val fromExprBlock = scanner.extractBalancedBraces(nodeTreeText, jointreeMarkerEnd) ?: return null

    val qualsMarkerEnd = scanner.findMarkerAtDepthOne(fromExprBlock, ":quals ")
    if (qualsMarkerEnd == -1 ||
      qualsMarkerEnd >= fromExprBlock.length ||
      fromExprBlock[qualsMarkerEnd] != '{'
    ) {
      return null
    }
    val qualsBlock = scanner.extractBalancedBraces(fromExprBlock, qualsMarkerEnd) ?: return null
    return parseExpression(qualsBlock)
  }

  /**
   * Returns `true` if the outermost QUERY node in [nodeTreeText] has a non-empty `:groupingSets` field.
   *
   * PostgreSQL's `GROUPING SETS`, `CUBE`, and `ROLLUP` clauses populate `:groupingSets` with
   * `{GROUPINGSET ...}` nodes. When this field is present, GROUP BY columns can receive NULL values
   * for rows where the column is not part of the current grouping set — even if the underlying base
   * table column is declared NOT NULL.
   *
   * A plain `GROUP BY` (without GROUPING SETS/CUBE/ROLLUP) has `:groupingSets <>` (empty).
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text
   * @return `true` if the outer query uses GROUPING SETS, CUBE, or ROLLUP; `false` otherwise
   */
  fun hasGroupingSets(nodeTreeText: String): Boolean = scanner.rawListAtDepthOne(nodeTreeText, ":groupingSets") != null

  /**
   * Returns the union of `tleSortGroupRef` values that identify a `GROUP BY` grouping key: every
   * `:tleSortGroupRef` in `:groupClause`, unioned with every integer found inside `:groupingSets`.
   *
   * `:groupingSets` holds one or more `{GROUPINGSET ...}` nodes. A `GROUPINGSET` with `:kind 1`
   * (SIMPLE) stores its member `tleSortGroupRef`s directly as an integer list, e.g. `:content (i 1 2)`;
   * ROLLUP/CUBE/SETS (`:kind` 2/3/4) nest further `{GROUPINGSET ...}` blocks in `:content` instead —
   * PostgreSQL does not pre-expand ROLLUP/CUBE into their individual grouping sets at parse-analysis
   * time, that happens later in the planner. Rather than modeling that nesting, this method takes the
   * union of every `(i ...)` integer list found anywhere inside `:groupingSets`, which is exactly the
   * set of grouping-key `tleSortGroupRef`s regardless of nesting, since a `GROUPINGSET`'s only other
   * fields are the scalar `:kind` and `:location` integers, never `(i ...)`-formatted.
   *
   * Relying on `:groupClause` alone would miss a `GROUPING SETS`/`CUBE`/`ROLLUP` grouping key that a
   * target-list entry's `:ressortgroupref` still points at, and relying on `:groupingSets` alone is
   * needlessly fragile against alternate/older node-tree shapes, so both sources are unioned.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text
   * @return the set of `tleSortGroupRef` values that are `GROUP BY` grouping keys; empty if the
   *   query has neither a `:groupClause` nor a `:groupingSets`
   */
  fun parseGroupingSortGroupRefs(nodeTreeText: String): Set<Int> {
    val fromGroupClause = scanner.rawListAtDepthOne(nodeTreeText, ":groupClause")
      ?.let { groupClauseContent ->
        scanner.splitBraceBlocks(groupClauseContent).mapNotNull { clauseBlock ->
          scanner.extractIntField(clauseBlock, ":tleSortGroupRef")
        }
      } ?: emptyList()
    val fromGroupingSets = scanner.rawListAtDepthOne(nodeTreeText, ":groupingSets")
      ?.let { groupingSetsContent ->
        integerListPattern.findAll(groupingSetsContent).flatMap { match ->
          match.groupValues[1].trim().split(PgNodeTreeScanner.whitespace).mapNotNull { it.toIntOrNull() }
        }.toList()
      } ?: emptyList()
    return (fromGroupClause + fromGroupingSets).toSet()
  }

  /**
   * Parses the CTE definitions from a full `pg_node_tree` text.
   *
   * Extracts the `:cteList` section from [nodeTreeText] at the outermost QUERY level, splits it
   * into `{COMMONTABLEEXPR ...}` blocks, and returns each one as a [NodeTreeCteDefinition] with the CTE
   * name and its `:ctequery {QUERY ...}` block text.
   *
   * CTEs are returned in declaration order so callers can process cascading CTEs where later
   * definitions reference earlier ones.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text
   * @return the list of CTE definitions in declaration order, or an empty list if [nodeTreeText]
   *   contains no `:cteList`
   */
  fun parseCteList(nodeTreeText: String): List<NodeTreeCteDefinition> {
    val cteListContent = scanner.rawListAtDepthOne(nodeTreeText, ":cteList") ?: return emptyList()
    return scanner.splitBraceBlocks(cteListContent).mapNotNull { block ->
      if (!block.startsWith("{COMMONTABLEEXPR")) return@mapNotNull null
      val cteName = scanner.extractStringField(block, ":ctename") ?: return@mapNotNull null
      val cteQueryMarker = ":ctequery {"
      val cteQueryIndex = block.indexOf(cteQueryMarker)
      if (cteQueryIndex == -1) return@mapNotNull null
      val braceStart = cteQueryIndex + cteQueryMarker.length - 1
      val queryBlock = scanner.extractBalancedBraces(block, braceStart) ?: return@mapNotNull null
      // :cterecursive is serialized after :ctequery in COMMONTABLEEXPR's field order, so a naive
      // whole-block scan could find a nested COMMONTABLEEXPR's same-named field first if queryBlock
      // itself declares a nested WITH clause; boolAtDepthOne scopes the search to block's own
      // outermost brace to avoid that. :ctename needs no such scoping — it is always serialized
      // before :ctequery.
      val recursive = scanner.boolAtDepthOne(block, ":cterecursive") ?: false
      NodeTreeCteDefinition(name = cteName, queryBlock = queryBlock, recursive = recursive)
    }
  }

  /**
   * Parses every range-table entry from [nodeTreeText]'s own `:rtable`, regardless of `rtekind`,
   * into a [RangeTableEntry] — see that type's KDoc for why a resolver needs visibility into every
   * kind, not just the ones [baseRelations], [subqueryBlocks], and [cteReferences] each derive
   * individually.
   *
   * None of the fields read here need [PgNodeTreeScanner.findMarkerAtDepthOne]'s depth-one-awareness:
   * a `JOINEXPR` range-table entry's own fields (`:jointype`, `:joinaliasvars`, etc.) contain no
   * nested `QUERY` block that could shadow them (`:joinaliasvars`'s entries are scalar expressions,
   * never a whole query), and a `CTE` range-table entry (`rtekind 6`) carries only the CTE's name and
   * scope metadata, not its body — the body lives in `:cteList`, parsed separately by [parseCteList].
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text (or a bare `{QUERY ...}` block)
   * @return a map from 1-based varno to [RangeTableEntry], or an empty map if [nodeTreeText] is
   *   malformed or contains no `:rtable`
   */
  fun parseRangeTableEntries(nodeTreeText: String): Map<Int, RangeTableEntry> {
    val rtableContent = scanner.rawListAtDepthOne(nodeTreeText, ":rtable") ?: return emptyMap()
    return buildMap {
      scanner.splitBraceBlocks(rtableContent).forEachIndexed { index, rangeTableEntry ->
        val rtekind = scanner.extractIntField(rangeTableEntry, ":rtekind") ?: return@forEachIndexed
        val entry: RangeTableEntry = when (rtekind) {
          0 -> {
            val relid = scanner.extractIntField(rangeTableEntry, ":relid") ?: return@forEachIndexed
            RangeTableEntry.Relation(relid)
          }
          1 -> {
            val subqueryMarker = ":subquery {"
            val subqueryIndex = rangeTableEntry.indexOf(subqueryMarker)
            if (subqueryIndex == -1) return@forEachIndexed
            val braceStart = subqueryIndex + subqueryMarker.length - 1
            val subqueryBlock = scanner.extractBalancedBraces(rangeTableEntry, braceStart) ?: return@forEachIndexed
            RangeTableEntry.Subquery(subqueryBlock)
          }
          2 -> RangeTableEntry.Join(joinAliasVars = expressionParser.parseArgList(rangeTableEntry, ":joinaliasvars"))
          6 -> {
            val cteName = scanner.extractStringField(rangeTableEntry, ":ctename") ?: return@forEachIndexed
            val ctelevelsup = scanner.extractIntField(rangeTableEntry, ":ctelevelsup") ?: 0
            val selfReference = scanner.extractBoolField(rangeTableEntry, ":self_reference") ?: false
            RangeTableEntry.Cte(NodeTreeCteReference(cteName, ctelevelsup, selfReference))
          }
          9 -> {
            // rawListAtDepthOne works on any {NODE ...} block at its own outer level (depth 1).
            val groupExprsContent = scanner.rawListAtDepthOne(rangeTableEntry, ":groupexprs")
            if (groupExprsContent != null) {
              RangeTableEntry.Group(scanner.splitBraceBlocks(groupExprsContent))
            } else {
              RangeTableEntry.Other(rtekind)
            }
          }
          else -> RangeTableEntry.Other(rtekind)
        }
        put(index + 1, entry) // varno is 1-based
      }
    }
  }

  /**
   * Parses the [TargetEntry] items from a full `pg_node_tree` text.
   *
   * Extracts the `:targetList` section from [nodeTreeText], splits it into `{TARGETENTRY ...}`
   * blocks, and parses each one into a [TargetEntry].
   *
   * The returned list includes **all** entries, including junk entries (where [TargetEntry.isJunk]
   * is `true`). Junk entries are internal planner bookkeeping (e.g. sort keys) and typically should
   * not be exposed as result columns. Callers that only want visible columns must filter on
   * `!isJunk`.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text
   * @return the list of target entries (including junk entries), in the order they appear in the
   *   node tree, or an empty list if [nodeTreeText] is malformed or contains no `:targetList`
   */
  fun parseTargetList(nodeTreeText: String): List<TargetEntry> {
    val targetListContent = scanner.rawListAtDepthOne(nodeTreeText, ":targetList") ?: return emptyList()
    return splitTargetEntries(targetListContent).mapNotNull { entry ->
      parseTargetEntry(entry)
    }
  }

  /**
   * Parses the `:returningList` from a DML node tree into [TargetEntry] items.
   *
   * DML statements (INSERT/UPDATE/DELETE) with a RETURNING clause store their output columns in
   * `:returningList` rather than `:targetList`. This method extracts those entries using the same
   * parsing logic as [parseTargetList].
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text or a bare `{QUERY ...}` block
   * @return the list of returning entries (including junk entries), or an empty list if
   *   [nodeTreeText] contains no `:returningList`
   */
  fun parseReturningList(nodeTreeText: String): List<TargetEntry> {
    val content = scanner.rawListAtDepthOne(nodeTreeText, ":returningList") ?: return emptyList()
    return splitTargetEntries(content).mapNotNull { entry -> parseTargetEntry(entry) }
  }

  /**
   * Parses the outermost QUERY node's `:resultRelation` field: the 1-based `rtable` index of the
   * table an `INSERT`/`UPDATE`/`DELETE`/`MERGE` writes to, or `0` for a plain `SELECT` (`0` is
   * never a valid `rtable` index, so it is a safe "no target relation" sentinel for callers).
   *
   * [PgNodeTreeScanner.extractIntField] finds the first unscoped textual occurrence of
   * `:resultRelation` in [nodeTreeText], which is always the outermost QUERY's own field, never a
   * nested CTE's or subquery's: `:resultRelation` is serialized immediately after `:utilityStmt`,
   * before `:cteList` or `:rtable` — where a nested `{QUERY ...}` block would appear — on PostgreSQL
   * 16, 17, and 18.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` or `pg_proc.prosqlbody` text, or a bare
   *   `{QUERY ...}` block
   * @return the 1-based `rtable` index of the target relation, or `0` if absent/unparseable
   */
  fun parseResultRelation(nodeTreeText: String): Int = scanner.extractIntField(nodeTreeText, ":resultRelation") ?: 0

  /**
   * Parses the outermost QUERY node's `:commandType` field (`CmdType` in `nodes/parsenodes.h`):
   * `1` = `SELECT`, `2` = `UPDATE`, `3` = `INSERT`, `4` = `DELETE`, `5` = `MERGE`. Same "first
   * unscoped occurrence is always the outer query's own field" reasoning as
   * [parseResultRelation] — `:commandType` is serialized even earlier in each `QUERY` node than
   * `:resultRelation` is.
   *
   * @return the outermost statement's command type, or `0` (`CMD_UNKNOWN`, never a real command
   *   type PostgreSQL emits) if absent/unparseable
   */
  fun parseCommandType(nodeTreeText: String): Int = scanner.extractIntField(nodeTreeText, ":commandType") ?: 0

  /**
   * `true` when [nodeTreeText]'s outermost `MERGE` statement declares at least one `WHEN ... THEN
   * DELETE` action — i.e. at least one `{MERGEACTION ...}` block in `:mergeActionList` whose own
   * `:commandType` is `4` (`DELETE`, same enum as [parseCommandType]'s top-level use, but scoped
   * here to each individual action rather than the outermost statement).
   *
   * A `MERGE` with no `DELETE` action anywhere — only `UPDATE`/`INSERT` actions — always leaves a
   * written or freshly-inserted row behind for `RETURNING` to see, so its `NEW` reference is exactly
   * as trustworthy as an ordinary column; only the presence of a `DELETE` action makes `NEW`
   * unconditionally forced nullable (see [ColumnNullabilityAnalyzer.forcesNewNullable]'s caller).
   *
   * @return `false` for a non-`MERGE` statement (`:mergeActionList` is absent), or for a `MERGE`
   *   with no `DELETE` action
   */
  fun hasDeleteMergeAction(nodeTreeText: String): Boolean {
    val mergeActionListContent = scanner.rawListAtDepthOne(nodeTreeText, ":mergeActionList")
      ?: return false
    return scanner.splitBraceBlocks(mergeActionListContent).any { actionBlock ->
      scanner.extractIntField(actionBlock, ":commandType") == COMMAND_TYPE_DELETE
    }
  }

  private fun parseTargetEntry(text: String): TargetEntry? {
    val expressionMarker = ":expr {"
    val expressionIndex = text.indexOf(expressionMarker)
    if (expressionIndex == -1) return null
    val braceIndex = expressionIndex + expressionMarker.length - 1
    val expressionText = scanner.extractBalancedBraces(text, braceIndex) ?: return null
    val expression = parseExpression(expressionText)

    // Extract fields from the text AFTER the balanced :expr block. The :expr may contain nested
    // TARGETENTRY nodes (e.g., AGGREF :args) whose :resno/:resname/:resjunk fields would shadow
    // the outer TARGETENTRY's fields if we searched the full text.
    val suffixStart = braceIndex + expressionText.length
    val suffix = if (suffixStart < text.length) text.substring(suffixStart) else ""
    val resultNumber = scanner.extractIntField(suffix, ":resno") ?: return null
    val resultName = scanner.extractStringField(suffix, ":resname")
    val isJunk = suffix.contains(":resjunk true")
    val sortGroupRef = scanner.extractIntField(suffix, ":ressortgroupref") ?: 0
    val originalTableOid = scanner.extractIntField(suffix, ":resorigtbl") ?: 0
    val originalColumnNumber = scanner.extractIntField(suffix, ":resorigcol") ?: 0
    return TargetEntry(
      expression = expression,
      resultName = resultName,
      resultNumber = resultNumber,
      isJunk = isJunk,
      sortGroupRef = sortGroupRef,
      originalTableOid = originalTableOid,
      originalColumnNumber = originalColumnNumber,
    )
  }

  /**
   * The first textual `:varno` and `:varattno` in [block], via [PgNodeTreeScanner.extractIntField] —
   * not [parseExpression] — so a `:groupexprs` entry that is not itself a bare `VAR` (e.g. a
   * `FUNCEXPR` wrapping one) still yields the `VAR` nested inside it. Used by `groupRteMap`.
   *
   * @return `null` if either field is absent.
   */
  internal fun firstVarnoAndVarattno(block: String): Pair<Int, Int>? {
    val varno = scanner.extractIntField(block, ":varno") ?: return null
    val varattno = scanner.extractIntField(block, ":varattno") ?: return null
    return varno to varattno
  }

  /**
   * Splits the content of `:targetList` into individual `{TARGETENTRY ...}` blocks, respecting
   * nested braces.
   */
  private fun splitTargetEntries(targetListContent: String): List<String> =
    scanner.splitBraceBlocks(targetListContent).filter { it.startsWith("{TARGETENTRY") }

  companion object {
    /** `:commandType` values — see [parseCommandType]'s KDoc. */
    const val COMMAND_TYPE_DELETE: Int = 4
    const val COMMAND_TYPE_MERGE: Int = 5

    private val integerListPattern = Regex("""\(i((?:\s+-?\d+)+)\s*\)""")
  }
}
