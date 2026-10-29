package norm.generator

/**
 * Extracts, from the developer's own original SQL text, the expression a [NodeTreeColumnProvenance]
 * points at.
 *
 * [sql] must be the query's original text — never [nodeTreeText]'s sentinel-substituted or deparsed
 * form. [nodeTreeText] is trusted only for where the expression lives ([provenance]'s CTE name and
 * body position); [sql] is trusted only for what it says.
 *
 * Returns `null` if any of these checks fails:
 * - the CTE [provenance] points at is resolved by replaying [NodeTreeColumnProvenance.hops] step by
 *   step ([scopedNodeTreeCteQueryBlock], [scopedSqlCteDefinition]), so a nested `WITH` that shadows an
 *   outer CTE of the same name resolves against the exact declaration, not merely a same-named one
 *   elsewhere.
 * - [parseOutputItemsWithAlias] over that CTE's body yields exactly as many top-level items as
 *   [nodeTreeText] has non-junk body target entries for that CTE, and every position's own `:resname`
 *   fold-matches, in order, with `:resname`s pairwise unique.
 * - [provenance]'s own item has a verifiable name: an explicit `AS x`, an implicit trailing alias
 *   token, or being itself a bare column reference (see [verifiedItem]).
 * - the matched item was not verified only via an implicit alias, which this function declines.
 * - the matched item is not a bare column pass-through (see [isBareColumnPassThrough]).
 *
 * @param sql The query's original SQL text (containing the developer's own `?` placeholders, if
 *   any) — never [nodeTreeText]'s sentinel-substituted or deparsed form.
 * @param nodeTreeText The same node tree [NodeTreeProvenanceResolver] resolved [provenance] from
 *   (`pg_rewrite.ev_action` or `pg_proc.prosqlbody` text, or a bare `{QUERY ...}` block).
 * @param provenance Where [NodeTreeProvenanceResolver] found the expression; this function is only
 *   called when it is non-`null`.
 * @param parser The node-tree parser to use; defaults to a fresh, stateless instance.
 * @return The CTE body's expression text as [SelectItem.normalizedExpression] builds it, without comments and with
 *   cosmetic whitespace collapsed, otherwise as the developer wrote it — or `null` if any check above fails.
 */
internal fun resolveNodeTreeProvenanceExpression(
  sql: String,
  nodeTreeText: String,
  provenance: NodeTreeColumnProvenance,
  parser: PgNodeTreeParser = PgNodeTreeParser(),
): String? {
  val cteBodyBlock = scopedNodeTreeCteQueryBlock(nodeTreeText, provenance.hops, parser) ?: return null
  val bodyResultNames = parser.resultProjection(cteBodyBlock).entries.map { it.resultName }

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
  if (isBareColumnPassThrough(matchedItem)) return null

  val matched = verifiedItems[provenance.bodyPosition - 1]
  if (matched.matchKind == AliasMatchKind.IMPLICIT_ALIAS) return null

  return matched.selectItem.normalizedExpression
}

/**
 * Whether [item] is nothing more than a bare column reference forwarded under a different name — an
 * alias, explicit or implicit (`description dx`), on a plain column — in which case there is nothing of
 * [item]'s own to report as "the expression".
 */
private fun isBareColumnPassThrough(item: OutputItemWithAlias): Boolean = item.selectItem.columnName != null

/**
 * Which of PostgreSQL's ways of naming a select-list item verified [VerifiedItem.selectItem] against its
 * `:resname`. [resolveNodeTreeProvenanceExpression] emits the expression for [EXPLICIT_ALIAS] and
 * [BARE_COLUMN_REFERENCE] (the latter unreachable there in practice, since [isBareColumnPassThrough]
 * already declines a bare column reference first), but declines [IMPLICIT_ALIAS] outright.
 */
private enum class AliasMatchKind { EXPLICIT_ALIAS, BARE_COLUMN_REFERENCE, IMPLICIT_ALIAS }

/**
 * @property selectItem The item whose name was verified.
 * @property matchKind Which naming rule verified [selectItem] against its `:resname`.
 */
private data class VerifiedItem(val selectItem: SelectItem, val matchKind: AliasMatchKind)

/**
 * [item] paired with which rule verified its name against [resultName] (the node tree's authoritative
 * `:resname` for this position), or `null` if none did. Tried in PostgreSQL's own precedence order for
 * naming a select-list item:
 *
 * 1. An alias — converted via [logicalIdentifier], since [OutputItemWithAlias.alias] still carries its
 *    own quotes, if any. An implicit (no-`AS`) alias is verified the same way and reported as
 *    [AliasMatchKind.IMPLICIT_ALIAS].
 * 2. A bare column reference with no alias at all — PostgreSQL exposes such an item under the
 *    column's own name.
 */
