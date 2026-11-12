package norm.generator

import norm.generator.libpgquery.CommonTableExpression
import norm.generator.libpgquery.StatementNode

/**
 * Extracts, from the developer's own original SQL text, the expression a [NodeTreeColumnProvenance]
 * points at.
 *
 * [parsedSql] must hold the query's original text — never [nodeTreeText]'s sentinel-substituted or deparsed
 * form. [nodeTreeText] is trusted only for where the expression lives ([provenance]'s CTE name and
 * body position); [parsedSql] is trusted only for what it says. The CTE bodies are nodes of [ParsedSql.tree] and their
 * items come from [ParsedSql.outputItemsOf], so a body is read once however many columns point at it.
 *
 * Returns `null` if any of these checks fails:
 * - the CTE [provenance] points at is resolved by replaying [NodeTreeColumnProvenance.hops] step by
 *   step ([scopedNodeTreeCteQueryBlock], [scopedSqlCteBody]), so a nested `WITH` that shadows an
 *   outer CTE of the same name resolves against the exact declaration, not merely a same-named one
 *   elsewhere.
 * - [ParsedSql.outputItemsOf] for that CTE's body yields exactly as many top-level items as
 *   [nodeTreeText] has non-junk body target entries for that CTE, and every position's own `:resname`
 *   equals, in order, the item's alias or column name, with `:resname`s pairwise unique.
 * - [provenance]'s own item has a verifiable name: an explicit `AS x`, an implicit trailing alias
 *   token, or being itself a bare column reference (see [verifiedItem]).
 * - the matched item was not verified only via an implicit alias, which this function declines.
 * - the matched item is not a bare column pass-through (see [isBareColumnPassThrough]).
 *
 * @param parsedSql The query's original SQL text (containing the developer's own `?` placeholders, if
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
  parsedSql: ParsedSql,
  nodeTreeText: String,
  provenance: NodeTreeColumnProvenance,
  parser: PgNodeTreeParser = PgNodeTreeParser(),
): String? {
  val cteBodyBlock = scopedNodeTreeCteQueryBlock(nodeTreeText, provenance.hops, parser) ?: return null
  val bodyResultNames = parser.resultProjection(cteBodyBlock).entries.map { it.resultName }

  val items = scopedSqlCteBody(parsedSql, provenance.hops)?.let { parsedSql.outputItemsOf(it) } ?: return null

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
 * 1. An alias. [OutputItemWithAlias.alias] is the parser's own name, so it is compared to [resultName] directly. An
 *    implicit (no-`AS`) alias is verified the same way and reported as [AliasMatchKind.IMPLICIT_ALIAS].
 * 2. A bare column reference with no alias at all — PostgreSQL exposes such an item under the
 *    column's own name.
 */
private fun verifiedItem(item: OutputItemWithAlias, resultName: String): VerifiedItem? {
  val alias = item.alias
  if (alias != null) {
    val matchKind = if (item.isAliasExplicit) AliasMatchKind.EXPLICIT_ALIAS else AliasMatchKind.IMPLICIT_ALIAS
    return VerifiedItem(item.selectItem, matchKind).takeIf { alias == resultName }
  }

  val columnName = item.selectItem.columnName ?: return null
  return VerifiedItem(item.selectItem, AliasMatchKind.BARE_COLUMN_REFERENCE).takeIf { columnName == resultName }
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
 * The SQL-text counterpart of [scopedNodeTreeCteQueryBlock]: replays [hops] against the `WITH` clauses of [parsedSql]'s
 * own parse tree, maintaining an analogous scope stack of [CommonTableExpression] lists, and returns the node of the last hop's
 * CTE body.
 *
 * @return `null` if any hop's [CteHop.ctelevelsup] addresses a scope-stack depth that does not exist,
 *   or its [CteHop.name] does not match ([CommonTableExpression.name], PostgreSQL's own logical identifier
 *   value) exactly one definition in that scope
 */
private fun scopedSqlCteBody(parsedSql: ParsedSql, hops: List<CteHop>): StatementNode? {
  val statement = parsedSql.tree?.firstStatement ?: return null
  var scopeStack = CteScopeStack(statement.commonTableExpressions)
  var resolvedBody: StatementNode? = null
  for (hop in hops) {
    val scope = scopeStack.frameAt(hop.ctelevelsup) ?: return null
    // Both names are the parser's `ctename`, so a direct comparison is exact.
    val definition = scope.filter { it.name == hop.name }.singleOrNull() ?: return null
    resolvedBody = definition.query
    scopeStack = scopeStack.entering(hop.ctelevelsup, definition.query.commonTableExpressions)
  }
  return resolvedBody
}
