package norm.generator

import java.util.logging.Logger

/**
 * Parses PostgreSQL `pg_node_tree` text into typed [PgNodeExpression] nodes.
 *
 * The `pg_node_tree` format uses `{NODE_TYPE :field value ...}` notation where values may be
 * integers, booleans, bitmapsets (`(b N ...)`), or nested `{...}` blocks.
 *
 * This class is stateless. Call [parseExpression] with any `{NODE_TYPE ...}` text to get a typed
 * node. Unrecognized node types become [PgNodeExpression.Unknown] and malformed input becomes
 * `Unknown("PARSE_ERROR")` with a warning logged — this class never throws.
 */
internal class PgNodeTreeParser {

  private val logger = Logger.getLogger(PgNodeTreeParser::class.java.name)

  private val nodeTypePattern = Regex("""^\{(\w+)""")
  private val whitespace = Regex("""\s+""")

  /**
   * Parses a single `{NODE_TYPE :field value ...}` expression block into a typed [PgNodeExpression].
   *
   * @param text the full node block text, including surrounding braces
   * @return the parsed expression, or [PgNodeExpression.Unknown] for unrecognized or malformed input
   */
  fun parseExpression(text: String): PgNodeExpression = try {
    val nodeType = nodeTypePattern.find(text)?.groupValues?.get(1)
      ?: return PgNodeExpression.Unknown("PARSE_ERROR").also {
        logger.warning("Could not determine node type from: $text")
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
    logger.warning("Failed to parse node tree expression: ${cause.message}\nInput: $text")
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
    val outerBraceIndex = nodeTreeText.indexOf('{')
    if (outerBraceIndex == -1) return false
    // Search for `:setOperations` at depth=1, excluding the trailing `{` from the marker since `{`
    // is processed as a brace character and never reaches the character-comparison branch.
    val marker = ":setOperations "
    var braceDepth = 0
    var index = outerBraceIndex
    var markerMatchIndex = 0
    while (index < nodeTreeText.length) {
      val character = nodeTreeText[index]
      when {
        // When we've matched `:setOperations ` and encounter `{` at depth=1, this is a set
        // operation query. The `{` opens the SETOPERATIONSTMT node value.
        character == '{' && braceDepth == 1 && markerMatchIndex == marker.length -> return true
        character == '{' -> {
          braceDepth++
          markerMatchIndex = 0
        }
        character == '}' -> {
          braceDepth--
          markerMatchIndex = 0
          if (braceDepth == 0) break
        }
        braceDepth == 1 && markerMatchIndex == marker.length ->
          // Marker already fully matched. Any non-space character that isn't `{` means value is
          // not a brace block (e.g., `<>` for empty). Reset so the matched state doesn't persist.
          if (character != ' ') markerMatchIndex = if (character == marker[0]) 1 else 0
        braceDepth == 1 && character == marker[markerMatchIndex] -> markerMatchIndex++
        braceDepth == 1 -> markerMatchIndex = if (character == marker[0]) 1 else 0
        else -> markerMatchIndex = 0
      }
      index++
    }
    return false
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
   * Returns the set of `(varno, varattno)` pairs for GROUP BY key columns in a query that uses
   * GROUPING SETS, CUBE, or ROLLUP.
   *
   * PostgreSQL 18 introduced a `*GROUP*` range table entry (rtekind 9) that acts as a nullability
   * barrier: target list VARs reference the GROUP RTE rather than the base table directly, so they
   * fall outside [parseRangeTable]'s base-table map and are treated as nullable. PostgreSQL 16 and
   * 17 do not have this RTE — their target list VARs point straight to the base table, which would
   * cause [parseRangeTable] to resolve them as NOT NULL even when GROUPING SETS/CUBE/ROLLUP can
   * produce `null` for any grouping key.
   *
   * This method identifies those VARs by correlating `:groupClause` (`tleSortGroupRef` values)
   * with `:targetList` (`ressortgroupref` values) so the caller can force them to nullable.
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text
   * @return `(varno, varattno)` pairs for GROUP BY key columns; empty if none or if the target
   *   list entries use non-VAR expressions for GROUP BY keys
   */
  fun parseGroupingKeyVars(nodeTreeText: String): Set<Pair<Int, Int>> {
    val groupClauseContent = extractOuterSectionContent(nodeTreeText, ":groupClause (") ?: return emptySet()
    val sortGroupRefs = buildSet {
      splitBraceBlocks(groupClauseContent).forEach { clauseBlock ->
        extractIntField(clauseBlock, ":tleSortGroupRef")?.let { add(it) }
      }
    }
    if (sortGroupRefs.isEmpty()) return emptySet()

    val targetListContent = extractOuterSectionContent(nodeTreeText, ":targetList (") ?: return emptySet()
    return buildSet {
      splitBraceBlocks(targetListContent).forEach { entryBlock ->
        val sortGroupRef = extractIntField(entryBlock, ":ressortgroupref") ?: return@forEach
        if (sortGroupRef == 0 || sortGroupRef !in sortGroupRefs) return@forEach
        val exprBlock = extractFieldExpression(entryBlock, ":expr") ?: return@forEach
        if (!exprBlock.startsWith("{VAR ")) return@forEach
        val varno = extractIntField(exprBlock, ":varno") ?: return@forEach
        val varattno = extractIntField(exprBlock, ":varattno") ?: return@forEach
        add(varno to varattno)
      }
    }
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
   * Extracts the `:rtable` section and returns a map from 1-based `varno` to the CTE name
   * (`:ctename`) for each range table entry with `rtekind 6`.
   *
   * This is the CTE counterpart to [parseRangeTable] (which handles `rtekind 0` base tables)
   * and [parseSubqueryRangeTable] (which handles `rtekind 1` subqueries).
   *
   * @param nodeTreeText the raw `pg_rewrite.ev_action` text (or a bare `{QUERY ...}` block)
   * @return a map from 1-based varno to CTE name, or an empty map if no CTE RTEs are found
   */
  fun parseCteRangeTableEntries(nodeTreeText: String): Map<Int, String> {
    val rtableContent = extractOuterSectionContent(nodeTreeText, ":rtable (") ?: return emptyMap()
    return buildMap {
      splitBraceBlocks(rtableContent).forEachIndexed { index, rangeTableEntry ->
        val rtekind = extractIntField(rangeTableEntry, ":rtekind") ?: return@forEachIndexed
        if (rtekind != 6) return@forEachIndexed
        val cteName = extractStringField(rangeTableEntry, ":ctename") ?: return@forEachIndexed
        put(index + 1, cteName)
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

  fun parseReturningList(nodeTreeText: String): List<TargetEntry> {
    val content = extractOuterSectionContent(nodeTreeText, ":returningList (") ?: return emptyList()
    return splitTargetEntries(content).mapNotNull { entry -> parseTargetEntry(entry) }
  }

  private fun parseVar(text: String): PgNodeExpression.Var {
    val varno = extractIntField(text, ":varno")
      ?: error("Missing :varno in VAR node")
    val varattno = extractIntField(text, ":varattno")
      ?: error("Missing :varattno in VAR node")
    val nullingRelations = extractBitmapset(text, ":varnullingrels")
    return PgNodeExpression.Var(varno = varno, varattno = varattno, nullingRelations = nullingRelations)
  }

  private fun parseConst(text: String): PgNodeExpression.Const {
    val isNull = extractBoolField(text, ":constisnull") ?: false
    return PgNodeExpression.Const(isNull = isNull)
  }

  private fun parseFuncExpr(text: String): PgNodeExpression.FuncExpr {
    val functionOid = extractIntField(text, ":funcid") ?: error("Missing :funcid in FUNCEXPR node")
    return PgNodeExpression.FuncExpr(functionOid = functionOid, arguments = parseArgList(text, ":args"))
  }

  private fun parseOpExpr(text: String): PgNodeExpression.OpExpr {
    val operatorFunctionOid = extractIntField(text, ":opfuncid") ?: error("Missing :opfuncid in OPEXPR node")
    return PgNodeExpression.OpExpr(operatorFunctionOid = operatorFunctionOid, arguments = parseArgList(text, ":args"))
  }

  private fun parseScalarArrayOpExpr(text: String): PgNodeExpression.ScalarArrayOpExpr {
    val operatorFunctionOid = extractIntField(text, ":opfuncid") ?: error("Missing :opfuncid in SCALARARRAYOPEXPR node")
    return PgNodeExpression.ScalarArrayOpExpr(
      operatorFunctionOid = operatorFunctionOid,
      arguments = parseArgList(text, ":args"),
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
    // For ANY sublinks (IN operator), extract the outer operand from the testexpr's first argument.
    val outerOperand = if (subLinkType == 2 || subLinkType == 3) {
      extractFieldExpression(text, ":testexpr")?.let { testExpr ->
        extractArgListSection(testExpr, ":args")?.let { splitBraceBlocks(it).firstOrNull()?.let(::parseExpression) }
      }
    } else {
      null
    }
    return PgNodeExpression.SubLink(subLinkType = subLinkType, outerOperand = outerOperand)
  }

  private fun parseCaseExpr(text: String): PgNodeExpression.CaseExpr {
    val caseWhenBlocks = extractArgListSection(text, ":args")
    val resultExpressions = if (caseWhenBlocks == null) {
      emptyList()
    } else {
      splitBraceBlocks(caseWhenBlocks).mapNotNull { block ->
        val result = extractFieldExpression(block, ":result")
        if (result == null) logger.warning("CASEWHEN block missing :result field, skipping: $block")
        result
      }.map { parseExpression(it) }
    }
    val defaultResult = extractFieldExpression(text, ":defresult")?.let { parseExpression(it) }
    return PgNodeExpression.CaseExpr(resultExpressions = resultExpressions, defaultResult = defaultResult)
  }

  private fun parseBoolExpr(text: String): PgNodeExpression.BoolExpr =
    PgNodeExpression.BoolExpr(arguments = parseArgList(text, ":args"))

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
    return PgNodeExpression.NullTest(argument = parseExpression(argument))
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
   * Extracts a named `{...}` expression block from a field like `:fieldName {NODETYPE ...}`.
   *
   * Returns `null` if the field is absent or its value is not a brace block.
   */
  private fun extractFieldExpression(text: String, fieldName: String): String? {
    val marker = "$fieldName {"
    val markerIndex = text.indexOf(marker)
    if (markerIndex == -1) return null
    val braceIndex = markerIndex + marker.length - 1
    return extractBalancedBraces(text, braceIndex)
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

    val resultNumber = extractIntField(text, ":resno") ?: return null
    val resultName = Regex(""":resname (\S+)""").find(text)?.groupValues?.get(1)
    val isJunk = text.contains(":resjunk true")
    return TargetEntry(
      expression = expression,
      resultName = resultName,
      resultNumber = resultNumber,
      isJunk = isJunk,
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
    Regex("""$fieldName (-?\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()

  /**
   * Extracts a boolean field value from a node block.
   *
   * @param text the full node block text
   * @param fieldName the field name including the leading colon, e.g. `":constisnull"`
   * @return the boolean value, or `null` if the field is absent
   */
  private fun extractBoolField(text: String, fieldName: String): Boolean? =
    Regex("""$fieldName (true|false)""").find(text)?.groupValues?.get(1)?.let { it == "true" }

  /**
   * Extracts a non-whitespace string field value from a node block.
   *
   * @param text the full node block text
   * @param fieldName the field name including the leading colon, e.g. `":ctename"`
   * @return the string value (first contiguous non-whitespace run after the field name and space),
   *   or `null` if the field is absent
   */
  private fun extractStringField(text: String, fieldName: String): String? =
    Regex("""$fieldName (\S+)""").find(text)?.groupValues?.get(1)

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
    val content = Regex("""$fieldName \(([^)]*)\)""").find(text)?.groupValues?.get(1) ?: return emptySet()
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
    val outerBraceIndex = text.indexOf('{')
    if (outerBraceIndex == -1) return null

    var braceDepth = 0
    var index = outerBraceIndex
    var markerMatchIndex = 0
    var markerStart = -1

    while (index < text.length) {
      val character = text[index]
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
            if (markerMatchIndex == marker.length) {
              markerStart = index - marker.length + 1
              break
            }
          } else {
            markerMatchIndex = if (character == marker[0]) 1 else 0
          }
        else -> markerMatchIndex = 0
      }
      index++
    }

    if (markerStart == -1) return null
    val openParenthesisIndex = markerStart + marker.length - 1
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
   */
  private fun splitBraceBlocks(content: String): List<String> {
    val entries = mutableListOf<String>()
    var index = 0
    while (index < content.length) {
      val braceIndex = content.indexOf('{', index)
      if (braceIndex == -1) break
      val entry = extractBalancedBraces(content, braceIndex) ?: break
      entries.add(entry)
      index = braceIndex + entry.length
    }
    return entries
  }
}
