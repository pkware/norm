package norm.generator

/**
 * A parsed item from a SQL SELECT clause.
 *
 * @property expression The full SQL expression text (e.g. `COUNT(*)`, `author.name`, `book.title`).
 * @property columnName The column's LOGICAL name for a simple column reference — `null` for a
 *   computed expression. For a QUOTED reference (`"My Col"`, `"He""llo"`), this is the identifier
 *   PostgreSQL itself resolves to: surrounding quotes removed and any doubled `""` escape
 *   collapsed to the single literal `"` it represents (verified directly against a live
 *   PostgreSQL 18: `ResultSetMetaData.getColumnName` for `SELECT "He""llo" FROM (SELECT 1 AS
 *   "He""llo") s` reports `He"llo` — no quotes, escape already collapsed — which is exactly what
 *   this property must agree with, since [JdbcAnalyzer]'s own `originalName` falls back to
 *   `columnName` first and only reaches `getColumnName` when this is `null`). This is NEVER the
 *   raw, quote-decorated source text — see [isColumnNameQuoted] for how the quoted/unquoted
 *   distinction PostgreSQL folding depends on is preserved instead, alongside the logical value
 *   rather than encoded inside it.
 * @property tableName The table qualifier's LOGICAL name for a qualified reference (e.g. `author`
 *   in `author.name`, or `My Table` in `"My Table".name`) — same logical-value convention as
 *   [columnName], and `null` for an unqualified reference or a computed expression.
 * @property isColumnNameQuoted Whether [columnName] came from a QUOTED source identifier —
 *   `false` when [columnName] is `null`. PostgreSQL folds a quoted identifier reference
 *   EXACTLY (case preserved, never lowercased) and an unquoted one to lowercase; since
 *   [columnName] itself no longer carries the quotes that would otherwise signal which rule
 *   applies (they were already removed to produce the logical value), this flag is what a caller
 *   doing that folding (see `foldIdentifier`'s two-argument overload) must consult instead.
 * @property isTableNameQuoted Whether [tableName] came from a QUOTED source identifier — same
 *   convention as [isColumnNameQuoted], `false` when [tableName] is `null`.
 */
internal data class SelectItem(
  val expression: String,
  val columnName: String?,
  val tableName: String?,
  val isColumnNameQuoted: Boolean = false,
  val isTableNameQuoted: Boolean = false,
)

