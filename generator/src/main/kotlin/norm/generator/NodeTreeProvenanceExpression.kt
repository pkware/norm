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
 * - the CTE [provenance] points at must be findable by REPLAYING [NodeTreeColumnProvenance.hops]
 *   step by step — never a flat, name-only search across every `WITH` clause anywhere in the
 *   statement — both on the node-tree side (see [scopedNodeTreeCteQueryBlock]) and on the SQL-text
 *   side (see [scopedSqlCteDefinition]). Each hop's own [CteHop.ctelevelsup] says exactly which
 *   lexically-enclosing `WITH` clause declares it, so a nested `WITH` that shadows an outer CTE of
 *   the SAME name resolves against the EXACT declaration [NodeTreeProvenanceResolver] walked to,
 *   rather than failing outright merely because the name is declared more than once somewhere in
 *   the statement (#238). A single `WITH` clause can never legally declare the same name twice
 *   (PostgreSQL itself rejects that at parse time), so each hop's own lookup is unambiguous by
 *   construction — the scoping is what makes uniqueness free, not an additional check layered on
 *   top of a global search.
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
 *   here only via [scopedNodeTreeCteQueryBlock], to read the referenced CTE body's own `:resname`s
 *   for cross-validation. Never re-analyzed for nullability or re-queried against the server: this
 *   is the SAME text already in hand from the one probe round trip that produced [provenance].
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

  val verifiedItems = items.map { verifiedNameAndExpression(it) }
  for ((index, verifiedItem) in verifiedItems.withIndex()) {
    val resultName = bodyResultNames[index] ?: return null
    if (verifiedItem == null || verifiedItem.name != resultName) return null
  }

  if (provenance.bodyPosition < 1 || provenance.bodyPosition > items.size) return null
  val matchedItem = items[provenance.bodyPosition - 1]
  // A bare column reference is a chained pass-through with nothing of its own to report -- see this
  // function's own KDoc. selectItem.columnName is already derived from the ALIAS-STRIPPED
  // expression -- [parseOutputItemsWithAlias] strips an IMPLICIT alias token (`description dx`) the
  // same way it strips an explicit `AS` one (`description AS description_upper`) before ever calling
  // [parseColumnReference] -- so this check needs no re-parse of its own (#238).
  if (matchedItem.selectItem.columnName != null) return null

  return collapseCosmeticWhitespace(stripComments(verifiedItems[provenance.bodyPosition - 1]!!.expression))
}

/**
 * Replays [hops] against [nodeTreeText]'s own `:cteList` structure, maintaining the SAME
 * scope-stack shape [NodeTreeProvenanceResolver.resolveVar] built while producing [hops] in the
 * first place, and returns the LAST hop's CTE body (`:ctequery` block) — the exact declaration
 * [NodeTreeProvenanceResolver] resolved against, never merely a same-named one elsewhere in the
 * tree.
 *
 * Index `0` of the scope stack is always the CURRENT query block's own `:cteList`; entering a hop's
 * CTE body pushes that body's OWN `:cteList` onto the front of the scope AS IT EXISTED AT THAT
 * HOP'S OWN DECLARATION — `scopeStack.drop(hop.ctelevelsup)`, never the full accumulated
 * [scopeStack] — exactly mirroring [NodeTreeProvenanceResolver.resolveVar]'s `currentScopeStack`
 * bookkeeping (see that method's KDoc for why: a hop into a SIBLING CTE is not a nesting level, so
 * blindly prepending onto every frame accumulated so far drifts out of step with the node tree's own
 * `:ctelevelsup`, which only ever counts LEXICAL nesting).
 *
 * @return `null` if any hop's [CteHop.ctelevelsup] addresses a scope-stack depth that does not
 *   exist, or its [CteHop.name] is not declared in that scope's `:cteList` — neither can happen for
 *   [hops] genuinely produced by [NodeTreeProvenanceResolver] against THIS SAME [nodeTreeText], but
 *   the caller must still treat either as "cannot resolve" rather than assume it.
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
 * lexically-nested `WITH` clauses, maintaining an analogous scope stack of [CteDefinition] lists
 * (rather than [NodeTreeCteDefinition] maps), and returns the LAST hop's [CteDefinition] — the
 * exact declaration in the text that corresponds to the node tree's own resolution.
 *
 * Every returned (and intermediate) [CteDefinition]'s [CteDefinition.bodyOpenParenthesis]/
 * [CteDefinition.bodyCloseParenthesis] are always re-based to index into [sql] itself — never the
 * (possibly deeply nested) body substring a definition was actually found in — since
 * [resolveNodeTreeProvenanceExpression] always slices [sql] directly by whichever [CteDefinition]
 * this function returns.
 *
 * A single `WITH` clause can never legally declare the same name twice (PostgreSQL rejects that at
 * parse time), so lookup within one scope level never needs a uniqueness check of its own — the
 * scope stack itself is what supplies the disambiguation a flat, whole-statement name search
 * cannot.
 *
 * @return `null` if any hop's [CteHop.ctelevelsup] addresses a scope-stack depth that does not
 *   exist, or its [CteHop.name] does not fold-match ([foldIdentifier] against
 *   [CteDefinition.rawName]) any definition in that scope
 */
