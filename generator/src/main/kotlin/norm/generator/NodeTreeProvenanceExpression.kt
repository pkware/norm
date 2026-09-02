package norm.generator

/**
 * Extracts, from the developer's own original SQL text, the expression a [NodeTreeColumnProvenance]
 * points at.
 *
 * [sql] must be the query's ORIGINAL text — never [nodeTreeText]'s sentinel-substituted or deparsed
 * form — so a sentinel literal built only to satisfy `?`'s type during analysis can never leak into
 * generated KDoc. [nodeTreeText] is trusted only for WHERE the expression lives ([provenance]'s CTE
 * name and body position); [sql] is trusted only for WHAT it says.
 *
 * This proves its answer rather than merely computing one: a text-only re-lex of the CTE body can
 * mis-split or mis-merge an item boundary (an unbalanced-looking comment, a pathological string
 * literal) without the item count changing, silently handing back a neighboring item's expression.
 * Every gate below returns `null` (no provenance) instead of risking a wrong one:
 * - the CTE [provenance] points at is found by replaying [NodeTreeColumnProvenance.hops] step by
 *   step (see [scopedNodeTreeCteQueryBlock] and [scopedSqlCteDefinition]), never by a flat, name-only
 *   search — so a nested `WITH` that shadows an outer CTE of the same name resolves against the exact
 *   declaration [NodeTreeProvenanceResolver] walked to, not merely a same-named one elsewhere (#238).
 * - [parseOutputItemsWithAlias] over that CTE's body must yield exactly as many top-level items as
 *   [nodeTreeText] has non-junk body target entries for that CTE.
 * - every position's own name — not merely [provenance]'s — must fold-match that position's own
 *   `:resname`, in order, and those `:resname`s must be pairwise unique; otherwise a mis-split that
 *   happens to preserve the item count could pass by shifting text between two positions sharing a
 *   name.
 * - [provenance]'s own item must have a verifiable name: an explicit `AS x`, an implicit trailing
 *   alias token, or being itself a bare column reference — see [verifiedItem].
 * - [provenance]'s own matched item must not have been verified only via an implicit alias: its
 *   complete text is legal only as a SELECT-LIST ITEM (`UPPER(name) y`), never as a standalone
 *   EXPRESSION — the context a `@property` source reference renders it in — because whether a
 *   trailing bare word is an alias or a required operand (`ts AT TIME ZONE timezone`) cannot be
 *   decided from text alone. An explicit `AS` alias has no such problem: [extractAlias] already
 *   splits it off before the item's expression is formed.
 * - the matched item must not be a bare column pass-through (see [isBareColumnPassThrough]) — a CTE
 *   body that merely forwards a column unchanged has nothing of its own to report, the same rule
 *   [TypeRepository.buildTypeProjectionForQuery] applies to a top-level plain column reference.
 *
 * @param sql The query's original SQL text (containing the developer's own `?` placeholders, if
 *   any) — never [nodeTreeText]'s sentinel-substituted or deparsed form.
 * @param nodeTreeText The same node tree [NodeTreeProvenanceResolver] resolved [provenance] from
 *   (`pg_rewrite.ev_action` or `pg_proc.prosqlbody` text, or a bare `{QUERY ...}` block).
 * @param provenance Where [NodeTreeProvenanceResolver] found the expression; this function is only
 *   called when it is non-`null`.
 * @param parser The node-tree parser to use; defaults to a fresh, stateless instance.
 * @return The CTE body's expression text, [collapseCosmeticWhitespace]-normalized and with any
 *   comment [stripComments]-removed, exactly as the developer wrote it — or `null` if any gate above
 *   fails.
 */
internal fun resolveNodeTreeProvenanceExpression(
  sql: String,
  nodeTreeText: String,
  provenance: NodeTreeColumnProvenance,
  parser: PgNodeTreeParser = PgNodeTreeParser(),
): String? {
  val cteBodyBlock = scopedNodeTreeCteQueryBlock(nodeTreeText, provenance.hops, parser) ?: return null
  val bodyResultNames = (parser.parseReturningList(cteBodyBlock).ifEmpty { parser.parseTargetList(cteBodyBlock) })
    .filterNot { it.isJunk }
    .sortedBy { it.resultNumber }
    .map { it.resultName }

  val cteInSql = scopedSqlCteDefinition(sql, provenance.hops) ?: return null
  val bodySql = sql.substring(cteInSql.bodyOpenParenthesis + 1, cteInSql.bodyCloseParenthesis)
  val items = parseOutputItemsWithAlias(bodySql)

  if (items.size != bodyResultNames.size) return null
  if (bodyResultNames.distinct().size != bodyResultNames.size) return null

  val verifiedItems = items.mapIndexed { index, item ->
    val resultName = bodyResultNames[index] ?: return null
    verifiedItem(item, resultName) ?: return null
  }

  if (provenance.bodyPosition < 1 || provenance.bodyPosition > items.size) return null
  val matchedItem = items[provenance.bodyPosition - 1]
  // A bare column reference is a chained pass-through with nothing of its own to report -- see this
  // function's own KDoc.
  if (isBareColumnPassThrough(matchedItem)) return null

  val matched = verifiedItems[provenance.bodyPosition - 1]
  // The matched item's complete text (including its own implicit alias) is only ever a legal
  // select-list item, never a legal standalone expression -- see this function's own KDoc.
  if (matched.matchKind == AliasMatchKind.IMPLICIT_ALIAS) return null

  return collapseCosmeticWhitespace(stripComments(matched.expression))
}

