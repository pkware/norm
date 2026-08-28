package norm.generator

/**
 * Extracts, from the developer's OWN original SQL text, the expression a [NodeTreeColumnProvenance]
 * points at — the second half of Route D (see the plan's KDoc on [NodeTreeProvenanceResolver] for
 * the first half, which answers only "where", never "what text").
 *
 * [sql] MUST be the query's ORIGINAL text — never [nodeTreeText]'s own sentinel-substituted or
 * deparsed source — so a sentinel literal built only to satisfy `?`'s type during analysis can
 * never leak into generated KDoc; the developer's own token (a literal `?`, or whatever they wrote)
 * is what comes back instead. [nodeTreeText] and [sql] describe the SAME statement but are never
 * interchangeable for TEXT EXTRACTION: only [nodeTreeText] is trusted to say WHERE the expression
 * lives (`provenance`'s CTE name and body position), and only [sql] is trusted for WHAT the
 * expression actually says.
 *
 * This function proves its answer rather than merely computing one: [provenance] alone would let a
 * caller re-run [PgNodeTreeParser.parseCteList]/[parseOutputItemsWithAlias] and read off item
 * [NodeTreeColumnProvenance.bodyPosition] directly, but a text-only re-lex of the CTE body can
 * mis-split or mis-merge an item boundary (an unbalanced-looking comment, a pathological string
 * literal, ...) without the resulting item COUNT changing, silently handing back the WRONG
 * neighboring item's expression instead. Every gate below is required to rule that out; per the
 * "correct or silent" invariant, ANY gate failing returns `null` (no provenance) rather than a wrong
 * expression:
 * - the CTE named [NodeTreeColumnProvenance.cteName] must be findable, by exact name, among every
 *   `WITH` clause reachable in [sql] — not just its own outermost one, since [provenance] can point
 *   into a nested CTE body's own `WITH` (see [allSqlCteDefinitions]) — and, on the node-tree side,
 *   among every `:cteList` reachable in [nodeTreeText] (see [allNodeTreeCteDefinitions]), fold-compared
 *   via [foldIdentifier] against [CteDefinition.rawName], never [CteDefinition.name] — see that
 *   property's own KDoc for why. If that name is declared MORE THAN ONCE across those nesting
 *   depths — an outer CTE shadowed by an inner, differently-bodied one of the same name —
 *   [NodeTreeColumnProvenance] alone cannot say which one [NodeTreeProvenanceResolver] actually
 *   meant, so this gate fails on ANY duplicate rather than guessing.
 * - [parseOutputItemsWithAlias] over that CTE's body text must yield EXACTLY as many top-level items
 *   as [nodeTreeText] has non-junk body target entries for that same CTE. A mis-split/mis-merge that
 *   happens to preserve the total item count survives this check alone.
 * - EVERY position's own name (see [verifiedNameAndExpression]) — not merely
 *   [NodeTreeColumnProvenance.bodyPosition]'s — must fold-match that position's own `:resname`, in
 *   the SAME order. A one-mis-split-plus-
 *   one-mis-merge pair shifts items between its two error points while leaving the total count
 *   alone; checking only the requested position could still pass by accident if the shifted text
 *   happens to look like a legitimate expression. Checking every position closes that gap, PROVIDED
 *   the next gate also holds.
 * - the body's own `:resname`s must be pairwise unique. Without this, a shift could still satisfy
 *   the all-position check above by matching against a DIFFERENT item that happens to share the same
 *   name — uniqueness is what turns "every position matches" into "every position matches ITS OWN
 *   item".
 * - [NodeTreeColumnProvenance.bodyPosition]'s own item must have a VERIFIABLE alias to prove its
 *   position against in the first place: an explicit `AS x`, an implicit (no-`AS`) trailing alias
 *   token (via [splitTrailingImplicitAlias]), or being itself a bare column reference (whose own
 *   name IS what PostgreSQL reports as `:resname` when there is no alias at all). A position whose
 *   `:resname` PostgreSQL auto-generated (e.g. `upper`, `?column?`) has no corresponding text to
 *   verify against, so it can never pass this gate — and per the point above, that failure alone
 *   already fails the WHOLE cross-validation, not merely that one position.
 * - the matched item must not itself be a bare column reference — a CTE body that merely forwards
 *   another column unchanged (`description AS description_upper`) has nothing worth reporting as
 *   "the expression"; echoing a column name back adds no value (the same rule
 *   [TypeRepository.buildTypeProjectionForQuery] already applies to a top-level plain column
 *   reference).
 *
 * @param sql The query's ORIGINAL SQL text (containing the developer's own `?` placeholders, if
 *   any) — never [nodeTreeText]'s sentinel-substituted or deparsed form.
 * @param nodeTreeText The SAME node tree [NodeTreeProvenanceResolver] resolved [provenance] from
 *   (`pg_rewrite.ev_action` or `pg_proc.prosqlbody` text, or a bare `{QUERY ...}` block) — re-parsed
 *   here only via [allNodeTreeCteDefinitions], to read the referenced CTE body's own `:resname`s for
 *   cross-validation. Never re-analyzed for nullability or re-queried against the server: this is the
 *   SAME text already in hand from the one probe round trip that produced [provenance].
 * @param provenance Where [NodeTreeProvenanceResolver] found the expression — a CTE name and
 *   1-based body position — or `null` if it found nothing, in which case this function is not
 *   called at all (there is nothing to extract).
 * @param parser The node-tree parser to use; defaults to a fresh, stateless instance.
 * @return The CTE body's expression text, [collapseCosmeticWhitespace]-normalized and with any
 *   comment [stripComments]-removed, exactly as the SHIPPED `SqlCteOutputExpression.kt` whitelist
 *   emitted for the one shape it resolved — or `null` if any gate above fails.
 */