/**
 * Parses the output clause of a SQL statement to extract individual items.
 *
 * Paired POSITIONALLY against `java.sql.ResultSetMetaData` columns by
 * `JdbcAnalyzer.buildResultColumns`, so the search is restricted to the statement's MAIN query —
 * after any leading `WITH` clause's CTEs, via [parseCteClause]'s [ParsedCteClause.mainQueryStart]
 * — and `SELECT` is located with [findTopLevelKeyword] (depth-0, lexically aware, never
 * `String.indexOf`), so a nested `SELECT` or a keyword-like substring inside a literal/comment is
 * never mistaken for the real clause. `RETURNING` is located with [findTopLevelReturningKeyword]
 * instead — see its KDoc for why a plain [findTopLevelKeyword] search is not enough — and is only
 * searched for when the window's own leading keyword is DML (`INSERT`/`UPDATE`/`DELETE`/`MERGE`),
 * since `RETURNING` is not reserved and is otherwise legal as a plain `SELECT`'s column alias.
 *
 * Handles:
 * - Simple columns: `title` → expression=`title`, columnName=`title`
 * - Qualified columns: `book.title` → expression=`book.title`, columnName=`title`, tableName=`book`
 * - Aliased columns: `author.name AS author_name` → expression=`author.name`, columnName=`name`, tableName=`author`
 * - Computed expressions: `COUNT(*) AS book_count` → expression=`COUNT(*)`, columnName=`null`
 * - Star projections: `*` → expression=`*`, columnName=`null`
 *
 * On the `SELECT` branch only (a `RETURNING` clause has no such quantifier), an optional leading
 * set quantifier — `ALL`, or `DISTINCT` optionally followed by `ON (...)` — is skipped via
 * [skipOptionalSetQuantifier] BEFORE the clause is split into items, so the FIRST item's
 * `expression`/`columnName` reflect the bare column, not the quantifier glued onto it (`SELECT
 * DISTINCT x, id FROM t` → first item is `x`, not `DISTINCTx`). This must happen here, not inside
 * [isStarItem]: [isStarItem] normalizes by stripping ALL whitespace, so `ALL 2.*a` and a genuine
 * star on a table named `all2` (`SELECT all2.* a, p FROM all2` — verified valid, 3 columns) become
 * textually IDENTICAL (`ALL2.*a`/`all2.*a`) once whitespace is gone; only stripping the quantifier
 * BEFORE that normalization, using the still-whitespace-intact `window`, can tell them apart —
 * `skipOptionalKeyword`'s own word-boundary check is what refuses to match `ALL` as a prefix of the
 * identifier `all2` (verified: `SELECT ALL 2.*a lbl, b FROM t` is arithmetic, 2 columns, distinct
 * from the `all2` table case despite normalizing identically further down the pipeline).
 *
 * A star item (`*`/`table.*`, via [isStarItem] — which also recognizes an implicit alias on a
 * star, including a quoted, `U&`-escaped, or non-ASCII unquoted alias, though NOT claimed
 * exhaustive — see [isStarItem]'s own KDoc) expands to however many columns the starred relation
 * has, shifting every later item onto the wrong metadata column; an item before a star is
 * unaffected by that unknown width, so only the first star and everything after it is dropped — a
 * lone star is left alone, and items at/after a star fall back to metadata unresolved.
 *
 * @return Items strictly before the first star, if any; the full list if there is no star or it's
 *   a single star item; or empty if the output clause can't be found (e.g. a parenthesized main
 *   query, a `VALUES` list, or a `TABLE` shorthand — none have a depth-0 `SELECT`/`RETURNING`).
 *   Both consumers degrade safely for a missing item, falling back to
 *   `ResultSetMetaData.getColumnName` rather than reporting a wrong original name.
 */
internal fun parseSelectItems(sql: String): List<SelectItem> = parseOutputItemsWithAlias(sql).map { it.selectItem }

/**
 * A single item from [parseOutputItemsWithAlias], pairing the [selectItem] parsed from the
 * item's expression (alias stripped, exactly what [parseSelectItems] itself exposes) with the
 * [alias] that was stripped off, if any.
 *
 * Kept separate from [SelectItem] because [SelectItem.columnName] already carries a meaning for
 * simple column references — reusing it for an alias would conflate "this item IS a bare column
 * reference" with "this item HAS an alias", which are different facts a computed expression can
 * have independently (`UPPER(description) AS description_upper` has an alias but is not a bare
 * column reference; `description AS description_upper` is a bare column reference AND has an
 * alias).
 *
 * @property selectItem The parsed expression/columnName/tableName, alias already removed.
 * @property alias The alias text after `AS`, or `null` if the item has no alias.
 */
internal data class OutputItemWithAlias(val selectItem: SelectItem, val alias: String?)

/**
 * The shared parsing core behind both [parseSelectItems] (which discards [OutputItemWithAlias.alias])
 * and [resolveCteOutputExpression] (which needs the alias to match a CTE body item against the
 * outer name it's exposed under — see that function's KDoc for why the alias can't be dropped
 * there). Locates the same output clause [parseSelectItems] documents finding — see its KDoc for
 * the window/`RETURNING`-gating/star-truncation rules, all of which apply identically here.
 */
