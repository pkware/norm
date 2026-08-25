package norm.generator

/**
 * Resolves the SQL expression text that produced a CTE-wrapped result column, when the outer
 * query's own select item is a plain reference into a CTE's output (rather than a computed
 * expression itself). This is the missing half of provenance for a result column that is BOTH
 * CTE-wrapped AND expression-derived — [parseSelectItems] only ever looks at the OUTER select
 * list, where such a column is a bare name (`description_upper`), never the `UPPER(description)`
 * expression that actually produced it inside the CTE body.
 *
 * [selectItem] must be the outer item — i.e. what [parseSelectItems] returned for this column's
 * position in the outer query's own output clause. This function re-parses [sql]'s `WITH` clause
 * and, unlike [parseSelectItems], deliberately looks INSIDE a CTE body to find the real
 * expression. [selectItem] itself being a computed expression (`columnName == null`) is already
 * handled by the ordinary `isComputedExpression` path in `TypeRepository.buildTypeProjectionForQuery`
 * — this function only ever needs to run for a plain reference, so a `null` `columnName` here
 * returns `null` immediately.
 *
 * A WHITELIST, deliberately, not a best-effort general resolver: an earlier, more general
 * implementation (multi-CTE, join-aware, alias-aware) went through several rounds of adversarial
 * review, and EVERY round found a NEW wrong emission, not merely a new missed case. String-based
 * SQL transformation cannot be proven exhaustively correct for every shape a general
 * implementation has to cover — the same principle `PgCatalogLoader`'s own discard-unless-verified
 * fallback answers (see its KDoc, around the same reasoning). This function instead resolves ONLY
 * a single, narrow shape that is provably unambiguous by construction, and returns `null` for
 * everything else — INCLUDING shapes a more general implementation could resolve correctly. The
 * single-CTE precondition below is load-bearing for that proof, not a convenience: do not widen
 * this gate without re-deriving the correctness argument from scratch.
 *
 * Resolves ONLY when ALL of the following hold; `null` for every other case:
 * - [selectItem] has a non-`null` [SelectItem.columnName] — see the paragraph above.
 * - The outer reference is NOT an unquoted bare [reservedWords] name (`user`, `true`,
 *   `system_user`, ...) — PostgreSQL never resolves one of these to a column, no matter how
 *   confidently everything else about this shape lines up, so an outer `user` can never denote a
 *   body item aliased `AS user`; see [reservedWords]' own KDoc for why the FULL reserved category
 *   is checked rather than only the keywords currently known to be niladic.
 * - [sql] has a `WITH` clause (not `WITH RECURSIVE`) declaring EXACTLY ONE CTE, AND the main
 *   query's `FROM` clause is EXACTLY that CTE's own name and nothing else — no alias, no `JOIN`,
 *   no additional comma-separated source, no derived table. BOTH checks are required, not either:
 *   the declared-count check alone would leave a same-named schema table relying on ordinary CTE
 *   name shadowing to save it (an unqualified reference resolves against the CTE, never the
 *   table, when both exist — a rule this function does not attempt to model) — requiring the FROM
 *   clause to be that exact single name too means there is nothing else the reference COULD mean.
 * - The CTE has no explicit column list (`name(col1, col2) AS (...)`) — the list renames the
 *   body's own output names, and this function has no access to which body expression backs any
 *   particular listed name.
 * - NEITHER the CTE body NOR the main query has a top-level set operation (`UNION`/`INTERSECT`/
 *   `EXCEPT`, with or without `ALL`/`DISTINCT`) — either one's rows would come from multiple,
 *   independently-sourced branches, and [parseOutputItemsWithAlias] (for the body) or the FROM
 *   check above (for the main query, whose own FROM-region search already stops AT a set-operation
 *   keyword, since one is among [SELECT_FROM_TERMINATOR_KEYWORDS] — so the FROM check alone would
 *   otherwise wrongly PASS for `SELECT ux FROM c UNION ALL SELECT b FROM other`, since branch one's
 *   FROM really is just `c`) only ever sees the first branch. The CTE body being DML with its own
 *   `RETURNING` clause is fine and expected.
 * - The main query is a plain `SELECT`, not DML (`INSERT`/`UPDATE`/`DELETE`/`MERGE`) — a DML main
 *   query's `RETURNING` list resolves against its OWN target relation plus any `USING`/`FROM`
 *   sources, which reintroduces exactly the DML-target-vs-CTE-name confusion this function exists
 *   to avoid.
 * - The outer reference, if qualified, folds (see [foldIdentifier]) to the same name as the CTE
 *   itself, compared via [CteDefinition.rawName] — never [CteDefinition.name]; see
 *   [CteDefinition.rawName]'s own KDoc for why. This is the only name the FROM clause makes it
 *   addressable as, per the first bullet above (also compared via `rawName`, for the same reason).
 * - NO TWO items in the CTE body's own output list have an EXPLICIT `AS <alias>` (via
 *   [extractAlias]) that fold to the SAME name as each other — checked across ALL aliased items
 *   in the body, not only the two (if any) that happen to fold to the outer reference's own name.
 *   This is stricter than PostgreSQL itself requires: a body with two OTHER items colliding with
 *   each other, but neither colliding with the name actually being looked up, is valid PostgreSQL
 *   and would resolve there — this function silently punts on it anyway, trading a rare missed
 *   resolution for not having to reason about which of two colliding aliases elsewhere in the body
 *   an unrelated lookup should ignore. Exactly one item then bears an EXPLICIT `AS <alias>` that
 *   folds to the outer reference's name — implied unique once the check above passes. An item
 *   with an IMPLICIT alias (no `AS`, e.g. `SELECT UPPER(a) y FROM t`) or no alias at all is never
 *   a candidate match, regardless of what its raw text looks like — deliberately unimplemented, not merely
 *   unhandled: distinguishing a genuine implicit alias from an expression that only LOOKS like
 *   `expression identifier` (`CAST(x AS int)`, `a + b`, `INTERVAL '1 day'`, ...) is exactly the
 *   kind of guess this function exists to avoid.
 * - That matched item's own expression is not itself a bare column reference (a chained
 *   pass-through, e.g. `description AS description_upper`) — echoing a column name back as if it
 *   were a meaningful expression adds no value, the same rationale [parseSelectItems]'s caller
 *   (`TypeRepository.buildTypeProjectionForQuery`) already applies to a top-level plain column
 *   reference.
 *
 * Folding (see [foldIdentifier]) matters for CORRECTNESS here, not just recall: a body with both
 * `UPPER(a) AS "UX"` (quoted, case preserved) and `LOWER(a) AS ux` (unquoted, folds to lowercase)
 * must match an outer `ux` against the SECOND item only — a case-INSENSITIVE raw comparison would
 * wrongly match the quoted first item instead.
 *
 * Comments inside the matched body item's own text (e.g. a line comment sitting between two
 * select items in the body's item list, which ends up glued onto the matched item's expression by
 * [extractAlias]'s own text-slicing) are stripped from the returned expression via
 * [stripComments] before it is returned — the emitted KDoc must never carry raw comment text.
 *
 * @param reservedWords PostgreSQL's reserved keyword set — every keyword the CONNECTED server's
 *   `pg_get_keywords()` categorizes `R` ("reserved") or `T` ("reserved, can be function or type
 *   name"), lowercased; normally [JdbcAnalyzer.fetchReservedWords]' live result, threaded down
 *   from wherever that was already queried for this generator run, NEVER a hardcoded snapshot.
 *   Membership means a bare, UNQUOTED outer reference of that name is rejected outright rather
 *   than fold-compared against a body alias: PostgreSQL either resolves it to a runtime VALUE
 *   instead (a niladic keyword like `user`, or a literal like `true`/`false`/`null` — verified
 *   directly: `WITH c AS (SELECT 'x' AS user) SELECT user FROM c` returns the session user, e.g.
 *   `postgres`, never `'x'`), or the word cannot appear as a bare, unaliased select-list item AT
 *   ALL (`select`, `from`, `where`, ...) — a real query containing one could never have reached
 *   this far through the pipeline, so covering that half of the set costs nothing. The FULL
 *   reserved category is checked, not only the keywords currently known to be niladic: this is
 *   what a real, live query buys over a hand-maintained list — reserved-word status is per-server,
 *   per-version state (verified: `system_user` became niladic in PostgreSQL 16, so a keyword list
 *   snapshotted against an older server would wrongly let it resolve through a CTE body alias on a
 *   PostgreSQL 16+ server), and a generator run can target more than one database, so this must
 *   never be cached across runs. Defaults to `emptySet()` ONLY for callers that never construct a
 *   `sql` containing a `WITH` clause aliasing a reserved word at all — every real production
 *   caller (`TypeRepository.buildTypeProjectionForQuery`) passes the connected server's actual set
 *   explicitly; relying on the default there would silently widen this function's resolution past
 *   what PostgreSQL itself allows for a bare reserved-word reference.
 * @return The CTE body's expression text (e.g. `UPPER(description)`), or `null` if any
 *   precondition above fails to hold.
 */
