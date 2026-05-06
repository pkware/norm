package norm.generator

import java.util.logging.Logger

/**
 * Parses PostgreSQL's `pg_node_tree` text (from `pg_rewrite.ev_action`) to determine which result
 * columns may be `null` due to outer joins.
 *
 * The node tree format is PostgreSQL's internal text serialization of its query parse tree. This
 * parser focuses on the `:targetList` section, extracting each non-junk `TARGETENTRY` and
 * inspecting its `:expr` field:
 * - If the expression is a `VAR` node with a non-empty `varnullingrels` bitmapset (i.e., more than
 *   just the base marker `b`), the column is nullable due to an outer join.
 * - If the expression is an aggregate (`AGGREF`), function (`FUNCEXPR`), or other non-`VAR` node,
 *   the column is not nullable from joins (returns `false`).
 *
 * The `varnullingrels` field uses PostgreSQL's bitmapset text format: `(b)` means empty (no
 * nulling relations), while `(b N ...)` with one or more integers means the column can be nulled
 * by those join relations.
 */
internal object NodeTreeNullabilityAnalyzer {

  private val LOGGER = Logger.getLogger(NodeTreeNullabilityAnalyzer::class.java.name)

  private val WHITESPACE = Regex("""\s+""")
  private val NODE_TYPE_PATTERN = Regex("""^\{(\w+)""")
  private val VARNULLING_PATTERN = Regex(""":varnullingrels \(([^)]*)\)""")
  private val RESNO_PATTERN = Regex(""":resno (\d+)""")
  private val RESNAME_PATTERN = Regex(""":resname (\S+)""")
  private val VARNO_PATTERN = Regex(""":varno (\d+)""")
  private val VARATTNO_PATTERN = Regex(""":varattno (\d+)""")
  private val RTEKIND_PATTERN = Regex(""":rtekind (\d+)""")
  private val CTENAME_PATTERN = Regex(""":ctename (\S+)""")

  /** A column's position and outer-join nullability, used for sorting results by column order. */
  private data class ColumnNullability(val resno: Int, val nullable: Boolean)

  /**
   * Extracts per-column outer-join nullability from a `pg_node_tree` text.
   *
   * For simple queries (no CTEs), reads `varnullingrels` from the outer targetList's VAR nodes.
   * For CTEs, when a VAR references a CTE range table entry (`rtekind 6`), the nullability is
   * resolved from the CTE body's own targetList column (which carries the correct `varnullingrels`
   * from the join inside the CTE).
   *
   * @param nodeTreeText the raw text value of `pg_rewrite.ev_action`
   * @return one `Boolean` per result column (in column order), where `true` means the column may
   *   be `null` due to an outer join
   */
  fun extractColumnNullability(nodeTreeText: String): List<Boolean> {
    val targetListContent = extractTargetListContent(nodeTreeText) ?: return emptyList()
    // Build a map from varno → CTE column nullabilities for CTE RTE references.
    // This allows resolving nullability when the outer query references a CTE (rtekind 6).
    val varnoToCteNullability = buildVarnoToCteNullabilityMap(nodeTreeText)
    return resolveTargetListNullability(targetListContent, varnoToCteNullability)
  }

  /**
   * Builds a map from `varno` (1-based RTE index) to CTE column nullabilities for CTE RTEs.
   *
   * Reads `:rtable` entries at the outer QUERY level. For each entry with `rtekind 6` (CTE RTE),
   * finds the matching CTE body in `:cteList` by name, and computes that CTE's column nullabilities.
   *
   * @return A map from `varno` to the CTE's per-column nullable-from-outer-join flags. Only
   *   contains entries for CTE RTEs; other RTE types are absent from the map.
   */
  private fun buildVarnoToCteNullabilityMap(nodeTreeText: String): Map<Int, List<Boolean>> {
    val rtableContent = extractOuterSectionContent(nodeTreeText, ":rtable (") ?: return emptyMap()
    val cteListContent = extractOuterSectionContent(nodeTreeText, ":cteList (") ?: return emptyMap()

    val cteNullabilities = parseCteNullabilities(cteListContent)
    if (cteNullabilities.isEmpty()) return emptyMap()

    return mapCteRangeTableEntries(rtableContent, cteNullabilities)
  }

