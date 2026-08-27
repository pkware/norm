package norm.generator

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Parses PostgreSQL `pg_node_tree` text into typed [PgNodeExpression] nodes.
 *
 * The `pg_node_tree` format uses `{NODE_TYPE :field value ...}` notation where values may be
 * integers, booleans, bitmapsets (`(b N ...)`), or nested `{...}` blocks.
 *
 * **Escaping**: Postgres's node-tree writer (`outToken` in `outfuncs.c`) backslash-escapes any
 * character in a string/identifier VALUE that would otherwise be misread as structural by the
 * reader: `{`, `}`, `(`, `)`, whitespace, and the backslash character itself (verified empirically
 * against a live server — e.g. a column alias of `k}x` is written as `:resname k\}x`, and a
 * literal backslash in a value is written as `\\`). Every backslash-prefixed pair is exactly two
 * characters (the escaping is never multi-character, e.g. there is no `\n`-for-newline mnemonic —
 * a literal newline is written as a backslash followed by the raw newline byte). Every function in
 * this class that scans raw text character-by-character for structural `{`, `}`, `(`, or `)` must
 * treat a `\`-prefixed pair as an opaque, non-structural unit — see [nextUnescapedIndexOf],
 * [findMarkerAtDepthOne], and [extractBalancedDelimiters]. Field-name markers (e.g. `:targetList (`,
 * `:expr {`) are Postgres's own fixed labels, never user data, so they are never escaped, and the
 * plain (non-escape-aware) substring searches for them elsewhere in this class ([extractArgListSection]
 * and similar) remain safe as long as they hand off to an escape-aware balanced-delimiter scan for
 * everything past the marker. [extractFieldExpression] is the one exception worth calling out
 * explicitly: unlike those, it searches via the escape-aware AND depth-one-aware
 * [findMarkerAtDepthOne] rather than a plain `indexOf` — see its own KDoc for why depth-one-awareness
 * is required there specifically (several node types have a field whose own value can legally
 * contain another node of the same outer type carrying the same field name, nested deeper).
 *
 * This class is stateless. Call [parseExpression] with any `{NODE_TYPE ...}` text to get a typed
 * node. Unrecognized node types become [PgNodeExpression.Unknown] and malformed input becomes
 * `Unknown("PARSE_ERROR")` — this class never throws.
 */
internal class PgNodeTreeParser {

  private val logger = Logger.getLogger(PgNodeTreeParser::class.java.name)

  private val nodeTypePattern = Regex("""^\{(\w+)""")
  private val whitespace = Regex("""\s+""")
  private val bitmapsetPattern = Regex("""\(([^)]*)\)""")
  private val integerListPattern = Regex("""\(i((?:\s+-?\d+)+)\s*\)""")
  private val intFieldPatterns = mutableMapOf<String, Regex>()
  private val boolFieldPatterns = mutableMapOf<String, Regex>()
  private val stringFieldPatterns = mutableMapOf<String, Regex>()

  /**
   * Parses a single `{NODE_TYPE :field value ...}` expression block into a typed [PgNodeExpression].
   *
   * @param text the full node block text, including surrounding braces
   * @return the parsed expression, or [PgNodeExpression.Unknown] for unrecognized or malformed input
   */
  fun parseExpression(text: String): PgNodeExpression = try {
    val nodeType = nodeTypePattern.find(text)?.groupValues?.get(1)
      ?: return PgNodeExpression.Unknown("PARSE_ERROR").also {
        logger.log(Level.FINE, "Could not determine node type from: $text")
      }
    when (nodeType) {
      "VAR" -> parseVar(text)
      "CONST" -> parseConst(text)
      "FUNCEXPR" -> parseFuncExpr(text)
      "OPEXPR" -> parseOpExpr(text)
      "SCALARARRAYOPEXPR" -> parseScalarArrayOpExpr(text)
      "AGGREF" -> parseAggref(text)
      "WINDOWFUNC" -> parseWindowFunc(text)
      "COALESCEEXPR" -> parseCoalesceExpr(text)
      "NULLIFEXPR" -> parseNullIfExpr(text)
      "MINMAXEXPR" -> parseMinMaxExpr(text)
      "SUBLINK" -> parseSubLink(text)
      "CASEEXPR" -> parseCaseExpr(text)
      "BOOLEXPR" -> parseBoolExpr(text)
      "RELABELTYPE" -> parseRelabelType(text)
      "COERCEVIAIO" -> parseCoerceViaIo(text)
      "ARRAYCOERCEEXPR" -> parseArrayCoerceExpr(text)
      "COLLATEEXPR" -> parseCollateExpr(text)
      "COERCETODOMAIN" -> parseCoerceToDomain(text)
      "SQLVALUEFUNCTION" -> parseSqlValueFunction(text)
      "NULLTEST" -> parseNullTest(text)
      "BOOLEANTEST" -> parseBooleanTest(text)
      "NEXTVALEXPR" -> parseNextValExpr(text)
      "GROUPINGFUNC" -> parseGroupingFunc(text)
      "DISTINCTEXPR" -> parseDistinctExpr(text)
      "ARRAYEXPR" -> parseArrayExpr(text)
      "ROWEXPR" -> parseRowExpr(text)
      "FIELDSELECT" -> parseFieldSelect(text)
      "JSONISPREDICATE" -> parseJsonIsPredicate(text)
      "JSONCONSTRUCTOREXPR" -> parseJsonConstructorExpr(text)
      "JSONEXPR" -> parseJsonExpr(text)
      "XMLEXPR" -> parseXmlExpr(text)
      else -> PgNodeExpression.Unknown(nodeType)
    }
  } catch (cause: RuntimeException) {
    logger.log(Level.FINE, "Failed to parse node tree expression: ${cause.message}\nInput: $text")
    PgNodeExpression.Unknown("PARSE_ERROR")
  }

  /**
   * Parses the range table from a full `pg_node_tree` text into a map from 1-based `varno` to `relid` OID.
   *
   * Extracts the `:rtable` section from [nodeTreeText] at the outermost QUERY level, splits it
   * into `{RANGETBLENTRY ...}` blocks, and returns a map from 1-based position index (varno) to
   * the `:relid` OID for each entry with `rtekind 0` (regular base table).
   *
   * Entries with `rtekind != 0` (subqueries with `rtekind 1`, joins with `rtekind 2`,
   * functions with `rtekind 3`, CTEs with `rtekind 6`, etc.) are skipped and contribute `null`
   * for their position — their varnos do not appear as keys in the returned map.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text
   * @return a map from 1-based varno to `relid` OID for base table range table entries only,
   *   or an empty map if [nodeTreeText] is malformed or contains no `:rtable`
   */
  fun parseRangeTable(nodeTreeText: String): Map<Int, Int> {
    val rtableContent = extractOuterSectionContent(nodeTreeText, ":rtable (") ?: return emptyMap()
    return buildMap {
      splitBraceBlocks(rtableContent).forEachIndexed { index, rangeTableEntry ->
        val rtekind = extractIntField(rangeTableEntry, ":rtekind") ?: return@forEachIndexed
        if (rtekind != 0) return@forEachIndexed
        val relid = extractIntField(rangeTableEntry, ":relid") ?: return@forEachIndexed
        put(index + 1, relid) // varno is 1-based
      }
    }
  }

  /**
   * Parses GROUP BY RTE entries (rtekind 9) from a full `pg_node_tree` text.
   *
   * PostgreSQL creates an `*GROUP*` range table entry (rtekind 9) for aggregate queries with
   * GROUP BY. Target list VARs for grouped columns reference this GROUP RTE (using its varno)
   * rather than the base table directly. Each GROUP RTE holds a `:groupexprs` list of VARs
   * pointing back to the original base table columns.
   *
   * Example: `SELECT author.name, COUNT(*) FROM author JOIN book ... GROUP BY author.name`
   * produces a target list with `Var(varno=4, varattno=1)` where varno=4 is the GROUP RTE and
   * varattno=1 selects the first entry in `:groupexprs` — `Var(varno=1, varattno=2)` (author.name).
   *
   * @return a map from `(groupVarno, 1-based attribute position)` to `(baseVarno, baseVarattno)`,
   *   enabling resolution of GROUP BY column references back to their source base table columns;
   *   or an empty map if the node tree contains no GROUP BY RTEs or is malformed
   */
  fun parseGroupRteMap(nodeTreeText: String): Map<Pair<Int, Int>, Pair<Int, Int>> {
    val rtableContent = extractOuterSectionContent(nodeTreeText, ":rtable (") ?: return emptyMap()
    return buildMap {
      splitBraceBlocks(rtableContent).forEachIndexed { index, rangeTableEntry ->
        val rtekind = extractIntField(rangeTableEntry, ":rtekind") ?: return@forEachIndexed
        if (rtekind != 9) return@forEachIndexed
        val groupVarno = index + 1
        // extractOuterSectionContent works on any {NODE ...} block at its "outer" level (depth 1).
        val groupExprsContent = extractOuterSectionContent(rangeTableEntry, ":groupexprs (")
          ?: return@forEachIndexed
        splitBraceBlocks(groupExprsContent).forEachIndexed { attrIndex, varBlock ->
          val baseVarno = extractIntField(varBlock, ":varno") ?: return@forEachIndexed
          val baseVarattno = extractIntField(varBlock, ":varattno") ?: return@forEachIndexed
          put(groupVarno to (attrIndex + 1), baseVarno to baseVarattno)
        }
      }
    }
  }

  /**
   * Parses GROUP RTE (`rtekind 9`) group expressions — the FULLY PARSED counterpart to
   * [parseGroupRteMap] — from a full `pg_node_tree` text.
   *
   * PostgreSQL 18 introduced an `RTE_GROUP` range-table entry (`:rtekind 9`, alias `*GROUP*`) that
   * carries a `:groupexprs` list of the query's grouping-key expressions, and rewrites EVERY
   * target-list occurrence of a grouping-key expression (not merely the one PostgreSQL assigns
   * `:ressortgroupref` to) into a bare `Var` referencing this RTE. PostgreSQL 16 and 17 have no
   * such RTE — no `:rtable` entry there ever has `:rtekind 9` — so on those versions this method's
   * return value is always an empty map, and the original expression is left in place in the
   * target list for [parseTargetList] to see directly.
   *
   * Unlike [parseGroupRteMap], which only resolves a GROUP RTE entry that is itself a bare `VAR`
   * (mapping it back to a `(baseVarno, baseVarattno)` pair), this method parses the `:groupexprs`
   * entry into a full [PgNodeExpression] — a grouping key can be an arbitrary expression (e.g.
   * `lower(a)`, a literal, or a `Var` carrying outer-join `:varnullingrels`), not only a bare
   * column reference. See [substituteGroupRteVars] for how a target-list `Var` referencing this
   * RTE is substituted back to the expression this method returns.
   *
   * @return a map from the GROUP RTE's 1-based `varno` (its position in `:rtable`) to its parsed
   *   `:groupexprs` list, in declaration order — a target-list `Var` whose `:varno` is a key here
   *   resolves to `list[varattno - 1]` (1-based `:varattno` indexing into the 0-based list). Empty
   *   when [nodeTreeText] is malformed, absent, or contains no GROUP RTE (including every
   *   PostgreSQL 16/17 tree, and any PostgreSQL 18+ tree for a query with no `GROUP BY`).
   */
  fun parseGroupRteExpressions(nodeTreeText: String): Map<Int, List<PgNodeExpression>> {
    val rtableContent = extractOuterSectionContent(nodeTreeText, ":rtable (") ?: return emptyMap()
    return buildMap {
      splitBraceBlocks(rtableContent).forEachIndexed { index, rangeTableEntry ->
        val rtekind = extractIntField(rangeTableEntry, ":rtekind") ?: return@forEachIndexed
        if (rtekind != 9) return@forEachIndexed
        val groupVarno = index + 1
        val groupExprsContent = extractOuterSectionContent(rangeTableEntry, ":groupexprs (")
          ?: return@forEachIndexed
        put(groupVarno, splitBraceBlocks(groupExprsContent).map(::parseExpression))
      }
    }
  }

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
    val markerEnd = findMarkerAtDepthOne(nodeTreeText, ":setOperations ")
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
    val jointreeMarkerEnd = findMarkerAtDepthOne(nodeTreeText, ":jointree ")
    if (jointreeMarkerEnd == -1 ||
      jointreeMarkerEnd >= nodeTreeText.length ||
      nodeTreeText[jointreeMarkerEnd] != '{'
    ) {
      return null
    }
    val fromExprBlock = extractBalancedBraces(nodeTreeText, jointreeMarkerEnd) ?: return null

    val qualsMarkerEnd = findMarkerAtDepthOne(fromExprBlock, ":quals ")
    if (qualsMarkerEnd == -1 ||
      qualsMarkerEnd >= fromExprBlock.length ||
      fromExprBlock[qualsMarkerEnd] != '{'
    ) {
      return null
    }
    val qualsBlock = extractBalancedBraces(fromExprBlock, qualsMarkerEnd) ?: return null
    return parseExpression(qualsBlock)
  }

  /**
   * Finds [marker] at brace depth 1 (measured from the first unescaped `{` in [text]) and returns
   * the index just past the marker's last character, or `-1` when [marker] does not appear at
   * that depth before the outermost block closes, or [text] contains no unescaped `{`.
   *
   * "Depth 1" means directly inside the outermost `{...}` block, excluding any field nested
   * inside a child `{...}` block — this is what prevents matching, for example, a nested
   * `JOINEXPR`'s own `:quals` field when looking for the top-level `:quals` of a `FROMEXPR`.
   *
   * Escaping: see the class-level note on backslash escaping. Every `\`-prefixed pair is skipped
   * as an opaque, non-structural unit — an escaped `\{`/`\}` inside a quoted identifier (e.g. a
   * column alias containing a literal `}`) is data, not a real brace, and must not perturb
   * [braceDepth] or terminate the scan early.
   */
  private fun findMarkerAtDepthOne(text: String, marker: String): Int {
    val outerBraceIndex = nextUnescapedIndexOf(text, '{', 0)
    if (outerBraceIndex == -1) return -1

    var braceDepth = 0
    var index = outerBraceIndex
    var markerMatchIndex = 0
    while (index < text.length) {
      val character = text[index]
      if (character == '\\' && index + 1 < text.length) {
        index += 2
        markerMatchIndex = 0
        continue
      }
      when {
        character == '{' -> {
          braceDepth++
          markerMatchIndex = 0
        }
        character == '}' -> {
          braceDepth--
          markerMatchIndex = 0
          if (braceDepth == 0) break
        }
        braceDepth == 1 ->
          if (character == marker[markerMatchIndex]) {
            markerMatchIndex++
            if (markerMatchIndex == marker.length) return index + 1
          } else {
            markerMatchIndex = if (character == marker[0]) 1 else 0
          }
        else -> markerMatchIndex = 0
      }
      index++
    }
    return -1
  }

  /**
   * Returns the index of the first occurrence of [target] in [text] at or after [fromIndex] that
   * is NOT escaped by a preceding backslash, or `-1` if none exists.
   *
   * See the class-level note on backslash escaping. A backslash in `pg_node_tree` output always
   * introduces a one-character escape, so this treats every `\`-prefixed pair as an opaque,
   * two-character unit when scanning — an escaped `target` (e.g. a literal `{` inside a quoted
   * identifier, written as `\{`) is never mistaken for a real, structural occurrence.
   */
  private fun nextUnescapedIndexOf(text: String, target: Char, fromIndex: Int): Int {
    var index = fromIndex
    while (index < text.length) {
      val character = text[index]
      if (character == '\\' && index + 1 < text.length) {
        index += 2
        continue
      }
      if (character == target) return index
      index++
    }
    return -1
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
  fun hasGroupingSets(nodeTreeText: String): Boolean =
    extractOuterSectionContent(nodeTreeText, ":groupingSets (") != null

  /**
   * Returns the union of `tleSortGroupRef` values that identify a `GROUP BY` grouping key: every
   * `:tleSortGroupRef` in `:groupClause`, unioned with every integer found inside `:groupingSets`.
   *
   * `:groupingSets` holds one or more `{GROUPINGSET ...}` nodes. A `GROUPINGSET` with `:kind 1`
   * (SIMPLE) stores its member `tleSortGroupRef`s directly as an integer list, e.g. `:content (i 1 2)`;
   * ROLLUP/CUBE/SETS (`:kind` 2/3/4) nest further `{GROUPINGSET ...}` blocks in `:content` instead —
   * PostgreSQL does not pre-expand ROLLUP/CUBE into their individual grouping sets at parse-analysis
   * time, that happens later in the planner. Rather than modeling that nesting, this method takes the
   * union of every `(i ...)` integer list found anywhere inside `:groupingSets`, which — since a
   * `GROUPINGSET`'s only other fields are the scalar `:kind` and `:location` integers, never
   * `(i ...)`-formatted — is exactly the set of grouping-key `tleSortGroupRef`s regardless of nesting.
   *
   * Both halves matter: relying on `:groupClause` alone would miss a `GROUPING SETS`/`CUBE`/`ROLLUP`
   * grouping key that a target-list entry's `:ressortgroupref` still points at, and relying on
   * `:groupingSets` alone is needlessly fragile against alternate/older node-tree shapes — the union
   * is taken defensively rather than trusting either source alone.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text
   * @return the set of `tleSortGroupRef` values that are `GROUP BY` grouping keys; empty if the
   *   query has neither a `:groupClause` nor a `:groupingSets`
   */
  fun parseGroupingSortGroupRefs(nodeTreeText: String): Set<Int> {
    val fromGroupClause = extractOuterSectionContent(nodeTreeText, ":groupClause (")?.let { groupClauseContent ->
      splitBraceBlocks(groupClauseContent).mapNotNull { clauseBlock ->
        extractIntField(clauseBlock, ":tleSortGroupRef")
      }
    } ?: emptyList()
    val fromGroupingSets = extractOuterSectionContent(nodeTreeText, ":groupingSets (")?.let { groupingSetsContent ->
      integerListPattern.findAll(groupingSetsContent).flatMap { match ->
        match.groupValues[1].trim().split(whitespace).mapNotNull { it.toIntOrNull() }
      }.toList()
    } ?: emptyList()
    return (fromGroupClause + fromGroupingSets).toSet()
  }

  /**
   * Parses subquery range table entries from a full `pg_node_tree` text.
   *
   * Extracts the `:rtable` section from [nodeTreeText] at the outermost QUERY level, and for each
   * entry with `rtekind 1` (subquery), extracts the embedded `:subquery {QUERY ...}` block.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text (or a bare `{QUERY ...}` block)
   * @return a map from 1-based varno to the subquery's `{QUERY ...}` block text, or empty if none
   */
  fun parseSubqueryRangeTable(nodeTreeText: String): Map<Int, String> {
    val rtableContent = extractOuterSectionContent(nodeTreeText, ":rtable (") ?: return emptyMap()
    return buildMap {
      splitBraceBlocks(rtableContent).forEachIndexed { index, rangeTableEntry ->
        val rtekind = extractIntField(rangeTableEntry, ":rtekind") ?: return@forEachIndexed
        if (rtekind != 1) return@forEachIndexed
        val subqueryMarker = ":subquery {"
        val subqueryIndex = rangeTableEntry.indexOf(subqueryMarker)
        if (subqueryIndex == -1) return@forEachIndexed
        val braceStart = subqueryIndex + subqueryMarker.length - 1
        val subqueryBlock = extractBalancedBraces(rangeTableEntry, braceStart) ?: return@forEachIndexed
        put(index + 1, subqueryBlock) // varno is 1-based
      }
    }
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
    val cteListContent = extractOuterSectionContent(nodeTreeText, ":cteList (") ?: return emptyList()
    return splitBraceBlocks(cteListContent).mapNotNull { block ->
      if (!block.startsWith("{COMMONTABLEEXPR")) return@mapNotNull null
      val cteName = extractStringField(block, ":ctename") ?: return@mapNotNull null
      val cteQueryMarker = ":ctequery {"
      val cteQueryIndex = block.indexOf(cteQueryMarker)
      if (cteQueryIndex == -1) return@mapNotNull null
      val braceStart = cteQueryIndex + cteQueryMarker.length - 1
      val queryBlock = extractBalancedBraces(block, braceStart) ?: return@mapNotNull null
      NodeTreeCteDefinition(name = cteName, queryBlock = queryBlock)
    }
  }

  /**
   * Parses CTE range table entries (`rtekind 6`) from a full `pg_node_tree` text.
   *
   * Extracts the `:rtable` section and returns a map from 1-based `varno` to a
   * [NodeTreeCteReference] (the CTE's `:ctename` and `:ctelevelsup`) for each range table entry
   * with `rtekind 6`. `:ctelevelsup` defaults to `0` when absent, matching PostgreSQL's own default
   * for a same-level reference (verified live, PostgreSQL 18: the field is always present on a real
   * CTE RTE, so this default is defensive only).
   *
   * This is the CTE counterpart to [parseRangeTable] (which handles `rtekind 0` base tables)
   * and [parseSubqueryRangeTable] (which handles `rtekind 1` subqueries).
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text (or a bare `{QUERY ...}` block)
   * @return a map from 1-based varno to [NodeTreeCteReference], or an empty map if no CTE RTEs are
   *   found
   */
  fun parseCteRangeTableEntries(nodeTreeText: String): Map<Int, NodeTreeCteReference> {
    val rtableContent = extractOuterSectionContent(nodeTreeText, ":rtable (") ?: return emptyMap()
    return buildMap {
      splitBraceBlocks(rtableContent).forEachIndexed { index, rangeTableEntry ->
        val rtekind = extractIntField(rangeTableEntry, ":rtekind") ?: return@forEachIndexed
        if (rtekind != 6) return@forEachIndexed
        val cteName = extractStringField(rangeTableEntry, ":ctename") ?: return@forEachIndexed
        val ctelevelsup = extractIntField(rangeTableEntry, ":ctelevelsup") ?: 0
        put(index + 1, NodeTreeCteReference(name = cteName, ctelevelsup = ctelevelsup))
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
    val targetListContent = extractOuterSectionContent(nodeTreeText, ":targetList (") ?: return emptyList()
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
    val content = extractOuterSectionContent(nodeTreeText, ":returningList (") ?: return emptyList()
    return splitTargetEntries(content).mapNotNull { entry -> parseTargetEntry(entry) }
  }

  /**
   * Parses the outermost QUERY node's `:resultRelation` field: the 1-based `rtable` index of the
   * table an `INSERT`/`UPDATE`/`DELETE`/`MERGE` writes to, or `0` for a plain `SELECT` (`0` is
   * never a valid `rtable` index, so it is a safe "no target relation" sentinel for callers).
   *
   * [extractIntField] finds the FIRST unscoped textual occurrence of `:resultRelation` in
   * [nodeTreeText], which is always the outermost QUERY's own field, never a nested CTE's or
   * subquery's: `:resultRelation` is serialized immediately after `:utilityStmt` — before
   * `:cteList` or `:rtable`, both of which is where any nested `{QUERY ...}` block would appear —
   * on every PostgreSQL version this class supports (16, 17, 18), the same field-order argument
   * [parseVar]'s own KDoc already relies on for `:varreturningtype`.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` or `pg_proc.prosqlbody` text, or a bare
   *   `{QUERY ...}` block
   * @return the 1-based `rtable` index of the target relation, or `0` if absent/unparseable
   */
  fun parseResultRelation(nodeTreeText: String): Int = extractIntField(nodeTreeText, ":resultRelation") ?: 0

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
  fun parseCommandType(nodeTreeText: String): Int = extractIntField(nodeTreeText, ":commandType") ?: 0

  /**
   * `true` when [nodeTreeText]'s outermost `MERGE` statement declares AT LEAST ONE `WHEN ... THEN
   * DELETE` action — i.e. at least one `{MERGEACTION ...}` block in `:mergeActionList` whose OWN
   * `:commandType` is `4` (`DELETE`, same enum as [parseCommandType]'s top-level use, but scoped
   * here to each individual action rather than the outermost statement).
   *
   * A `MERGE` with no `DELETE` action anywhere — only `UPDATE`/`INSERT` actions — always leaves a
   * written or freshly-inserted row behind for `RETURNING` to see, so its `NEW` reference is exactly
   * as trustworthy as an ordinary column; only the presence of a `DELETE` action makes `NEW`
   * unconditionally forced nullable (see [PgCatalogLoader.forcesNewNullable]'s caller).
   *
   * @return `false` for a non-`MERGE` statement (`:mergeActionList` is absent), or for a `MERGE`
   *   with no `DELETE` action
   */
  fun hasDeleteMergeAction(nodeTreeText: String): Boolean {
    val mergeActionListContent = extractOuterSectionContent(nodeTreeText, ":mergeActionList (") ?: return false
    return splitBraceBlocks(mergeActionListContent).any { actionBlock ->
      extractIntField(actionBlock, ":commandType") == COMMAND_TYPE_DELETE
    }
  }

  private fun parseVar(text: String): PgNodeExpression.Var {
    val varno = extractIntField(text, ":varno")
      ?: error("Missing :varno in VAR node")
    val varattno = extractIntField(text, ":varattno")
      ?: error("Missing :varattno in VAR node")
    val nullingRelations = extractBitmapset(text, ":varnullingrels")
    val levelsUp = extractIntField(text, ":varlevelsup") ?: 0
    // ":varreturningtype" only appears on PostgreSQL 18+ (RETURNING WITH (OLD AS o, NEW AS n)) and
    // is absent entirely on 16/17 — see PgNodeExpression.Var.returningType's KDoc for why 0 (never
    // 1, "OLD") is the correct default for a missing field.
    val returningType = extractIntField(text, ":varreturningtype") ?: 0
    return PgNodeExpression.Var(
      varno = varno,
      varattno = varattno,
      nullingRelations = nullingRelations,
      levelsUp = levelsUp,
      returningType = returningType,
    )
  }

  private fun parseConst(text: String): PgNodeExpression.Const {
    // Default to `true` (nullable) when `:constisnull` cannot be read — never `false`. `:constisnull`
    // is always emitted by a live server, but that guarantee only covers today's known-good format;
    // if a future Postgres version ever renamed or reordered the field, this default is what a
    // malformed/absent read degrades to, and it must fail toward nullable, never toward a confidently
    // wrong NOT NULL.
    val isNull = extractBoolField(text, ":constisnull") ?: true
    return PgNodeExpression.Const(isNull = isNull)
  }

  /**
   * Parses a `{FUNCEXPR ...}` block.
   *
   * Correctness of [PgNodeExpression.FuncExpr.isVariadic] rests on an invariant of the
   * `pg_node_tree` format, verified live on PostgreSQL 16, 17, and 18: `:funcvariadic` is a
   * scalar field of `FUNCEXPR` that always precedes `:args` in that node's own field order, and
   * it is always emitted explicitly — including `:funcvariadic false` for an ordinary call, never
   * omitted. [extractBoolField] matches the FIRST occurrence of `:funcvariadic` anywhere in
   * [text], including inside nested blocks, so this is only safe because the outer node's own
   * field is textually guaranteed to appear before any nested `FUNCEXPR`'s field, regardless of
   * whether the nesting is variadic-in-non-variadic or non-variadic-in-variadic (both directions
   * verified live — see `PgNodeTreeParserTest`). If a future PostgreSQL version ever reordered
   * `FUNCEXPR`'s fields or made `:funcvariadic` conditional, this extraction would silently start
   * reading the wrong node's flag — which is exactly why the fallback below defaults to `true`
   * (variadic) rather than `false`: the "always emitted" guarantee describes today's known-good
   * format, not a reason to trust an optimistic default should that ever stop holding.
   * [NodeTreeNullabilityAnalyzer.isNonNull]'s `FuncExpr` branch treats a variadic call far more
   * conservatively than an ordinary one (see its own KDoc), so defaulting `true` here can only
   * make an unreadable flag resolve toward nullable, never toward a confidently wrong NOT NULL.
   */
  private fun parseFuncExpr(text: String): PgNodeExpression.FuncExpr {
    val functionOid = extractIntField(text, ":funcid") ?: error("Missing :funcid in FUNCEXPR node")
    val isVariadic = extractBoolField(text, ":funcvariadic") ?: true
    return PgNodeExpression.FuncExpr(
      functionOid = functionOid,
      arguments = parseArgList(text, ":args"),
      isVariadic = isVariadic,
    )
  }

  private fun parseOpExpr(text: String): PgNodeExpression.OpExpr {
    val operatorFunctionOid = extractIntField(text, ":opfuncid") ?: error("Missing :opfuncid in OPEXPR node")
    return PgNodeExpression.OpExpr(operatorFunctionOid = operatorFunctionOid, arguments = parseArgList(text, ":args"))
  }

  private fun parseScalarArrayOpExpr(text: String): PgNodeExpression.ScalarArrayOpExpr {
    val operatorFunctionOid = extractIntField(text, ":opfuncid") ?: error("Missing :opfuncid in SCALARARRAYOPEXPR node")
    // Unknown defaults to the more restrictive ALL handling.
    val useOr = extractBoolField(text, ":useOr") ?: false
    return PgNodeExpression.ScalarArrayOpExpr(
      operatorFunctionOid = operatorFunctionOid,
      arguments = parseArgList(text, ":args"),
      useOr = useOr,
    )
  }

  private fun parseAggref(text: String): PgNodeExpression.Aggref {
    val aggregateFunctionOid = extractIntField(text, ":aggfnoid") ?: error("Missing :aggfnoid in AGGREF node")
    return PgNodeExpression.Aggref(
      aggregateFunctionOid = aggregateFunctionOid,
      arguments = extractTargetEntryExpressions(text, ":args"),
    )
  }

  private fun parseWindowFunc(text: String): PgNodeExpression.WindowFunc {
    val windowFunctionOid = extractIntField(text, ":winfnoid") ?: error("Missing :winfnoid in WINDOWFUNC node")
    return PgNodeExpression.WindowFunc(windowFunctionOid = windowFunctionOid, arguments = parseArgList(text, ":args"))
  }

  private fun parseCoalesceExpr(text: String): PgNodeExpression.CoalesceExpr =
    PgNodeExpression.CoalesceExpr(arguments = parseArgList(text, ":args"))

  private fun parseNullIfExpr(text: String): PgNodeExpression.NullIfExpr =
    PgNodeExpression.NullIfExpr(arguments = parseArgList(text, ":args"))

  private fun parseMinMaxExpr(text: String): PgNodeExpression.MinMaxExpr =
    PgNodeExpression.MinMaxExpr(arguments = parseArgList(text, ":args"))

  private fun parseSubLink(text: String): PgNodeExpression.SubLink {
    val subLinkType = extractIntField(text, ":subLinkType") ?: error("Missing :subLinkType in SUBLINK node")
    // :testexpr is extracted UNCONDITIONALLY, regardless of subLinkType — not merely for ANY/ALL.
    // outerOperand (this field's first argument) feeds three consumers beyond isNonNull's own
    // ANY/ALL proof below: NodeTreeNullabilityAnalyzer.safetyWalkChildren,
    // NodeTreeNullabilityAnalyzer.containsVarOutsideRelation, and GroupRteSubstitution's
    // Var-substitution walk. This is uniformity and future-proofing, not the closing of a live hole
    // today: verified live on PostgreSQL 18.4 across all eight SubLinkType values, EXISTS/EXPR/
    // MULTIEXPR/ARRAY/CTE sublinks all emit `:testexpr <>` (absent), and ROWCOMPARE_SUBLINK's own
    // ROWCOMPAREEXPR testexpr carries no `:args` field at all (its operands are under `:largs`/
    // `:rargs` instead — see PgNodeTreeParserTest's own ROWCOMPARE fixture) and is not a node type
    // this parser's own `when` dispatch recognizes, so it parses to Unknown regardless. outerOperand
    // is therefore `null` for every sublink type this analyzer does not already special-case, gated
    // extraction or not — those three consumers see nothing new today. What unconditional extraction
    // buys is robustness against a FUTURE SubLinkType (or a future PostgreSQL version reshaping an
    // existing one) that DOES carry a real single-argument testexpr this parser could read: gating by
    // subLinkType would silently keep that hidden from all three consumers with no compile-time or
    // test-time signal, whereas unconditional extraction makes it visible automatically the moment
    // this parser's `when` dispatch (or a future extraction rule) learns to read it. isNonNull's own
    // ANY/ALL proof stays gated on subLinkType below regardless, so this change alone cannot make any
    // sublink provably non-null.
    val testExprBlock = extractFieldExpression(text, ":testexpr")
    val outerOperand = testExprBlock?.let { testExpr ->
      extractArgListSection(testExpr, ":args")?.let { splitBraceBlocks(it).firstOrNull()?.let(::parseExpression) }
    }
    val testExpressionOperatorOid = (testExprBlock?.let(::parseExpression) as? PgNodeExpression.OpExpr)
      ?.operatorFunctionOid
    // :subselect holds the sublink's subquery body ({QUERY ...}), mirroring parseSubqueryRangeTable
    // and parseCteList's extraction of the same node shape — see subselectBlock's KDoc for why it
    // is captured verbatim rather than parsed here. Depth-one-aware (via extractFieldExpression)
    // is mandatory, not incidental: :testexpr precedes :subselect in SUBLINK's own field order, and
    // :testexpr's own value can contain a NESTED sublink with its own :subselect — see
    // extractFieldExpression's KDoc for the live-verified repro this guards against.
    val subselectBlock = extractFieldExpression(text, ":subselect")
    return PgNodeExpression.SubLink(
      subLinkType = subLinkType,
      outerOperand = outerOperand,
      subselectBlock = subselectBlock,
      testExpressionOperatorOid = testExpressionOperatorOid,
    )
  }

  private fun parseCaseExpr(text: String): PgNodeExpression.CaseExpr {
    // The `CASE testexpr WHEN ...` test expression (`:arg`) holds a real Var for the shorthand
    // form, e.g. `CASE a WHEN 'x' THEN 1 ELSE 2 END` — see PgNodeExpression.CaseExpr's KDoc.
    val testExpression = extractFieldExpression(text, ":arg")?.let { parseExpression(it) }
    val whenBlocks = extractArgListSection(text, ":args")?.let { splitBraceBlocks(it) }
      ?.filter { it.startsWith("{CASEWHEN") }
      ?: emptyList()
    val resultExpressions = whenBlocks.mapNotNull { block -> extractFieldExpression(block, ":result") }
      .map { parseExpression(it) }
    // Each WHEN's own condition (`:expr`) holds the real Var for the explicit form, e.g.
    // `CASE WHEN a = 'x' THEN 1 ELSE 2 END` — see PgNodeExpression.CaseExpr's KDoc.
    val whenConditions = whenBlocks.mapNotNull { block -> extractFieldExpression(block, ":expr") }
      .map { parseExpression(it) }
    val defaultResult = extractFieldExpression(text, ":defresult")?.let { parseExpression(it) }
    return PgNodeExpression.CaseExpr(
      resultExpressions = resultExpressions,
      defaultResult = defaultResult,
      testExpression = testExpression,
      whenConditions = whenConditions,
    )
  }

  private fun parseBoolExpr(text: String): PgNodeExpression.BoolExpr = PgNodeExpression.BoolExpr(
    arguments = parseArgList(text, ":args"),
    boolOperator = extractStringField(text, ":boolop") ?: "",
  )

  private fun parseRelabelType(text: String): PgNodeExpression.RelabelType {
    val argument = extractFieldExpression(text, ":arg") ?: error("Missing :arg in RELABELTYPE node")
    return PgNodeExpression.RelabelType(argument = parseExpression(argument))
  }

  private fun parseCoerceViaIo(text: String): PgNodeExpression.CoerceViaIo {
    val argument = extractFieldExpression(text, ":arg") ?: error("Missing :arg in COERCEVIAIO node")
    return PgNodeExpression.CoerceViaIo(argument = parseExpression(argument))
  }

  private fun parseArrayCoerceExpr(text: String): PgNodeExpression.ArrayCoerceExpr {
    val argument = extractFieldExpression(text, ":arg") ?: error("Missing :arg in ARRAYCOERCEEXPR node")
    return PgNodeExpression.ArrayCoerceExpr(argument = parseExpression(argument))
  }

  private fun parseCollateExpr(text: String): PgNodeExpression.CollateExpr {
    val argument = extractFieldExpression(text, ":arg") ?: error("Missing :arg in COLLATEEXPR node")
    return PgNodeExpression.CollateExpr(argument = parseExpression(argument))
  }

  private fun parseCoerceToDomain(text: String): PgNodeExpression.CoerceToDomain {
    val argument = extractFieldExpression(text, ":arg") ?: error("Missing :arg in COERCETODOMAIN node")
    return PgNodeExpression.CoerceToDomain(argument = parseExpression(argument))
  }

  private fun parseSqlValueFunction(text: String): PgNodeExpression.SqlValueFunction {
    val operation = extractIntField(text, ":op") ?: error("Missing :op in SQLVALUEFUNCTION node")
    return PgNodeExpression.SqlValueFunction(operation = operation)
  }

  private fun parseNullTest(text: String): PgNodeExpression.NullTest {
    val argument = extractFieldExpression(text, ":arg") ?: error("Missing :arg in NULLTEST node")
    // Unknown defaults to the form that proves nothing.
    val nullTestType = extractIntField(text, ":nulltesttype") ?: PgNodeExpression.NULL_TEST_IS_NULL
    return PgNodeExpression.NullTest(argument = parseExpression(argument), nullTestType = nullTestType)
  }

  private fun parseBooleanTest(text: String): PgNodeExpression.BooleanTest {
    val argument = extractFieldExpression(text, ":arg") ?: error("Missing :arg in BOOLEANTEST node")
    return PgNodeExpression.BooleanTest(argument = parseExpression(argument))
  }

  private fun parseNextValExpr(text: String): PgNodeExpression.NextValExpr {
    val sequenceOid = extractIntField(text, ":seqid") ?: error("Missing :seqid in NEXTVALEXPR node")
    return PgNodeExpression.NextValExpr(sequenceOid = sequenceOid)
  }

  private fun parseGroupingFunc(text: String): PgNodeExpression.GroupingFunc =
    PgNodeExpression.GroupingFunc(arguments = parseArgList(text, ":args"))

  private fun parseDistinctExpr(text: String): PgNodeExpression.DistinctExpr =
    PgNodeExpression.DistinctExpr(arguments = parseArgList(text, ":args"))

  private fun parseArrayExpr(text: String): PgNodeExpression.ArrayExpr =
    PgNodeExpression.ArrayExpr(elements = parseArgList(text, ":elements"))

  private fun parseRowExpr(text: String): PgNodeExpression.RowExpr =
    PgNodeExpression.RowExpr(arguments = parseArgList(text, ":args"))

  private fun parseFieldSelect(text: String): PgNodeExpression.FieldSelect {
    val argument = extractFieldExpression(text, ":arg") ?: error("Missing :arg in FIELDSELECT node")
    val fieldNumber = extractIntField(text, ":fieldnum") ?: error("Missing :fieldnum in FIELDSELECT node")
    return PgNodeExpression.FieldSelect(argument = parseExpression(argument), fieldNumber = fieldNumber)
  }

  private fun parseJsonIsPredicate(text: String): PgNodeExpression.JsonIsPredicate {
    val argument = extractFieldExpression(text, ":expr") ?: error("Missing :expr in JSONISPREDICATE node")
    return PgNodeExpression.JsonIsPredicate(argument = parseExpression(argument))
  }

  private fun parseJsonConstructorExpr(text: String): PgNodeExpression.JsonConstructorExpr =
    PgNodeExpression.JsonConstructorExpr(arguments = parseArgList(text, ":args"))

  private fun parseJsonExpr(text: String): PgNodeExpression.JsonExpr {
    val op = extractIntField(text, ":op") ?: error("Missing :op in JSONEXPR node")
    val argument = extractFieldExpression(text, ":formatted_expr") ?: error("Missing :formatted_expr in JSONEXPR node")
    val onEmptyBlock = extractFieldExpression(text, ":on_empty")
    val onEmpty = if (onEmptyBlock != null) {
      extractIntField(onEmptyBlock, ":btype") ?: PgNodeExpression.JSON_BEHAVIOR_NULL
    } else {
      PgNodeExpression.JSON_BEHAVIOR_NULL
    }
    val onEmptyDefault = onEmptyBlock?.let { extractFieldExpression(it, ":expr")?.let(::parseExpression) }
    val onErrorBlock = extractFieldExpression(text, ":on_error")
    val onError = if (onErrorBlock != null) {
      extractIntField(onErrorBlock, ":btype") ?: PgNodeExpression.JSON_BEHAVIOR_NULL
    } else {
      PgNodeExpression.JSON_BEHAVIOR_NULL
    }
    val onErrorDefault = onErrorBlock?.let { extractFieldExpression(it, ":expr")?.let(::parseExpression) }
    return PgNodeExpression.JsonExpr(
      op = op,
      argument = parseExpression(argument),
      onEmpty = onEmpty,
      onEmptyDefault = onEmptyDefault,
      onError = onError,
      onErrorDefault = onErrorDefault,
    )
  }

  private fun parseXmlExpr(text: String): PgNodeExpression.XmlExpr {
    val op = extractIntField(text, ":op") ?: error("Missing :op in XMLEXPR node")
    val namedArguments = parseArgList(text, ":named_args")
    val arguments = parseArgList(text, ":args")
    return PgNodeExpression.XmlExpr(op = op, arguments = namedArguments + arguments)
  }

  /**
   * Extracts the content of the `(...)` list after [fieldName] without parsing it.
   *
   * Returns `null` if the field is absent or its value is `<>` (empty/absent in pg_node_tree).
   */
  private fun extractArgListSection(text: String, fieldName: String): String? {
    val marker = "$fieldName ("
    val markerIndex = text.indexOf(marker)
    if (markerIndex == -1) return null
    val openParenthesisIndex = markerIndex + marker.length - 1
    val content = extractBalancedParentheses(text, openParenthesisIndex)
    return if (content.isNullOrBlank()) null else content
  }

  /**
   * Parses a `(...)` argument list from [fieldName] into a list of [PgNodeExpression]s.
   *
   * Handles `<>` (empty/absent) by returning an empty list.
   */
  private fun parseArgList(text: String, fieldName: String): List<PgNodeExpression> {
    val content = extractArgListSection(text, fieldName) ?: return emptyList()
    return splitBraceBlocks(content).map { parseExpression(it) }
  }

  /**
   * Extracts a named `{...}` expression block from a field like `:fieldName {NODETYPE ...}`, at
   * brace depth 1 of [text] — i.e. [fieldName] must be a direct field of the node [text] itself
   * represents, not a same-named field belonging to some node NESTED inside one of [text]'s own
   * field values.
   *
   * Depth-one-awareness is load-bearing, not defensive polish: [fieldName] is often a field whose
   * OWN value is a full expression subtree that can legally contain another node of the SAME
   * outer type carrying the SAME field name — e.g. a `SUBLINK`'s `:testexpr` field can itself
   * contain a nested `SUBLINK` with its own `:testexpr`/`:subselect`, a `CASEWHEN`'s `:expr`
   * condition can contain a nested `CASEEXPR` with its own `:result`/`:defresult`, and a
   * `JSONEXPR`'s `:on_empty`/`:on_error` behavior can nest another `JSONEXPR`. Because Postgres
   * serializes a node depth-first, a same-named field belonging to a NESTED node is written
   * INSIDE the outer field's own value — textually EARLIER than the outer node's OWN later field
   * of that name would be, whenever the outer field being searched for comes before the nested
   * one in that node's field order. Verified live, PostgreSQL 17 and 18, for `SUBLINK`: its
   * `:testexpr` field precedes its `:subselect` field, so for `SELECT EXISTS (SELECT v FROM u) =
   * ANY (SELECT b FROM x) FROM t`, the OUTER `ANY_SUBLINK`'s `:testexpr` (an `OPEXPR` whose first
   * argument is the nested `EXISTS` sublink, itself a genuine `{SUBLINK ... :subselect {QUERY
   * ... u ...} ...}` block) textually precedes the outer `ANY_SUBLINK`'s OWN `:subselect {QUERY
   * ... x ...}` — a naive first-match `text.indexOf(":subselect {")` scan over the OUTER
   * `ANY_SUBLINK`'s full text therefore returns the INNER `EXISTS` sublink's `u`-block, not the
   * outer sublink's own `x`-block, silently proving the wrong subquery's column nullable or not.
   * [findMarkerAtDepthOne] (reused here, the same helper [parseWhereQuals] uses for its
   * `:jointree`/`:quals` extraction) only matches [fieldName] directly inside [text]'s own
   * outermost `{...}` block, so a nested node's same-named field can never shadow it.
   *
   * [findMarkerAtDepthOne] resets its match state on any `{`, so the marker searched for must be
   * `"$fieldName "` (trailing space, no brace) rather than `"$fieldName {"` — the value's opening
   * brace is located separately, immediately after the marker.
   *
   * Returns `null` if the field is absent, or its value is not a brace block (e.g. `:defresult <>`).
   */
  private fun extractFieldExpression(text: String, fieldName: String): String? {
    val markerEnd = findMarkerAtDepthOne(text, "$fieldName ")
    if (markerEnd == -1 || markerEnd >= text.length || text[markerEnd] != '{') return null
    return extractBalancedBraces(text, markerEnd)
  }

  /**
   * Extracts expression nodes from TARGETENTRY-wrapped items in a named `(...)` list.
   *
   * AGGREF stores its arguments as `{TARGETENTRY :expr {actual_expr} ...}` wrappers. This method
   * splits the list, identifies TARGETENTRY blocks, and extracts the inner `:expr` expression from
   * each one. Non-TARGETENTRY blocks are parsed directly.
   */
  private fun extractTargetEntryExpressions(text: String, fieldName: String): List<PgNodeExpression> {
    val content = extractArgListSection(text, fieldName) ?: return emptyList()
    return splitBraceBlocks(content).map { block ->
      if (block.startsWith("{TARGETENTRY")) {
        val expressionText = extractFieldExpression(block, ":expr")
        if (expressionText != null) parseExpression(expressionText) else PgNodeExpression.Unknown("TARGETENTRY")
      } else {
        parseExpression(block)
      }
    }
  }

  private fun parseTargetEntry(text: String): TargetEntry? {
    val expressionMarker = ":expr {"
    val expressionIndex = text.indexOf(expressionMarker)
    if (expressionIndex == -1) return null
    val braceIndex = expressionIndex + expressionMarker.length - 1
    val expressionText = extractBalancedBraces(text, braceIndex) ?: return null
    val expression = parseExpression(expressionText)

    // Extract fields from the text AFTER the balanced :expr block. The :expr may contain nested
    // TARGETENTRY nodes (e.g., AGGREF :args) whose :resno/:resname/:resjunk fields would shadow
    // the outer TARGETENTRY's fields if we searched the full text.
    val suffixStart = braceIndex + expressionText.length
    val suffix = if (suffixStart < text.length) text.substring(suffixStart) else ""
    val resultNumber = extractIntField(suffix, ":resno") ?: return null
    val resultName = extractStringField(suffix, ":resname")
    val isJunk = suffix.contains(":resjunk true")
    val sortGroupRef = extractIntField(suffix, ":ressortgroupref") ?: 0
    return TargetEntry(
      expression = expression,
      resultName = resultName,
      resultNumber = resultNumber,
      isJunk = isJunk,
      sortGroupRef = sortGroupRef,
    )
  }

  /**
   * Extracts an integer field value from a node block.
   *
   * @param text the full node block text
   * @param fieldName the field name including the leading colon, e.g. `":varno"`
   * @return the integer value, or `null` if the field is absent or unparseable
   */
  private fun extractIntField(text: String, fieldName: String): Int? =
    intFieldPatterns.getOrPut(fieldName) { Regex("""$fieldName (-?\d+)""") }
      .find(text)?.groupValues?.get(1)?.toIntOrNull()

  /**
   * Extracts a boolean field value from a node block.
   *
   * @param text the full node block text
   * @param fieldName the field name including the leading colon, e.g. `":constisnull"`
   * @return the boolean value, or `null` if the field is absent
   */
  private fun extractBoolField(text: String, fieldName: String): Boolean? =
    boolFieldPatterns.getOrPut(fieldName) { Regex("""$fieldName (true|false)""") }
      .find(text)?.groupValues?.get(1)?.let { it == "true" }

  /**
   * Extracts a non-whitespace string field value from a node block, with backslash-escaping
   * removed (see the class-level note on backslash escaping) — e.g. a `:resname` of `k\}x` (an
   * alias `k}x` containing a literal `}`) is returned as `k}x`, not the raw escaped text.
   *
   * This does NOT recover a value containing an escaped (literal) whitespace character — a space,
   * tab, newline, or any other character `\S` excludes: the `\S+` capture group below has no
   * escape-awareness of its own and stops at that byte regardless of the preceding backslash,
   * truncating the match before [unescapeToken] ever runs (verified live: an alias `"t<TAB>x"`
   * captures as just `t\`, dropping the escaped tab and everything after it). No current caller of
   * this method depends on a field value containing whitespace, so this residual gap is left
   * unaddressed rather than rewriting the capture into a full escape-aware scanner for a case no
   * caller hits.
   *
   * @param text the full node block text
   * @param fieldName the field name including the leading colon, e.g. `":ctename"`
   * @return the unescaped string value (first contiguous non-whitespace run after the field name
   *   and space), or `null` if the field is absent
   */
  private fun extractStringField(text: String, fieldName: String): String? =
    stringFieldPatterns.getOrPut(fieldName) { Regex("""$fieldName (\S+)""") }
      .find(text)?.groupValues?.get(1)?.let(::unescapeToken)

  /**
   * Removes `pg_node_tree` backslash-escaping from an already-captured raw token: each
   * `\`-prefixed pair collapses to just the escaped character (see the class-level note on
   * backslash escaping).
   */
  private fun unescapeToken(token: String): String {
    if ('\\' !in token) return token
    val result = StringBuilder(token.length)
    var index = 0
    while (index < token.length) {
      val character = token[index]
      if (character == '\\' && index + 1 < token.length) {
        result.append(token[index + 1])
        index += 2
      } else {
        result.append(character)
        index++
      }
    }
    return result.toString()
  }

  /**
   * Extracts a PostgreSQL bitmapset field value into a [Set] of integers.
   *
   * PostgreSQL encodes bitmapsets as `(b)` for empty, or `(b N ...)` where N is one or more
   * space-separated integers. The `b` is a base marker and is not included in the result.
   *
   * @param text the full node block text
   * @param fieldName the field name including the leading colon, e.g. `":varnullingrels"`
   * @return the set of integer members, or [emptySet] if the field is absent or the set is empty
   */
  private fun extractBitmapset(text: String, fieldName: String): Set<Int> {
    val fieldIndex = text.indexOf(fieldName)
    if (fieldIndex == -1) return emptySet()
    val content = bitmapsetPattern.find(text, fieldIndex + fieldName.length)?.groupValues?.get(1) ?: return emptySet()
    return content.trim().split(whitespace).mapNotNull { it.toIntOrNull() }.toSet()
  }

  /**
   * Finds [marker] at the outermost QUERY level (brace depth 1) and extracts the
   * balanced-parenthesis content that follows the trailing `(` of the marker.
   *
   * @param marker a field marker ending in `(`, e.g. `":targetList ("`
   * @return the content inside the outer parentheses, or `null` if not found or unbalanced
   */
  private fun extractOuterSectionContent(text: String, marker: String): String? {
    check(marker.endsWith("(")) { "marker must end with '(': $marker" }
    val markerEnd = findMarkerAtDepthOne(text, marker)
    if (markerEnd == -1) return null
    val openParenthesisIndex = markerEnd - 1
    return extractBalancedParentheses(text, openParenthesisIndex)
  }

  /**
   * Extracts the content inside balanced parentheses starting at [startIndex].
   *
   * @param text the full text to parse
   * @param startIndex the index of the opening `(`
   * @return the content between the outer `(` and its matching `)`, or `null` if unbalanced
   */
  private fun extractBalancedParentheses(text: String, startIndex: Int): String? =
    extractBalancedDelimiters(text, startIndex, open = '(', close = ')', includeDelimiters = false)

  /**
   * Extracts balanced brace content starting at the `{` at [startIndex].
   *
   * @param text the full text to parse
   * @param startIndex the index of the opening `{`
   * @return the full `{...}` text including the outer braces, or `null` if unbalanced or
   *   [startIndex] does not point to a `{`
   */
  private fun extractBalancedBraces(text: String, startIndex: Int): String? =
    extractBalancedDelimiters(text, startIndex, open = '{', close = '}', includeDelimiters = true)

  /**
   * Extracts content inside balanced delimiters starting at [startIndex].
   *
   * See the class-level note on backslash escaping: a `\`-prefixed pair (e.g. `\{`, `\}`, `\\`, or
   * an escaped space) is copied into [content] verbatim and never counted toward [depth] or
   * matched against [open]/[close], even when the escaped character is itself one of them.
   *
   * @param includeDelimiters when `true`, the outer delimiter pair is included in the result;
   *   when `false`, only the content between the delimiters is returned
   * @return the extracted content, or `null` if [startIndex] does not point to [open] or the
   *   delimiters are unbalanced
   */
  private fun extractBalancedDelimiters(
    text: String,
    startIndex: Int,
    open: Char,
    close: Char,
    includeDelimiters: Boolean,
  ): String? {
    if (startIndex >= text.length || text[startIndex] != open) return null
    var depth = 0
    val content = StringBuilder()
    var index = startIndex
    while (index < text.length) {
      val character = text[index]
      if (character == '\\' && index + 1 < text.length) {
        content.append(character).append(text[index + 1])
        index += 2
        continue
      }
      when (character) {
        open -> {
          depth++
          if (includeDelimiters || depth > 1) content.append(character)
        }
        close -> {
          depth--
          if (depth == 0) {
            if (includeDelimiters) content.append(character)
            return content.toString()
          }
          content.append(character)
        }
        else -> content.append(character)
      }
      index++
    }
    return null
  }

  /**
   * Splits the content of `:targetList` into individual `{TARGETENTRY ...}` blocks, respecting
   * nested braces.
   */
  private fun splitTargetEntries(targetListContent: String): List<String> =
    splitBraceBlocks(targetListContent).filter { it.startsWith("{TARGETENTRY") }

  /**
   * Splits parenthesized node-tree list content into its top-level `{...}` blocks, respecting
   * nested braces. Returns ALL brace blocks regardless of node type.
   *
   * Uses [nextUnescapedIndexOf] (not a plain `indexOf`) so a literal `{` escaped inside a quoted
   * identifier between blocks — see the class-level note on backslash escaping — is not mistaken
   * for the start of the next block.
   */
  private fun splitBraceBlocks(content: String): List<String> {
    val entries = mutableListOf<String>()
    var index = 0
    while (index < content.length) {
      val braceIndex = nextUnescapedIndexOf(content, '{', index)
      if (braceIndex == -1) break
      val entry = extractBalancedBraces(content, braceIndex) ?: break
      entries.add(entry)
      index = braceIndex + entry.length
    }
    return entries
  }

  /** `:commandType` values — see [parseCommandType]'s KDoc. */
  companion object {
    const val COMMAND_TYPE_DELETE: Int = 4
    const val COMMAND_TYPE_MERGE: Int = 5
  }
}