internal fun resolveCteOutputExpression(
  sql: String,
  selectItem: SelectItem,
  reservedWords: Set<String> = emptySet(),
): String? {
  val outputName = selectItem.columnName ?: return null
  // A bare PostgreSQL reserved word is never resolved as a column -- see reservedWords' own KDoc
  // -- but ONLY when unquoted: quoting suppresses that entirely (a quoted "user" is an ordinary
  // column reference), so this must gate on selectItem.isColumnNameQuoted explicitly rather than
  // fold-comparing regardless of it -- outputName is a LOGICAL value (see SelectItem.columnName's
  // own KDoc) that could coincidentally already be lowercase even when quoted, which a
  // fold-without-gating would wrongly treat as matching the (all-lowercase) reserved set.
  if (!selectItem.isColumnNameQuoted && foldIdentifier(outputName, isQuoted = false) in reservedWords) {
    return null
  }

  val cteClause = parseCteClause(sql) ?: return null
  if (cteClause.isRecursive) return null

  val cte = cteClause.definitions.singleOrNull() ?: return null
  if (cte.hasColumnList) return null

  // Compared via cte.rawName, NOT cte.name -- see CteDefinition.rawName's own KDoc for why.
  // selectItem.tableName is folded via its OWN isTableNameQuoted flag (it is a LOGICAL value, not
  // raw text -- see SelectItem's own KDoc), while cte.rawName still folds via the raw-text
  // overload, since CteDefinition never adopted the logical-value/quoted-flag split.
  if (selectItem.tableName != null &&
    foldIdentifier(selectItem.tableName, selectItem.isTableNameQuoted) != foldIdentifier(cte.rawName)
  ) {
    return null
  }

  val mainQueryWindow = sql.substring(cteClause.mainQueryStart)
  if (!isPlainSelectMainQuery(mainQueryWindow)) return null
  if (!mainQueryFromIsExactlyThisCte(mainQueryWindow, cte.rawName)) return null

  // A top-level set operation in the MAIN QUERY itself means result column 1's rows come from
  // MULTIPLE, independently-sourced branches -- only the first branch's FROM is this CTE at all,
  // so resolving through it would misdescribe every row that actually came from a later branch.
  if (hasTopLevelSetOperation(mainQueryWindow)) return null

  val bodySql = sql.substring(cte.bodyOpenParenthesis + 1, cte.bodyCloseParenthesis)
  // Same reasoning, applied to the CTE BODY's own rows instead of the main query's.
  if (hasTopLevelSetOperation(bodySql)) return null

  val items = parseOutputItemsWithAlias(bodySql)
  // outputName is a LOGICAL value, folded via its own isColumnNameQuoted flag; a body item's own
  // alias (from extractAlias, via parseOutputItemsWithAlias) is still RAW text with any quotes
  // intact, so it keeps folding via the raw-text overload, unaffected by this round's change.
  val foldedOutputName = foldIdentifier(outputName, selectItem.isColumnNameQuoted)
  val aliasedFoldedNames = items.mapNotNull { it.alias }.map(::foldIdentifier)
  // Two body items folding to the same name make it impossible to know, by name alone, which one
  // an outer reference to that name actually targets -- ambiguous within the body itself.
  if (aliasedFoldedNames.size != aliasedFoldedNames.toSet().size) return null

  val matchedItem = items.find { it.alias != null && foldIdentifier(it.alias) == foldedOutputName } ?: return null
  // A bare column reference is a chained pass-through -- see this function's KDoc.
  if (matchedItem.selectItem.columnName != null) return null

  return collapseCosmeticWhitespace(stripComments(matchedItem.selectItem.expression))
}