internal fun parseOutputItemsWithAlias(sql: String): List<OutputItemWithAlias> {
  val mainQueryStart = parseCteClause(sql)?.mainQueryStart ?: 0
  val window = sql.substring(mainQueryStart)

  // See parseSelectItems' KDoc for why RETURNING is gated on the main query's own leading keyword.
  val leadingKeywordStart = skipWhitespaceAndComments(window, 0)
  val isDmlMainQuery = listOf("INSERT", "UPDATE", "DELETE", "MERGE").any { keyword ->
    skipOptionalKeyword(window, leadingKeywordStart, keyword) != leadingKeywordStart
  }

  val returningIndex = if (isDmlMainQuery) findTopLevelReturningKeyword(window) else -1
  val selectIndex = findTopLevelKeyword(window, "SELECT")

  val afterKeyword: Int
  val itemsStart: Int
  val hasFromClause: Boolean
  if (returningIndex >= 0) {
    afterKeyword = returningIndex + "RETURNING".length
    // PostgreSQL 18's `RETURNING WITH (OLD AS o, NEW AS n) o.x, n.x` prologue is not part of the
    // first item's own expression — skip past it via parseOldNewAliasPrologue so itemsStart lands
    // on the real first item, not on `WITH (OLD AS o, NEW AS n) o.x` (which parseColumnReference
    // cannot make sense of, so it would otherwise be embedded verbatim in generated KDoc as that
    // column's expression).
    itemsStart = parseOldNewAliasPrologue(window, afterKeyword).second
    // RETURNING clauses are terminal — no FROM keyword follows
    hasFromClause = false
  } else if (selectIndex >= 0) {
    afterKeyword = selectIndex + "SELECT".length
    itemsStart = skipOptionalSetQuantifier(window, afterKeyword)
    hasFromClause = true
  } else {
    return emptyList()
  }

  val fromIndex = if (hasFromClause) findTopLevelKeyword(window, "FROM", afterKeyword) else -1
  val rawClause = if (fromIndex >= 0) {
    window.substring(itemsStart, fromIndex)
  } else {
    window.substring(itemsStart)
  }

  val items = splitAtTopLevel(rawClause.trim().trimEnd(';'), ',').map { raw ->
    val item = raw.trim()
    val (expression, alias) = extractAlias(item)
    OutputItemWithAlias(parseColumnReference(expression), alias)
  }

  val firstStarIndex = items.indexOfFirst { isStarItem(it.selectItem.expression) }
  return if (firstStarIndex < 0 || items.size == 1) items else items.take(firstStarIndex)
}

/**
 * Skips an optional leading SQL set quantifier starting at or after [position] in [sql]: `ALL`,
 * or `DISTINCT` optionally followed by `ON (` ... `)`. Used by [parseSelectItems] to separate a
 * `SELECT`'s quantifier from its first real item BEFORE [isStarItem] ever sees it — see that
 * function's KDoc for why this can't be done inside [isStarItem] itself.
 *
 * [position] is the index right after the `SELECT` keyword — still followed by whitespace, since
 * [skipOptionalKeyword] (unlike [skipWhitespaceAndComments]) requires its keyword to start EXACTLY
 * where it's told to look, with no leading separator of its own to skip. This function skips that
 * whitespace/comments FIRST, then tries `ALL`/`DISTINCT` at the resulting position — otherwise
 * `regionMatches` would fail immediately on the space between `SELECT` and the quantifier, this
 * function would report no quantifier present, and `parseSelectItems` would go right back to
 * feeding [isStarItem] the fused, whitespace-bearing text this function exists to prevent.
 *
 * [skipOptionalKeyword]'s own word-boundary check is what keeps `ALL`/`DISTINCT` from matching a
 * longer identifier that merely starts with those letters (`all2`, `distinctive_column`).
 *
 * @return The index immediately after the quantifier (and any trailing whitespace/comments), or
 *   [position] unchanged if there is no quantifier there.
 */