  /**
   * Parses CTE body nullabilities from the `:cteList` content.
   *
   * CTEs are processed in declaration order so that each CTE body can resolve references to
   * previously declared CTEs. This handles cases like `WITH a AS (SELECT ... LEFT JOIN ...),
   * b AS (SELECT ... FROM a) SELECT ... FROM b` where nullability from `a`'s LEFT JOIN must
   * propagate through `b` to the outer query.
   *
   * @param cteListContent the content between `:cteList (` and its matching `)` at the outer QUERY level
   * @return A map from CTE name to per-column nullable-from-outer-join flags for the CTE's output columns.
   */
  private fun parseCteNullabilities(cteListContent: String): Map<String, List<Boolean>> {
    val resolvedCtes = mutableMapOf<String, List<Boolean>>()
    for (block in splitBraceBlocks(cteListContent)) {
      if (!block.startsWith("{COMMONTABLEEXPR")) continue
      val parsed = parseSingleCteNullability(block, resolvedCtes) ?: continue
      resolvedCtes[parsed.first] = parsed.second
    }
    return resolvedCtes
  }

  /**
   * Parses a single `{COMMONTABLEEXPR ...}` block into a (cteName, column-nullability) pair.
   *
   * @param previouslyResolvedCtes CTE nullabilities resolved from earlier declarations in the same
   *   WITH clause, used when this CTE's body references another CTE.
   * @return A pair of (cteName, columnNullabilities), or `null` if the block cannot be parsed.
   */
  private fun parseSingleCteNullability(
    block: String,
    previouslyResolvedCtes: Map<String, List<Boolean>>,
  ): Pair<String, List<Boolean>>? {
    val cteName = CTENAME_PATTERN.find(block)?.groupValues?.get(1) ?: return null
    // Find the :ctequery {QUERY ...} block within this COMMONTABLEEXPR.
    val cteQueryMarker = ":ctequery {"
    val cteQueryIndex = block.indexOf(cteQueryMarker)
    if (cteQueryIndex == -1) return null
    val cteQueryBraceIndex = cteQueryIndex + cteQueryMarker.length - 1
    val cteQueryContent = extractBalancedBraces(block, cteQueryBraceIndex) ?: return null

    // Build a varno → CTE nullability map for this CTE's own rtable, so references to
    // previously declared CTEs resolve correctly.
    val innerCteMap = buildInnerCteMap(cteQueryContent, previouslyResolvedCtes)

    val cteBodyTargetListContent = extractTargetListContent(cteQueryContent) ?: return null
    return cteName to resolveTargetListNullability(cteBodyTargetListContent, innerCteMap)
  }

  /**
   * Builds a varno → CTE nullability map for a CTE body's own range table.
   *
   * Scans the CTE body's `:rtable` for entries with `rtekind 6` (CTE references) and maps them
   * to the column nullabilities from [previouslyResolvedCtes].
   */
  private fun buildInnerCteMap(
    cteQueryContent: String,
    previouslyResolvedCtes: Map<String, List<Boolean>>,
  ): Map<Int, List<Boolean>> {
    if (previouslyResolvedCtes.isEmpty()) return emptyMap()
    val rtableMarker = ":rtable ("
    val rtableIndex = cteQueryContent.indexOf(rtableMarker)
    if (rtableIndex == -1) return emptyMap()
    val rtableParenIndex = rtableIndex + rtableMarker.length - 1
    val rtableContent = extractBalancedParentheses(cteQueryContent, rtableParenIndex) ?: return emptyMap()

    return mapCteRangeTableEntries(rtableContent, previouslyResolvedCtes)
  }

  /**
   * Scans range table entries for CTE references (`rtekind 6`) and maps each entry's 1-based
   * `varno` to the corresponding CTE's per-column nullabilities from [cteNullabilities].
   *
   * Used by both [buildVarnoToCteNullabilityMap] (outer query's rtable) and [buildInnerCteMap]
   * (CTE body's rtable) to avoid duplicating the rtekind/ctename extraction logic.
   */
  private fun mapCteRangeTableEntries(
    rtableContent: String,
    cteNullabilities: Map<String, List<Boolean>>,
  ): Map<Int, List<Boolean>> = buildMap {
    splitBraceBlocks(rtableContent).forEachIndexed { index, rangeTableEntry ->
      val rtekind = RTEKIND_PATTERN.find(rangeTableEntry)?.groupValues?.get(1)?.toIntOrNull()
        ?: return@forEachIndexed
      if (rtekind != 6) return@forEachIndexed
      val cteName = CTENAME_PATTERN.find(rangeTableEntry)?.groupValues?.get(1) ?: return@forEachIndexed
      val columnNullabilities = cteNullabilities[cteName] ?: return@forEachIndexed
      put(index + 1, columnNullabilities)
    }
  }