internal fun resolveNodeTreeProvenanceExpression(
  sql: String,
  nodeTreeText: String,
  provenance: NodeTreeColumnProvenance,
  parser: PgNodeTreeParser = PgNodeTreeParser(),
): String? {
  val cteBodyBlock = allNodeTreeCteDefinitions(nodeTreeText, parser)
    .filter { it.name == provenance.cteName }
    .singleOrNull()
    ?.queryBlock
    ?: return null
  val bodyResultNames = (parser.parseReturningList(cteBodyBlock).ifEmpty { parser.parseTargetList(cteBodyBlock) })
    .filterNot { it.isJunk }
    .sortedBy { it.resultNumber }
    .map { it.resultName }

  val cteInSql = allSqlCteDefinitions(sql)
    .filter { foldIdentifier(it.rawName) == provenance.cteName }
    .singleOrNull()
    ?: return null
  val bodySql = sql.substring(cteInSql.bodyOpenParenthesis + 1, cteInSql.bodyCloseParenthesis)
  val items = parseOutputItemsWithAlias(bodySql)

  if (items.size != bodyResultNames.size) return null
  if (bodyResultNames.distinct().size != bodyResultNames.size) return null

  val verifiedItems = items.map { verifiedNameAndExpression(it) }
  for ((index, verifiedItem) in verifiedItems.withIndex()) {
    val resultName = bodyResultNames[index] ?: return null
    if (verifiedItem == null || verifiedItem.name != resultName) return null
  }

  if (provenance.bodyPosition < 1 || provenance.bodyPosition > items.size) return null
  val matchedItem = items[provenance.bodyPosition - 1]
  // A bare column reference is a chained pass-through with nothing of its own to report -- see this
  // function's own KDoc.
  if (matchedItem.selectItem.columnName != null) return null

  return collapseCosmeticWhitespace(stripComments(verifiedItems[provenance.bodyPosition - 1]!!.expression))
}

/**
 * Every [NodeTreeCteDefinition] reachable from [nodeTreeText], at ANY nesting depth — not just its
 * own outermost `:cteList`, which is all [PgNodeTreeParser.parseCteList] alone returns. Recurses into
 * each CTE body's own `:cteList` in turn, mirroring exactly the scopes [NodeTreeProvenanceResolver]
 * can walk into (a nested `WITH` inside a CTE body shadowing an enclosing one of the same name).
 *
 * @see resolveNodeTreeProvenanceExpression's own KDoc for why a name appearing more than once in the
 *   returned list must fail resolution rather than pick either one.
 */
private fun allNodeTreeCteDefinitions(nodeTreeText: String, parser: PgNodeTreeParser): List<NodeTreeCteDefinition> =
  parser.parseCteList(nodeTreeText).flatMap { definition ->
    listOf(definition) + allNodeTreeCteDefinitions(definition.queryBlock, parser)
  }