/**
 * Whether [item] is nothing more than a bare column reference forwarded under a different name — an
 * explicit `AS` alias on a plain column, or the same shape spelled with an implicit alias
 * (`description dx`) — in which case there is nothing of [item]'s own to report as "the expression".
 *
 * The implicit-alias branch here is the one place [splitTrailingImplicitAlias]'s guessed split
 * decides something about [item], rather than merely verifying a `:resname` match (see
 * [verifiedItem]). Safe because a wrong guess only makes this function more conservative (reporting
 * a pass-through that wasn't one, so the caller reports nothing) or less conservative (missing a
 * real pass-through, which then falls through to [verifiedItem]'s own [AliasMatchKind.IMPLICIT_ALIAS]
 * decline instead) — never a wrong value emitted.
 */
private fun isBareColumnPassThrough(item: OutputItemWithAlias): Boolean {
  if (item.selectItem.columnName != null) return true
  if (item.alias != null) return false
  val implicitAliasStripped = splitTrailingImplicitAlias(item.selectItem.expression)?.expression ?: return false
  return parseColumnReference(implicitAliasStripped).columnName != null
}

/**
 * Which of PostgreSQL's three ways of naming a select-list item verified [VerifiedItem.expression]
 * against its `:resname`. [resolveNodeTreeProvenanceExpression] emits the expression as-is for
 * [EXPLICIT_ALIAS]/[BARE_COLUMN_REFERENCE] (the latter unreachable there in practice, since
 * [isBareColumnPassThrough] already declines a bare column reference first), but declines
 * [IMPLICIT_ALIAS] outright — see that function's own KDoc for why.
 */
private enum class AliasMatchKind { EXPLICIT_ALIAS, BARE_COLUMN_REFERENCE, IMPLICIT_ALIAS }

/**
 * @property expression The item's complete, uncut text — see [resolveNodeTreeProvenanceExpression]
 *   for why cutting is never attempted.
 * @property matchKind Which naming rule verified [expression] against its `:resname`.
 */
private data class VerifiedItem(val expression: String, val matchKind: AliasMatchKind)

/**
 * [item]'s complete, uncut text paired with which rule verified its name against [resultName] (the
 * node tree's authoritative `:resname` for this position), or `null` if none did. Tried in
 * PostgreSQL's own precedence order for naming a select-list item:
 *
 * 1. An explicit `AS alias` — folded via [foldIdentifier]'s raw-text overload, since
 *    [OutputItemWithAlias.alias] still carries its own quotes, if any.
 * 2. A bare column reference with no alias at all — PostgreSQL exposes such an item under the
 *    column's own name.
 * 3. Neither of the above: the item's text may still end with an implicit (no-`AS`) alias token that
 *    happens to be the same string PostgreSQL reports for a keyword-operand shape's own trailing
 *    operand (`ts AT TIME ZONE timezone`'s `:resname` is `timezone`, whether or not `timezone` is
 *    really an alias). [splitTrailingImplicitAlias] is used only to find that trailing token and
 *    fold-compare it against [resultName]; the split-off expression half of its result is never read.
 */
private fun verifiedItem(item: OutputItemWithAlias, resultName: String): VerifiedItem? {
  val alias = item.alias
  if (alias != null) {
    // resultName comes from the server and is already truncated. Truncating after the fold, not
    // before: the raw alias still carries its quotes, and those bytes are not part of the name.
    return item.selectItem.expression.takeIf { truncateIdentifier(foldIdentifier(alias)) == resultName }
      ?.let { VerifiedItem(it, AliasMatchKind.EXPLICIT_ALIAS) }
  }

  val columnName = item.selectItem.columnName
  if (columnName != null) {
    // parseColumnReference already truncated columnName.
    val folded = foldIdentifier(columnName, item.selectItem.isColumnNameQuoted)
    return item.selectItem.expression.takeIf { folded == resultName }
      ?.let { VerifiedItem(it, AliasMatchKind.BARE_COLUMN_REFERENCE) }
  }

  val implicitAlias = splitTrailingImplicitAlias(item.selectItem.expression)?.alias ?: return null
  return item.selectItem.expression.takeIf { truncateIdentifier(foldIdentifier(implicitAlias)) == resultName }
    ?.let { VerifiedItem(it, AliasMatchKind.IMPLICIT_ALIAS) }
}