private fun skipOptionalSetQuantifier(sql: String, position: Int): Int {
  val keywordStart = skipWhitespaceAndComments(sql, position)

  val afterAll = skipOptionalKeyword(sql, keywordStart, "ALL")
  if (afterAll != keywordStart) return afterAll

  val afterDistinct = skipOptionalKeyword(sql, keywordStart, "DISTINCT")
  if (afterDistinct == keywordStart) return position

  val afterOn = skipOptionalKeyword(sql, afterDistinct, "ON")
  if (afterOn == afterDistinct) return afterDistinct

  if (afterOn >= sql.length || sql[afterOn] != '(') return afterDistinct
  val closeParenthesis = findMatchingCloseParenthesis(sql, afterOn)
  if (closeParenthesis < 0) return afterDistinct
  return skipWhitespaceAndComments(sql, closeParenthesis + 1)
}

/**
 * Splits a select item into its expression and alias parts.
 *
 * Handles `expression AS alias` patterns, respecting parentheses so that
 * `CAST(x AS text) AS my_col` correctly identifies `my_col` as the alias.
 *
 * Skips string literals, quoted identifiers, dollar-quoted strings, and comments via
 * [skipLexicalToken], so an `AS`-like substring or an unbalanced paren inside one of those is
 * not mistaken for a real `AS` keyword or a real parenthesis.
 *
 * Tracks `(`/`)` only, deliberately NOT `[`/`]` — unlike [splitAtTopLevel], which shares one depth
 * counter across both bracket kinds because it scans RAW, not-yet-split clause text, where a
 * top-level-looking `,` can sit directly inside an unsplit `ARRAY[...]` literal (see
 * [splitAtTopLevel]'s own KDoc). [extractAlias] instead only ever
 * receives an item [parseOutputItemsWithAlias] already split via [splitAtTopLevel] — so any
 * `[`/`]` pair the item contains is, by construction of that prior split, already self-balanced
 * and cannot itself hold an unmatched `(`/`)` inside it either. Tracking `[`/`]` here would
 * therefore never change which `AS` this scan finds: PostgreSQL syntax also never lets a bare `AS`
 * appear directly inside `[...]` (an array literal holds element expressions, not aliases) without
 * that `AS` also sitting inside some `(...)` this scan already tracks (`ARRAY[CAST(x AS int)]`, for
 * instance, has its `AS` inside `CAST(...)`'s own parentheses).
 *
 * The word-boundary check on either side of a candidate `AS`/`as` uses [isIdentifierChar] — the
 * same predicate [findTopLevelKeyword] and every other keyword scanner in this file use — rather
 * than `Char.isWhitespace()`: PostgreSQL's `AS` keyword only needs to NOT be fused into a longer
 * identifier on either side, not to be surrounded by literal whitespace. Verified against
 * PostgreSQL 18.4: `SELECT (1)AS b` returns column `b` — `AS` directly abuts the closing `)` with
 * no whitespace, and `)` is not an identifier character, so this is the real keyword. Conversely
 * `SELECT 1 AS$b` returns column `as$b`, a SINGLE implicit alias identifier — `$` is a valid
 * identifier-continuation character (see [isIdentifierChar]), so `AS$b` is one word, not the
 * keyword `AS` followed by `$b`.
 *
 * This affects `columnName` whenever a glued alias would otherwise prevent the pre-`AS` text from
 * matching [COLUMN_REFERENCE] on its own, but the split-off expression matches it once separated.
 * `columnName` is derived by [parseOutputItemsWithAlias] from the split `expression` via
 * [parseColumnReference] — [parseOutputItemsWithAlias] is this function's only caller, and does
 * `val (expression, alias) = extractAlias(item)`, keeping the alias half (unlike [parseSelectItems],
 * which discards it) — so whatever the split changes `expression` to feeds directly into that
 * derivation. Verified against PostgreSQL 18.4: `SELECT a AS"b", id FROM t` is valid (columns `b`,
 * `id`; PostgreSQL reports `a` as the source column of the first result column) — `AS"b"` (no
 * space before the quote) is recognized as the keyword here, since `"` is not an identifier
 * character, so the right-hand boundary holds without requiring whitespace; the item splits into
 * `expression="a"`, which matches [COLUMN_REFERENCE], giving `columnName="a"`, the name PostgreSQL
 * itself reports. Not every glued case benefits this way: a glued expression like `(age)AS b`
 * still fails to match [COLUMN_REFERENCE] once split, because the split-off `expression` (`(age)`)
 * contains parentheses regardless of the split, so `columnName` stays `null` for that shape either
 * way. The effect on `expression` itself is unconditional, independent of `columnName`:
 * `TypeRepository.buildTypeProjectionForQuery` embeds `expression` verbatim in generated KDoc for
 * a computed expression (`selectItem.columnName == null && column.table == null`).
 *
 * @return A pair of (expression, alias). `alias` is `null` when there is no `AS` keyword at all,
 *   AND when there is one but [parseAliasToken] finds nothing that legitimately looks like an
 *   alias right after it (see that function's own KDoc — a trailing comment, an unterminated
 *   quote, or a string literal where an alias should be, none of which contribute a real alias
 *   name).
 */