private fun scopedSqlCteDefinition(sql: String, hops: List<CteHop>): CteDefinition? {
  var scopeStack = listOf(rebasedCteDefinitions(sql, 0))
  var resolvedDefinition: CteDefinition? = null
  for (hop in hops) {
    val scope = scopeStack.getOrNull(hop.ctelevelsup) ?: return null
    val definition = scope.filter { foldIdentifier(it.rawName) == hop.name }.singleOrNull() ?: return null
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
 * [CteDefinition.bodyCloseParenthesis] shifted by [offset] so they index into the ORIGINAL SQL
 * string [text] was sliced from, rather than [text] itself. See [scopedSqlCteDefinition]'s own
 * KDoc for why every level of its scope stack needs definitions rebased this way.
 */
private fun rebasedCteDefinitions(text: String, offset: Int): List<CteDefinition> =
  parseCteClause(text)?.definitions?.map {
    it.copy(
      bodyOpenParenthesis = it.bodyOpenParenthesis + offset,
      bodyCloseParenthesis =
      it.bodyCloseParenthesis + offset,
    )
  } ?: emptyList()

/**
 * One body item's own [name] — the value PostgreSQL would report as `:resname` for it, the one
 * thing [resolveNodeTreeProvenanceExpression] can independently derive from TEXT alone to
 * cross-validate against the node tree's authoritative answer — and its own [expression] text with
 * any alias (explicit or implicit) already removed, ready to be returned as the resolved provenance
 * expression should this turn out to be the matched position.
 *
 * @property name Tried in this order, matching PostgreSQL's own precedence for naming a select-list
 *   item:
 *   1. An alias ([OutputItemWithAlias.alias]) — explicit `AS alias` OR an implicit (no-`AS`)
 *      trailing alias token, [parseOutputItemsWithAlias] having already folded both spellings into
 *      this one field (via [extractAlias] and, when that finds nothing, [splitTrailingImplicitAlias]
 *      — see [OutputItemWithAlias.alias]'s own KDoc) — folded via [foldIdentifier]'s raw-text
 *      overload, since [OutputItemWithAlias.alias] still carries its own quotes, if any.
 *   2. A bare column reference with NO alias at all ([SelectItem.columnName] non-`null`) —
 *      PostgreSQL exposes such an item under the column's own name.
 * @property expression [SelectItem.expression] — already alias-free either way, by
 *   [parseOutputItemsWithAlias]'s own construction: case 1 by [extractAlias]'s or
 *   [splitTrailingImplicitAlias]'s split, case 2 because the WHOLE item was one bare column
 *   reference with nothing else to strip.
 */
private data class VerifiedBodyItem(val name: String, val expression: String)

/**
 * Computes [item]'s [VerifiedBodyItem], or `null` if [item] has no VERIFIABLE name at all (an
 * unaliased computed expression, whose auto-generated `:resname` — e.g. `upper`, `?column?` —
 * cannot be reconstructed from text without guessing) — see [VerifiedBodyItem.name]'s own KDoc for
 * the two cases tried, in order.
 */
private fun verifiedNameAndExpression(item: OutputItemWithAlias): VerifiedBodyItem? {
  val alias = item.alias
  if (alias != null) return VerifiedBodyItem(foldIdentifier(alias), item.selectItem.expression)
  val columnName = item.selectItem.columnName ?: return null
  return VerifiedBodyItem(foldIdentifier(columnName, item.selectItem.isColumnNameQuoted), item.selectItem.expression)
}