/**
 * Whether [window] is a plain `SELECT` main query — specifically, NOT DML (`INSERT`/`UPDATE`/
 * `DELETE`/`MERGE`). Used by [resolveCteOutputExpression] to reject a DML main query outright: its
 * `RETURNING` list resolves against its own target relation (plus any `USING`/`FROM` sources),
 * never simply against a CTE feeding a nested `SELECT` — see that function's KDoc.
 */
private fun isPlainSelectMainQuery(window: String): Boolean {
  val leadingKeywordStart = skipWhitespaceAndComments(window, 0)
  val isDml = listOf("INSERT", "UPDATE", "DELETE", "MERGE").any { keyword ->
    skipOptionalKeyword(window, leadingKeywordStart, keyword) != leadingKeywordStart
  }
  return !isDml
}

/**
 * The keywords that end a plain `SELECT`'s `FROM` clause at the top level — anything that can
 * legally follow it.
 */
private val SELECT_FROM_TERMINATOR_KEYWORDS = listOf(
  "WHERE", "GROUP", "HAVING", "WINDOW", "ORDER", "LIMIT", "OFFSET", "FETCH", "UNION", "EXCEPT", "INTERSECT", "FOR",
)

/**
 * Whether [window]'s own `FROM` clause is EXACTLY [cteRawName] (folded per [foldIdentifier]) and
 * nothing else — no alias, no `JOIN`, no additional comma-separated source. Any of those would
 * make the FROM clause's own text longer than the bare CTE name, so a single fold-and-compare
 * check on the whole clause text is sufficient; there is no need to separately detect a comma or
 * a `JOIN` keyword.
 *
 * @param cteRawName MUST be [CteDefinition.rawName], never [CteDefinition.name] — see
 *   [CteDefinition.rawName]'s own KDoc for why.
 * @return `false` if [window] has no top-level `SELECT`/`FROM` at all.
 */