private fun verifiedItem(item: OutputItemWithAlias, resultName: String): VerifiedItem? {
  val alias = item.alias
  if (alias != null) {
    // resultName comes from the server and is already truncated. Folding before truncating: the
    // raw alias still carries its quotes, and those bytes are not part of the name.
    val matchKind = if (item.isAliasExplicit) AliasMatchKind.EXPLICIT_ALIAS else AliasMatchKind.IMPLICIT_ALIAS
    return VerifiedItem(item.selectItem, matchKind).takeIf { logicalIdentifier(alias) == resultName }
  }

  val columnName = item.selectItem.columnName ?: return null
  val folded = foldIdentifier(columnName, item.selectItem.isColumnNameQuoted)
  return VerifiedItem(item.selectItem, AliasMatchKind.BARE_COLUMN_REFERENCE).takeIf { folded == resultName }
}

/**
 * Replays [hops] against [nodeTreeText]'s own `:cteList` structure and returns the last hop's CTE
 * body (`:ctequery` block) — the exact declaration [NodeTreeProvenanceResolver] resolved against,
 * never merely a same-named one elsewhere in the tree.
 *
 * See [CteScopeStack] for the scope-stack bookkeeping this maintains as each hop is replayed.
 *
 * @return `null` if any hop's [CteHop.ctelevelsup] addresses a scope-stack depth that does not exist,
 *   or its [CteHop.name] is not declared in that scope's `:cteList`.
 */
private fun scopedNodeTreeCteQueryBlock(nodeTreeText: String, hops: List<CteHop>, parser: PgNodeTreeParser): String? {
  var scopeStack = CteScopeStack(parser.parseCteList(nodeTreeText).associateBy { it.name })
  var resolvedQueryBlock: String? = null
  for (hop in hops) {
    val scope = scopeStack.frameAt(hop.ctelevelsup) ?: return null
    val definition = scope[hop.name] ?: return null
    resolvedQueryBlock = definition.queryBlock
    val ownScope = parser.parseCteList(definition.queryBlock).associateBy { it.name }
    scopeStack = scopeStack.entering(hop.ctelevelsup, ownScope)
  }
  return resolvedQueryBlock
}

/**
 * The SQL-text counterpart of [scopedNodeTreeCteQueryBlock]: replays [hops] against [sql]'s own
 * lexically-nested `WITH` clauses, maintaining an analogous scope stack of [CteDefinition] lists, and
 * returns the last hop's [CteDefinition].
 *
 * Every returned (and intermediate) [CteDefinition]'s [CteDefinition.bodyOpenParenthesis]/
 * [CteDefinition.bodyCloseParenthesis] are re-based to index into [sql] itself, never the (possibly
 * nested) body substring a definition was found in, since [resolveNodeTreeProvenanceExpression]
 * slices [sql] directly by whichever [CteDefinition] this function returns.
 *
 * @return `null` if any hop's [CteHop.ctelevelsup] addresses a scope-stack depth that does not exist,
 *   or its [CteHop.name] does not match ([CteDefinition.name], PostgreSQL's own logical identifier
 *   value) any definition in that scope
 */
private fun scopedSqlCteDefinition(sql: String, hops: List<CteHop>): CteDefinition? {
  var scopeStack = CteScopeStack(rebasedCteDefinitions(sql, 0))
  var resolvedDefinition: CteDefinition? = null
  for (hop in hops) {
    val scope = scopeStack.frameAt(hop.ctelevelsup) ?: return null
    // hop.name comes from the node tree and is already truncated; CteDefinition.name is already
    // the logical, folded, truncated value, so a direct comparison is exact.
    val definition = scope.filter { it.name == hop.name }.singleOrNull() ?: return null
    resolvedDefinition = definition
    val bodyOffset = definition.bodyOpenParenthesis + 1
    val bodyText = sql.substring(bodyOffset, definition.bodyCloseParenthesis)
    scopeStack = scopeStack.entering(hop.ctelevelsup, rebasedCteDefinitions(bodyText, bodyOffset))
  }
  return resolvedDefinition
}

/**
 * [parseCteClause]'s own definitions for [text], with every [CteDefinition.bodyOpenParenthesis]/
 * [CteDefinition.bodyCloseParenthesis] shifted by [offset] so they index into the original SQL
 * string [text] was sliced from.
 */
private fun rebasedCteDefinitions(text: String, offset: Int): List<CteDefinition> =
  parseCteClause(text)?.definitions?.map {
    it.copy(
      bodyOpenParenthesis = it.bodyOpenParenthesis + offset,
      bodyCloseParenthesis =
      it.bodyCloseParenthesis + offset,
    )
  } ?: emptyList()