/**
 * Replays [hops] against [nodeTreeText]'s own `:cteList` structure, maintaining the same scope-stack
 * shape [NodeTreeProvenanceResolver.resolveVar] built while producing [hops], and returns the last
 * hop's CTE body (`:ctequery` block) — the exact declaration [NodeTreeProvenanceResolver] resolved
 * against, never merely a same-named one elsewhere in the tree.
 *
 * Mirrors [NodeTreeProvenanceResolver.resolveVar]'s scope-stack bookkeeping exactly: entering a hop's
 * CTE body pushes that body's own `:cteList` onto `scopeStack.drop(hop.ctelevelsup)`, not the full
 * accumulated stack, because a hop into a sibling CTE is not a nesting level.
 *
 * @return `null` if any hop's [CteHop.ctelevelsup] addresses a scope-stack depth that does not exist,
 *   or its [CteHop.name] is not declared in that scope's `:cteList` — neither can happen for [hops]
 *   genuinely produced against this same [nodeTreeText], but the caller still treats either as
 *   "cannot resolve" rather than assume it.
 */
private fun scopedNodeTreeCteQueryBlock(nodeTreeText: String, hops: List<CteHop>, parser: PgNodeTreeParser): String? {
  var scopeStack = listOf(parser.parseCteList(nodeTreeText).associateBy { it.name })
  var resolvedQueryBlock: String? = null
  for (hop in hops) {
    val scope = scopeStack.getOrNull(hop.ctelevelsup) ?: return null
    val definition = scope[hop.name] ?: return null
    resolvedQueryBlock = definition.queryBlock
    val ownScope = parser.parseCteList(definition.queryBlock).associateBy { it.name }
    scopeStack = listOf(ownScope) + scopeStack.drop(hop.ctelevelsup)
  }
  return resolvedQueryBlock
}

/**
 * The SQL-text counterpart of [scopedNodeTreeCteQueryBlock]: replays [hops] against [sql]'s own
 * lexically-nested `WITH` clauses, maintaining an analogous scope stack of [CteDefinition] lists, and
 * returns the last hop's [CteDefinition].
 *
 * Every returned (and intermediate) [CteDefinition]'s [CteDefinition.bodyOpenParenthesis]/
 * [CteDefinition.bodyCloseParenthesis] are re-based to index into [sql] itself — never the (possibly
 * nested) body substring a definition was found in — since [resolveNodeTreeProvenanceExpression]
 * slices [sql] directly by whichever [CteDefinition] this function returns.
 *
 * A single `WITH` clause can never legally declare the same name twice (PostgreSQL rejects that at
 * parse time), so lookup within one scope level never needs a uniqueness check: the scope stack
 * itself supplies the disambiguation a flat, whole-statement name search cannot.
 *
 * @return `null` if any hop's [CteHop.ctelevelsup] addresses a scope-stack depth that does not exist,
 *   or its [CteHop.name] does not fold-match ([foldIdentifier] against [CteDefinition.rawName]) any
 *   definition in that scope
 */
private fun scopedSqlCteDefinition(sql: String, hops: List<CteHop>): CteDefinition? {
  var scopeStack = listOf(rebasedCteDefinitions(sql, 0))
  var resolvedDefinition: CteDefinition? = null
  for (hop in hops) {
    val scope = scopeStack.getOrNull(hop.ctelevelsup) ?: return null
    // hop.name comes from the node tree and is already truncated.
    val definition = scope.filter { truncateIdentifier(foldIdentifier(it.rawName)) == hop.name }.singleOrNull()
      ?: return null
    resolvedDefinition = definition
    val bodyOffset = definition.bodyOpenParenthesis + 1
    val bodyText = sql.substring(bodyOffset, definition.bodyCloseParenthesis)
    // drop, not prepend-onto-the-full-stack: see scopedNodeTreeCteQueryBlock's KDoc, which this
    // mirrors exactly.
    scopeStack = listOf(rebasedCteDefinitions(bodyText, bodyOffset)) + scopeStack.drop(hop.ctelevelsup)
  }
  return resolvedDefinition
}

/**
 * [parseCteClause]'s own definitions for [text], with every [CteDefinition.bodyOpenParenthesis]/
 * [CteDefinition.bodyCloseParenthesis] shifted by [offset] so they index into the original SQL
 * string [text] was sliced from, rather than [text] itself. See [scopedSqlCteDefinition] for why
 * every level of its scope stack needs definitions rebased this way.
 */
private fun rebasedCteDefinitions(text: String, offset: Int): List<CteDefinition> =
  parseCteClause(text)?.definitions?.map {
    it.copy(
      bodyOpenParenthesis = it.bodyOpenParenthesis + offset,
      bodyCloseParenthesis =
      it.bodyCloseParenthesis + offset,
    )
  } ?: emptyList()