private fun mainQueryFromIsExactlyThisCte(window: String, cteRawName: String): Boolean {
  val selectIndex = findTopLevelKeyword(window, "SELECT")
  if (selectIndex < 0) return false
  val fromIndex = findTopLevelKeyword(window, "FROM", selectIndex + "SELECT".length)
  if (fromIndex < 0) return false
  val afterFrom = fromIndex + "FROM".length

  var end = window.length
  for (terminator in SELECT_FROM_TERMINATOR_KEYWORDS) {
    val terminatorIndex = findTopLevelKeyword(window, terminator, afterFrom)
    if (terminatorIndex in afterFrom until end) end = terminatorIndex
  }

  val fromText = stripComments(window.substring(afterFrom, end)).trim().trimEnd(';').trim()
  return fromText.isNotEmpty() && foldIdentifier(fromText) == foldIdentifier(cteRawName)
}

/**
 * The keywords that introduce a top-level SQL set operation between two query branches: `UNION`,
 * `INTERSECT`, and `EXCEPT` (each optionally followed by `ALL` or `DISTINCT`, which this function
 * does not need to check for separately — detecting the bare keyword itself is enough to know
 * multiple branches are present).
 */
private val SET_OPERATION_KEYWORDS = listOf("UNION", "INTERSECT", "EXCEPT")

/**
 * Whether [text] contains any [SET_OPERATION_KEYWORDS] keyword at the top level (paren depth 0) —
 * used by [resolveCteOutputExpression] to reject BOTH a CTE body AND the main query window when
 * either one's rows come from MULTIPLE, independently-sourced branches, since
 * [parseOutputItemsWithAlias] (for the body) only ever sees the first one, and the main query's
 * own FROM-region search (see [mainQueryFromIsExactlyThisCte]) would otherwise look like a clean
 * single-CTE match using only that same first branch.
 */
private fun hasTopLevelSetOperation(text: String): Boolean =
  SET_OPERATION_KEYWORDS.any { findTopLevelKeyword(text, it) >= 0 }