private fun extractAlias(item: String): Pair<String, String?> {
  // Find the last top-level AS keyword
  var depth = 0
  var lastAsIndex = -1
  var i = 0
  while (i < item.length) {
    val afterToken = skipLexicalToken(item, i)
    if (afterToken != i) {
      i = afterToken
      continue
    }
    when (item[i]) {
      '(' -> depth++
      ')' -> {
        depth--
        // A bare ')' with no matching '(' means [item] is not the well-formed, already-top-level
        // expression this scan assumes — see findTopLevelKeyword's own KDoc on why bailing (rather
        // than clamping depth at 0 and continuing) is the safe direction: an unbalanced scan's
        // assumptions are already void, so a loud "no alias found" is safer than a depth count that
        // silently recovers and may misplace a later real AS keyword. [item] is returned unsplit,
        // the same fallback [extractAlias] already uses when no top-level AS exists at all.
        if (depth < 0) return item to null
      }
      'A', 'a' -> if (depth == 0 && i + 1 < item.length && (item[i + 1] == 'S' || item[i + 1] == 's')) {
        // Check it's the keyword AS (not fused into a longer identifier on either side)
        val before = i == 0 || !isIdentifierChar(item[i - 1])
        val after = i + 2 >= item.length || !isIdentifierChar(item[i + 2])
        if (before && after) {
          lastAsIndex = i
        }
      }
    }
    i++
  }
  return if (lastAsIndex >= 0) {
    item.substring(0, lastAsIndex).trim() to parseAliasToken(item, lastAsIndex + 2)
  } else {
    item to null
  }
}

