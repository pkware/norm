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
 * - EVERY position's own name (see [verifiedExpression]) — not merely
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
 * - [NodeTreeColumnProvenance.bodyPosition]'s own item must have a VERIFIABLE name to prove its
 *   position against in the first place: an explicit `AS x`, an implicit (no-`AS`) trailing alias
 *   token (via [splitTrailingImplicitAlias]), or being itself a bare column reference (whose own
 *   name IS what PostgreSQL reports as `:resname` when there is no alias at all). A position whose
 *   `:resname` PostgreSQL auto-generated (e.g. `upper`, `?column?`) has no corresponding text to
 *   verify against, so it can never pass this gate — and per the point above, that failure alone
 *   already fails the WHOLE cross-validation, not merely that one position.
 * - [verifiedExpression] NEVER cuts an implicit alias token off the matched item, unlike the
 *   explicit-`AS` case: whether a trailing bare word is an alias or the item's own last operand
 *   cannot be decided from text alone (`ts AT TIME ZONE timezone`'s trailing `timezone` is a
 *   required operand of `AT TIME ZONE`, not an alias, yet it is ALSO a legal implicit alias token,
 *   and PostgreSQL's own `:resname` for that item is `timezone` either way — the exact same string
 *   a genuine alias would produce). `:resname` is used only to VERIFY that some trailing token
 *   matches, never to decide where to cut, so the emitted expression is always the item's COMPLETE
 *   text, alias or operand included — never a truncated fragment that either fails to parse or,
 *   worse, parses into a different value (`INTERVAL '1' DAY` cut to `INTERVAL '1'` evaluates to `1
 *   SECOND`).
 * - the matched item must not itself be a bare column reference (see [isBareColumnPassThrough]) —
 *   a CTE body that merely forwards another column unchanged (`description AS
 *   description_upper`, or the same shape spelled `description dx`) has nothing worth reporting as
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
 *   comment [stripComments]-removed, exactly as the developer wrote it — including any implicit
 *   alias glued onto it, since cutting one is exactly the defect this function refuses to
 *   reintroduce — or `null` if any gate above fails.
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

  val verifiedExpressions = items.mapIndexed { index, item ->
    val resultName = bodyResultNames[index] ?: return null
    verifiedExpression(item, resultName) ?: return null
  }

  if (provenance.bodyPosition < 1 || provenance.bodyPosition > items.size) return null
  val matchedItem = items[provenance.bodyPosition - 1]
  // A bare column reference is a chained pass-through with nothing of its own to report -- see this
  // function's own KDoc.
  if (isBareColumnPassThrough(matchedItem)) return null

  return collapseCosmeticWhitespace(stripComments(verifiedExpressions[provenance.bodyPosition - 1]))
}

/**
 * Whether [item] is nothing more than a bare column reference forwarded under a different name —
 * an explicit `AS` alias on a plain column (`description AS description_upper`), or the SAME shape
 * spelled with an implicit alias instead (`description dx`) — in which case there is nothing of
 * [item]'s own to report as "the expression".
 *
 * The implicit-alias branch here is the ONE place [splitTrailingImplicitAlias]'s guessed split is
 * used to decide something about [item] rather than merely to VERIFY a `:resname` match (see
 * [verifiedExpression]'s own KDoc) — safe here because a wrong guess can only make this function
 * MORE conservative (reporting a pass-through that, once split correctly, wasn't one — the caller
 * bails and reports nothing) or LESS conservative (missing a real pass-through and instead
 * reporting the item's complete, uncut text — noisy, exactly like an unaliased top-level `count(*)
 * c`, but never a WRONG value): a bad guess here never changes what text gets EMITTED, unlike
 * cutting the guessed alias off before returning it would.
 */
private fun isBareColumnPassThrough(item: OutputItemWithAlias): Boolean {
  if (item.selectItem.columnName != null) return true
  if (item.alias != null) return false
  val implicitAliasStripped = splitTrailingImplicitAlias(item.selectItem.expression)?.expression ?: return false
  return parseColumnReference(implicitAliasStripped).columnName != null
}

/**
 * [item]'s COMPLETE, UNCUT text — see this file's own KDoc for why cutting is never attempted —
 * if [item]'s own name can be VERIFIED to fold-match [resultName] (the node tree's authoritative
 * `:resname` for this same position), `null` otherwise. Tried in this order, matching PostgreSQL's
 * own precedence for naming a select-list item:
 *
 * 1. An explicit `AS alias` ([OutputItemWithAlias.alias]) — folded via [foldIdentifier]'s raw-text
 *    overload, since [OutputItemWithAlias.alias] still carries its own quotes, if any. Unambiguous:
 *    `AS` can only ever introduce an alias, so [item]'s own [SelectItem.expression] (already split
 *    off by [extractAlias]) is exactly the developer's expression with nothing extra attached.
 * 2. A bare column reference with NO alias at all ([SelectItem.columnName] non-`null`) — PostgreSQL
 *    exposes such an item under the column's own name.
 * 3. Neither of the above: [item]'s own text may still END with an implicit (no-`AS`) alias token
 *    that happens to be the SAME string PostgreSQL reports for a keyword-operand shape's own
 *    trailing operand (`ts AT TIME ZONE timezone`'s `:resname` is `timezone`, whether or not
 *    `timezone` is really an alias) — [splitTrailingImplicitAlias] over
 *    [item]'s [SelectItem.expression] is used ONLY to find that trailing token and fold-compare it
 *    against [resultName]; the [ItemAndImplicitAlias.expression] half of its result (the text with
 *    that token cut off) is never read.
 */
private fun verifiedExpression(item: OutputItemWithAlias, resultName: String): String? {
  val alias = item.alias
  if (alias != null) return item.selectItem.expression.takeIf { foldIdentifier(alias) == resultName }

  val columnName = item.selectItem.columnName
  if (columnName != null) {
    val folded = foldIdentifier(columnName, item.selectItem.isColumnNameQuoted)
    return item.selectItem.expression.takeIf { folded == resultName }
  }

  val implicitAlias = splitTrailingImplicitAlias(item.selectItem.expression)?.alias ?: return null
  return item.selectItem.expression.takeIf { foldIdentifier(implicitAlias) == resultName }
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