/**
 * The SQL-text counterpart of [allNodeTreeCteDefinitions]: every [CteDefinition] reachable from
 * [originalSql], at any nesting depth, found by recursing into each CTE body's own text —
 * [parseCteClause] itself recognizes a nested `WITH` there when the body starts with one — the SAME
 * scopes [allNodeTreeCteDefinitions] recurses into, on the node-tree side.
 *
 * Each returned [CteDefinition]'s [CteDefinition.bodyOpenParenthesis]/[CteDefinition.bodyCloseParenthesis]
 * are always re-based to index into [originalSql] itself — never the (possibly deeply nested) body
 * substring a definition was actually found in — since [resolveNodeTreeProvenanceExpression] always
 * slices [originalSql] directly by whichever [CteDefinition] this function returns.
 *
 * @param scanText the text currently being scanned for a leading `WITH` clause: [originalSql] itself
 *   on the initial (non-recursive) call, or a nested CTE body's own substring on every recursive one.
 * @param scanTextOffset [scanText]'s own start index within [originalSql]; `0` on the initial call.
 */
private fun allSqlCteDefinitions(
  originalSql: String,
  scanText: String = originalSql,
  scanTextOffset: Int = 0,
): List<CteDefinition> {
  val clause = parseCteClause(scanText) ?: return emptyList()
  return clause.definitions.flatMap { definition ->
    val rebased = definition.copy(
      bodyOpenParenthesis = definition.bodyOpenParenthesis + scanTextOffset,
      bodyCloseParenthesis = definition.bodyCloseParenthesis + scanTextOffset,
    )
    val nestedBodyText = scanText.substring(definition.bodyOpenParenthesis + 1, definition.bodyCloseParenthesis)
    val nestedBodyOffset = definition.bodyOpenParenthesis + scanTextOffset + 1
    listOf(rebased) + allSqlCteDefinitions(originalSql, nestedBodyText, nestedBodyOffset)
  }
}

/**
 * One body item's own [name] — the value PostgreSQL would report as `:resname` for it, the one
 * thing [resolveNodeTreeProvenanceExpression] can independently derive from TEXT alone to
 * cross-validate against the node tree's authoritative answer — and its own [expression] text with
 * any alias (explicit or implicit) already removed, ready to be returned as the resolved provenance
 * expression should this turn out to be the matched position.
 *
 * @property name Tried in this order, matching PostgreSQL's own precedence for naming a select-list
 *   item:
 *   1. An explicit `AS alias` ([OutputItemWithAlias.alias]) — folded via [foldIdentifier]'s raw-text
 *      overload, since [OutputItemWithAlias.alias] still carries its own quotes, if any.
 *   2. A bare column reference with NO alias at all ([SelectItem.columnName] non-`null`) —
 *      PostgreSQL exposes such an item under the column's own name. Mutually exclusive with case 3:
 *      `columnName` is non-`null` here only when the item's ENTIRE text matched [COLUMN_REFERENCE],
 *      which by construction leaves no separate trailing token an implicit alias could be.
 *   3. An implicit (no-`AS`) trailing alias token, via [splitTrailingImplicitAlias] over
 *      [SelectItem.expression] — reached only when [OutputItemWithAlias.alias] is `null` and
 *      [SelectItem.columnName] is also `null`, so [SelectItem.expression] is still the FULL,
 *      unsplit item text (see [parseOutputItemsWithAlias]'s own behavior when [extractAlias] finds
 *      no top-level `AS`).
 * @property expression [SelectItem.expression] for cases 1 and 2 above (already alias-free either
 *   way — case 1 by [extractAlias]'s own split, case 2 because the WHOLE item was one bare column
 *   reference with nothing else to strip); for case 3, [ItemAndImplicitAlias.expression] — the SAME
 *   [SelectItem.expression] text with the trailing implicit alias token itself sliced back off, so
 *   an emitted expression for an implicit-alias item never carries its own alias along as if it
 *   were part of the expression (`UPPER(a) y` must emit `UPPER(a)`, never `UPPER(a) y`).
 */
private data class VerifiedBodyItem(val name: String, val expression: String)

/**
 * Computes [item]'s [VerifiedBodyItem], or `null` if [item] has no VERIFIABLE name at all (an
 * unaliased computed expression, whose auto-generated `:resname` — e.g. `upper`, `?column?` —
 * cannot be reconstructed from text without guessing) — see [VerifiedBodyItem.name]'s own KDoc for
 * the three cases tried, in order.
 */
private fun verifiedNameAndExpression(item: OutputItemWithAlias): VerifiedBodyItem? {
  val explicitAlias = item.alias
  if (explicitAlias != null) return VerifiedBodyItem(foldIdentifier(explicitAlias), item.selectItem.expression)
  val columnName = item.selectItem.columnName
  if (columnName != null) {
    return VerifiedBodyItem(foldIdentifier(columnName, item.selectItem.isColumnNameQuoted), item.selectItem.expression)
  }
  val split = splitTrailingImplicitAlias(item.selectItem.expression) ?: return null
  return VerifiedBodyItem(foldIdentifier(split.alias), split.expression)
}