/**
 * Extracts exactly the alias TOKEN starting at or after [start] in [item] (the position right
 * after the `AS` keyword [extractAlias] already found) — a bare identifier or a double-quoted
 * one, [QUOTED_IDENTIFIER_PATTERN]'s own `""`-escape handling included — discarding anything else
 * around it: leading whitespace/comments before the token (`AS /* c */ ux`), and any TRAILING
 * whitespace/comments after it (`AS ux -- trailing note`, `AS ux /* c */`). [extractAlias]'s own
 * KDoc documents `AS"ux"` (no separating whitespace before a quoted alias) already being
 * recognized as the `AS` keyword itself; this function is what turns whatever textually follows
 * it into the CLEAN alias [foldIdentifier] expects, rather than [extractAlias] returning the raw,
 * unbounded remainder of the item verbatim — which would silently swallow a trailing comment into
 * the "alias" and make it fold-compare unequal to any real name.
 *
 * The quoted branch is escape-aware — via the SAME [QUOTED_IDENTIFIER_PATTERN] compiled pattern
 * [COLUMN_REFERENCE] itself uses, not a second, independently-written scanner — so a doubled `""`
 * inside the alias (`AS "zz""q"`) is correctly treated as an escaped literal `"`, not as the
 * token's end. Getting this wrong is worse than simply missing the escaped case: an
 * escape-UNAWARE scan on `AS "zz""q"` stops at the FIRST `"`, returning the raw token `"zz` (an
 * unterminated-looking fragment) that then still LOOKS quoted to [foldIdentifier] and, once its
 * outer quote is stripped, folds to the SHORTER name `zz` — which CAN collide with an unrelated,
 * genuinely-named `zz` elsewhere in the same body, wrongly matching an outer reference that was
 * never meant to reach this item at all. A punt has to come out as `null`, never a truncated name
 * that happens to still look like a real one.
 *
 * @return The alias token — WITH its surrounding quotes and any internal `""` escape still
 *   attached when quoted, exactly as [foldIdentifier] expects (see its own KDoc on why quotes
 *   must be preserved through to there) — or `null` when there is no legitimate alias token at
 *   all: nothing but whitespace/comments to the end of [item], an unterminated quoted identifier,
 *   a character that can neither start a bare identifier nor open a quoted one (e.g. a
 *   single-quoted STRING LITERAL — `AS 'x'` is not legal PostgreSQL syntax for an alias at all,
 *   and must never be treated as if it named one), OR anything other than trailing
 *   whitespace/comments following the token before the end of [item] (`AS ux zz` is not legal
 *   PostgreSQL either — a second bare word cannot follow a completed alias — so this returns
 *   `null` rather than silently discarding `zz` and keeping just `ux`; discarding unrecognized
 *   input is exactly the failure mode the escape-unaware quoted scan above had).
 */
private fun parseAliasToken(item: String, start: Int): String? {
  val tokenStart = skipWhitespaceAndComments(item, start)
  if (tokenStart >= item.length) return null

  val tokenEnd = when {
    item[tokenStart] == '"' -> {
      val match = QUOTED_IDENTIFIER_PATTERN.matchAt(item, tokenStart) ?: return null
      match.range.last + 1
    }
    isIdentifierStartChar(item[tokenStart]) -> {
      var end = tokenStart + 1
      while (end < item.length && isIdentifierChar(item[end])) end++
      end
    }
    else -> return null
  }

  // Nothing legitimate can follow a real alias inside an already comma-split, FROM-trimmed select
  // item -- only trailing whitespace/comments are tolerated; anything else means this wasn't a
  // clean single alias token, so discard nothing and return null instead.
  if (skipWhitespaceAndComments(item, tokenEnd) != item.length) return null

  return item.substring(tokenStart, tokenEnd)
}

/**
 * Parses a SQL expression into a [SelectItem], determining whether it's a simple column
 * reference (possibly qualified with a table name, either or both parts quoted or unquoted) or a
 * computed expression.
 *
 * A quoted position whose LOGICAL value comes out empty (`SELECT "" FROM t`) is treated as NO
 * match at all — the same `columnName = null`/`tableName = null` fallback as an expression that
 * doesn't match [COLUMN_REFERENCE] to begin with — rather than an empty-string name: PostgreSQL
 * itself rejects a zero-length delimited identifier outright (`zero-length delimited identifier`
 * is a real syntax error), so this shape can never actually reach here from a query PostgreSQL
 * accepted, but an empty non-null name is a worse `null` than `null` itself — it would make
 * `JdbcAnalyzer.buildResultColumns`' `originalName` fall to `""` instead of correctly falling back
 * to `ResultSetMetaData.getColumnName`, exactly the wrong-value-over-no-value mistake this whole
 * file exists to avoid.
 */