  /**
   * Resolves per-column nullability from a `:targetList` content string.
   *
   * Filters out junk entries, determines nullability for each column via [determineNullability],
   * and returns results sorted by column position.
   */
  private fun resolveTargetListNullability(
    targetListContent: String,
    varnoToCteNullability: Map<Int, List<Boolean>>,
  ): List<Boolean> = splitTargetEntries(targetListContent).mapNotNull { entry ->
    if (isResjunk(entry)) return@mapNotNull null
    val resno = extractResno(entry) ?: return@mapNotNull null
    ColumnNullability(resno, determineNullability(entry, resno, varnoToCteNullability))
  }.sortedBy { it.resno }.map { it.nullable }

  /** Delegates to [extractOuterSectionContent] with the `:targetList (` marker. */
  private fun extractTargetListContent(text: String): String? = extractOuterSectionContent(text, ":targetList (")

  /**
   * Finds [marker] at the outermost QUERY level (brace depth 1) and extracts the
   * balanced-parenthesis content that follows the trailing `(` of the marker.
   *
   * The `pg_rewrite.ev_action` text is a `({QUERY ...})` wrapper. Queries with CTEs or subqueries
   * contain nested `{QUERY ...}` blocks whose fields (e.g., `:targetList`, `:rtable`, `:cteList`)
   * appear earlier in the text. Scanning naively via `indexOf` would find an inner occurrence.
   *
   * This method scans inside the outer `{QUERY}` block (brace depth == 1), skipping over any
   * nested `{...}` blocks, to match only occurrences that belong to the outermost query.
   * The marker must end with `(`, and the returned content is the balanced-parenthesis body.
   *
   * @param marker A field marker ending in `(`, e.g. `":targetList ("` or `":rtable ("`.
   * @return the content inside the outer parentheses, or `null` if the marker is not found or the
   *   parentheses are unbalanced
   */
  private fun extractOuterSectionContent(text: String, marker: String): String? {
    check(marker.endsWith("(")) { "marker must end with '(': $marker" }
    // Find the opening brace of the outer QUERY node. The pg_rewrite text starts with "({QUERY ...})".
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
          markerMatchIndex = 0 // any nested brace interrupts marker scanning
        }
        character == '}' -> {
          braceDepth--
          markerMatchIndex = 0
          if (braceDepth == 0) break // finished the outer QUERY block
        }
        braceDepth == 1 ->
          // At depth 1 (directly inside the outer {QUERY}), scan for the marker.
          if (character == marker[markerMatchIndex]) {
            markerMatchIndex++
            if (markerMatchIndex == marker.length) {
              markerStart = index - marker.length + 1
              break
            }
          } else {
            // Restart match; check if current char starts a new match attempt.
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
   * @param includeDelimiters When `true`, the outer delimiter pair is included in the result.
   *   When `false`, only the content between the delimiters is returned.
   * @return The extracted content, or `null` if [startIndex] does not point to [open] or the
   *   delimiters are unbalanced.
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
   * nested braces. Returns ALL brace blocks (regardless of node type).
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

  /** Returns `true` if the TARGETENTRY has `:resjunk true`, meaning it should be skipped. */
  private fun isResjunk(targetEntry: String): Boolean = targetEntry.contains(":resjunk true")

  /**
   * Extracts the `:resno` integer value from a TARGETENTRY block.
   *
   * @return the 1-based column index, or `null` if not found
   */
  private fun extractResno(targetEntry: String): Int? =
    RESNO_PATTERN.find(targetEntry)?.groupValues?.get(1)?.toIntOrNull()

  /**
   * Extracts the `:resname` value from a TARGETENTRY block.
   *
   * @return the column name string, or `null` if not found
   */
  private fun extractResname(targetEntry: String): String? = RESNAME_PATTERN.find(targetEntry)?.groupValues?.get(1)

  /**
   * Extracts the balanced-brace content of the `:expr` field from a TARGETENTRY block.
   *
   * @return the full `{NODE_TYPE ...}` text of the expr, or `null` if not found
   */
  private fun extractExpressionContent(targetEntry: String): String? {
    val expressionMarker = ":expr {"
    val expressionIndex = targetEntry.indexOf(expressionMarker)
    if (expressionIndex == -1) return null
    val braceIndex = expressionIndex + expressionMarker.length - 1
    return extractBalancedBraces(targetEntry, braceIndex)
  }

  /**
   * Determines nullability for a single TARGETENTRY by inspecting its `:expr` field.
   *
   * If the expression's top-level node is a `VAR`:
   * - First checks [varnoToCteNullability]: if the VAR's `varno` maps to a CTE range table entry,
   *   the nullability is resolved from the CTE's own targetList at position `varattno - 1`. This
   *   handles `LEFT JOIN` inside a CTE whose outer query column has `varnullingrels (b)` because
   *   PostgreSQL does not propagate `varnullingrels` through CTE boundaries at rewrite time.
   * - Otherwise reads `varnullingrels` directly: if the bitmapset contains any integer members
   *   beyond the base marker `b`, the column is nullable from an outer join.
   * For any other node type (AGGREF, FUNCEXPR, etc.), returns `false`.
   *
   * On any parse error, logs a warning and returns `true` (safe default).
   *
   * @param targetEntry the full `{TARGETENTRY ...}` text block
   * @param resno the column position, used in warning messages
   * @param varnoToCteNullability map from `varno` to per-column CTE nullabilities for CTE RTEs
   */
  private fun determineNullability(
    targetEntry: String,
    resno: Int,
    varnoToCteNullability: Map<Int, List<Boolean>>,
  ): Boolean = try {
    val expressionContent = extractExpressionContent(targetEntry) ?: return false
    val expressionNodeType = NODE_TYPE_PATTERN.find(expressionContent)?.groupValues?.get(1)
    if (expressionNodeType != "VAR") return false

    // CTE resolution: when a VAR references a CTE RTE (rtekind 6), two sources of nullability
    // must be combined:
    // 1. The outer VAR's own varnullingrels — set when the CTE itself is on the nullable side
    //    of a LEFT/RIGHT/FULL JOIN in the outer query.
    // 2. The CTE body's internal column nullability — set when the CTE body contains outer joins
    //    (PostgreSQL does not propagate internal varnullingrels through CTE boundaries).
    // The column is nullable if EITHER source says so.
    if (varnoToCteNullability.isNotEmpty()) {
      val varno = VARNO_PATTERN.find(expressionContent)?.groupValues?.get(1)?.toIntOrNull()
      val cteNullabilities = varno?.let { varnoToCteNullability[it] }
      if (cteNullabilities != null) {
        // Check the outer VAR's varnullingrels first — if the CTE is on the nullable side
        // of an outer join, the column is nullable regardless of the CTE's internal structure.
        val outerMatch = VARNULLING_PATTERN.find(expressionContent)
        if (outerMatch != null && hasNullingRelations(outerMatch.groupValues[1])) return true
        // Otherwise, check the CTE body's internal column nullability.
        val varattno = VARATTNO_PATTERN.find(expressionContent)?.groupValues?.get(1)?.toIntOrNull()
        if (varattno != null) {
          return cteNullabilities.getOrElse(varattno - 1) { false }
        }
      }
    }

    val match = VARNULLING_PATTERN.find(expressionContent) ?: run {
      logParseWarning(resno, extractResname(targetEntry), targetEntry)
      return true
    }
    hasNullingRelations(match.groupValues[1])
  } catch (cause: RuntimeException) {
    logParseWarning(resno, extractResname(targetEntry), targetEntry, cause)
    true
  }

  /**
   * Returns `true` if the `varnullingrels` bitmapset content contains any integer members.
   *
   * PostgreSQL encodes bitmapsets as `(b)` for empty, or `(b N ...)` where N is one or more space-
   * separated integers. A non-empty set means the column can be nulled by at least one outer join.
   *
   * @param bitmapsetContent The content between the parentheses (e.g., `"b"` or `"b 3"`).
   */
  private fun hasNullingRelations(bitmapsetContent: String): Boolean =
    bitmapsetContent.trim().split(WHITESPACE).any { it.toIntOrNull() != null }

  private fun logParseWarning(resno: Int, resname: String?, targetEntry: String, cause: RuntimeException? = null) {
    val columnDescription = if (resname != null) "$resno ('$resname')" else "$resno"
    val causeDescription = if (cause != null) "\nCAUSE: ${cause.message}" else ""
    LOGGER.warning(
      """
      Norm could not determine outer join nullability for column $columnDescription.
      Defaulting to nullable. Please report at https://github.com/pkware/norm/issues with the text below.
      TARGETENTRY: $targetEntry$causeDescription
      """.trimIndent(),
    )
  }
}