private fun parseColumnReference(expression: String): SelectItem {
  val trimmed = expression.trim()
  // A simple column reference is one or two identifiers separated by a dot, with no parentheses or operators
  val match = COLUMN_REFERENCE.matchEntire(trimmed)
  val noMatch = SelectItem(expression = trimmed, columnName = null, tableName = null)
  if (match == null) return noMatch

  val rawTable = match.groups["table"]?.value
  val rawColumn = match.groups["column"]!!.value
  val tableIsQuoted = rawTable?.startsWith('"') == true
  val columnIsQuoted = rawColumn.startsWith('"')
  val column = if (columnIsQuoted) unescapeQuotedIdentifier(rawColumn) else rawColumn
  val table = rawTable?.let { if (tableIsQuoted) unescapeQuotedIdentifier(it) else it }
  if (column.isEmpty() || table?.isEmpty() == true) return noMatch

  return SelectItem(
    expression = trimmed,
    columnName = column,
    tableName = table,
    isColumnNameQuoted = columnIsQuoted,
    isTableNameQuoted = tableIsQuoted,
  )
}

/**
 * Parses PostgreSQL 18's optional `RETURNING WITH (OLD AS alias, NEW AS alias) ...` prologue,
 * which declares a custom name for referring to the `OLD`/`NEW` pseudo-relations in the
 * `RETURNING` list that follows (e.g. `RETURNING WITH (OLD AS o, NEW AS n) o.name, n.name`).
 *
 * @param dml The data-modifying statement.
 * @param afterReturningKeyword The index in [dml] immediately after the `RETURNING` keyword.
 * @return The declared alias names (empty if there is no prologue) paired with the index where
 *   the actual `RETURNING` item list begins — after the prologue's closing `)`, or unchanged if
 *   there is none.
 */
private fun parseOldNewAliasPrologue(dml: String, afterReturningKeyword: Int): Pair<Set<String>, Int> {
  val beforeWith = skipWhitespaceAndComments(dml, afterReturningKeyword)
  val afterWith = skipOptionalKeyword(dml, beforeWith, "WITH")
  if (afterWith == beforeWith || afterWith >= dml.length || dml[afterWith] != '(') {
    return emptySet<String>() to afterReturningKeyword
  }
  val closeParenthesis = findMatchingCloseParenthesis(dml, afterWith)
  if (closeParenthesis < 0) return emptySet<String>() to afterReturningKeyword
  val aliasNames = splitAtTopLevel(dml.substring(afterWith + 1, closeParenthesis), ',')
    .mapNotNull { entry -> parseOldNewAliasName(entry) }
    .toSet()
  return aliasNames to (closeParenthesis + 1)
}

/**
 * Extracts the alias name declared after `AS` in one comma-separated entry of a
 * `RETURNING WITH (OLD AS o, NEW AS n)` prologue (e.g. the `o` in `OLD AS o`), or `null` if
 * [entry] has no top-level `AS`.
 *
 * Whitespace and comments between `AS` and the alias, and trailing the alias before the entry
 * ends, are skipped rather than captured — PostgreSQL accepts both (`OLD AS o /*c*/`, verified
 * live on PostgreSQL 18) and neither is part of the declared name. A naive
 * `substring(asIndex + 2).trim()` captured a trailing comment as part of the alias (`o /*c*/`
 * instead of `o`), which then never matched any real `RETURNING` item reference and silently
 * dropped the alias from the set of recognized `OLD`/`NEW` aliases.
 */
private fun parseOldNewAliasName(entry: String): String? {
  val asIndex = findTopLevelKeyword(entry, "AS")
  if (asIndex < 0) return null
  val afterAs = skipWhitespaceAndComments(entry, asIndex + "AS".length)
  if (afterAs >= entry.length) return null
  if (entry[afterAs] == '"') {
    val afterQuote = skipDoubleQuotedIdentifier(entry, afterAs)
    return entry.substring(afterAs + 1, (afterQuote - 1).coerceAtLeast(afterAs + 1))
  }
  var end = afterAs
  while (end < entry.length && isIdentifierChar(entry[end])) end++
  return entry.substring(afterAs, end)
}
