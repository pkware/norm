package norm.generator

/**
 * A parsed item from a SQL SELECT clause.
 *
 * @property expression The full SQL expression text (e.g. `COUNT(*)`, `author.name`, `book.title`).
 * @property columnName The column's logical name for a simple column reference — `null` for a
 *   computed expression. For a quoted reference (`"My Col"`, `"He""llo"`), this is the identifier
 *   PostgreSQL itself resolves to: surrounding quotes removed and any doubled `""` escape
 *   collapsed to the single literal `"` it represents. On PostgreSQL 18,
 *   `ResultSetMetaData.getColumnName` for `SELECT "He""llo" FROM (SELECT 1 AS "He""llo") s`
 *   reports `He"llo` — no quotes, escape already collapsed — which this property must agree with.
 *   Never the raw, quote-decorated source text; see [isColumnNameQuoted] for how the
 *   quoted/unquoted distinction is preserved instead.
 * @property tableName The table qualifier's logical name for a qualified reference (e.g. `author`
 *   in `author.name`, or `My Table` in `"My Table".name`) — same logical-value convention as
 *   [columnName], and `null` for an unqualified reference or a computed expression.
 * @property isColumnNameQuoted Whether [columnName] came from a quoted source identifier — `false`
 *   when [columnName] is `null`. [columnName] no longer carries the quotes that would otherwise
 *   signal whether PostgreSQL folds it (quoted: case preserved) or not (unquoted: folded to
 *   lowercase), so this flag is what a caller doing that folding must consult instead.
 * @property isTableNameQuoted Whether [tableName] came from a quoted source identifier — same
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
 * Paired positionally against `java.sql.ResultSetMetaData` columns, so the search is restricted to
 * the statement's main query — after any leading `WITH` clause's CTEs — and `SELECT` is located
 * with [findTopLevelKeyword] (depth-0, lexically aware), so a nested `SELECT` or a keyword-like
 * substring inside a literal/comment is never mistaken for the real clause. `RETURNING` is located
 * with [findTopLevelReturningKeyword] instead, and is only searched for when the window's own
 * leading keyword is DML (`INSERT`/`UPDATE`/`DELETE`/`MERGE`), since `RETURNING` is not reserved
 * and is otherwise legal as a plain `SELECT`'s column alias.
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
 * [skipOptionalSetQuantifier] before the clause is split into items, so the first item's
 * `expression`/`columnName` reflect the bare column, not the quantifier glued onto it (`SELECT
 * DISTINCT x, id FROM t` → first item is `x`, not `DISTINCTx`). This must happen here, not inside
 * [isStarItem]: [isStarItem]'s own whitespace-stripping turns `ALL 2.*a` into `ALL2.*a` and a
 * genuine star on a table named `all2` into `all2.*a`, which differ only in the case of letters
 * an unquoted identifier reference folds anyway; only stripping the quantifier first, while
 * whitespace is still intact, tells them apart. `SELECT ALL 2.*a lbl, b FROM t` is valid
 * arithmetic (2 columns), distinct from `SELECT all2.* a, p FROM all2` (3 columns, a real star on
 * table `all2`).
 *
 * A star item (`*`/`table.*`, via [isStarItem]) expands to however many columns the starred
 * relation has, shifting every later item onto the wrong metadata column; an item before a star is
 * unaffected by that unknown width, so only the first star and everything after it is dropped — a
 * lone star is left alone, and items at/after a star fall back to metadata unresolved.
 *
 * @return Items strictly before the first star, if any; the full list if there is no star or it's
 *   a single star item; or empty if the output clause can't be found (e.g. a `VALUES` list, a
 *   `TABLE` shorthand, or two separately parenthesized set-operation branches like `(SELECT a)
 *   UNION (SELECT b)`). Both consumers degrade safely for a missing item, falling back to
 *   `ResultSetMetaData.getColumnName` rather than reporting a wrong original name.
 */
internal fun parseSelectItems(sql: String): List<SelectItem> = parseOutputItemsWithAlias(sql).map { it.selectItem }

/**
 * The single window both [hasTopLevelSetOperation] and [parseOutputItemsWithAlias] scan: [sql]'s
 * main query, after any leading `WITH` clause ([parseCteClause]'s
 * [ParsedCteClause.mainQueryStart]), with any redundant outer `(`...`)` pair stripped via
 * [stripRedundantOuterParentheses].
 *
 * A guard and the parser it guards must see the same text: without sharing this function, a set
 * operation wrapped in one parenthesis pair (`(SELECT a UNION SELECT b)`) sits at paren depth one in
 * the raw text, invisible to a guard that skips the stripping step the parser applies.
 */
private fun mainQueryWindow(sql: String): String {
  val mainQueryStart = parseCteClause(sql)?.mainQueryStart ?: 0
  return stripRedundantOuterParentheses(sql.substring(mainQueryStart))
}

/**
 * Whether [sql]'s main query ([mainQueryWindow]) contains a top-level `UNION`/`INTERSECT`/`EXCEPT`
 * keyword: this statement's own visible `SELECT`/`RETURNING` list is only one branch of a set
 * operation, so [parseSelectItems] parsed only that branch's items, never the other branch(es)'
 * own (possibly differently-computed) expressions for the same result column. A bare column
 * reference is unaffected: PostgreSQL itself names a set operation's whole result column after
 * branch 1 alone.
 *
 * All three keywords are PostgreSQL reserved words, so an unquoted occurrence can never be a
 * column/table identifier or alias.
 */
internal fun hasTopLevelSetOperation(sql: String): Boolean {
  val window = mainQueryWindow(sql)
  return SET_OPERATION_KEYWORDS.any { keyword -> findTopLevelKeyword(window, keyword) >= 0 }
}

private val SET_OPERATION_KEYWORDS = listOf("UNION", "INTERSECT", "EXCEPT")

/**
 * A single item from [parseOutputItemsWithAlias], pairing the [selectItem] parsed from the
 * item's expression (alias stripped, exactly what [parseSelectItems] itself exposes) with the
 * [alias] that was stripped off, if any.
 *
 * Kept separate from [SelectItem] because [SelectItem.columnName] already carries a meaning for
 * simple column references — reusing it for an alias would conflate "this item is a bare column
 * reference" with "this item has an alias", which are different facts a computed expression can
 * have independently (`UPPER(description) AS description_upper` has an alias but is not a bare
 * column reference; `description AS description_upper` is a bare column reference and has an
 * alias).
 *
 * @property selectItem The parsed expression/columnName/tableName, alias already removed.
 * @property alias The alias text after `AS`, or `null` if the item has no alias.
 */
internal data class OutputItemWithAlias(val selectItem: SelectItem, val alias: String?)

/**
 * The shared parsing core behind both [parseSelectItems] and [resolveNodeTreeProvenanceExpression]
 * (which needs [OutputItemWithAlias.alias] to cross-validate a CTE body item's own name against
 * the node tree's authoritative `:resname`). Locates the same output clause [parseSelectItems]
 * documents finding — the window/`RETURNING`-gating/star-truncation rules there apply identically
 * here.
 */
internal fun parseOutputItemsWithAlias(sql: String): List<OutputItemWithAlias> {
  val window = mainQueryWindow(sql)

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
    // first item's own expression; skip past it so itemsStart lands on the real first item.
    itemsStart = parseOldNewAliasPrologue(window, afterKeyword)
    // RETURNING clauses are terminal — no FROM keyword follows
    hasFromClause = false
  } else if (selectIndex >= 0) {
    afterKeyword = selectIndex + "SELECT".length
    itemsStart = skipOptionalSetQuantifier(window, afterKeyword)
    hasFromClause = true
  } else {
    return emptyList()
  }

  val fromIndex = if (hasFromClause) findTopLevelFromClauseKeyword(window, afterKeyword) else -1
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
 * Strips a redundant `(`...`)` pair wrapping [text]'s entire remaining content, repeatedly, as
 * long as one remains — `(SELECT ...)`, `((SELECT ...))`, etc.
 *
 * A CTE body (or a whole top-level query) may legally wrap its `SELECT`/`RETURNING`/DML statement
 * in one or more redundant parenthesis pairs. Without stripping them first, [findTopLevelKeyword]
 * never finds an unquoted `SELECT`/`RETURNING` at paren depth zero: the extra, unmatched leading
 * `(` puts the whole rest of the text at depth one instead, so [parseOutputItemsWithAlias] returns
 * no items at all for an otherwise-ordinary body.
 *
 * A pair only counts as wrapping the entire remaining text when the first non-whitespace/comment
 * character is `(` and its own matching close parenthesis is the last non-whitespace/comment
 * character in [text] — never merely "starts with ( and ends with )", which would also match two
 * separately parenthesized set-operation branches (`(SELECT a) UNION (SELECT b)`), where the first
 * `(`'s own match is nowhere near the end.
 */
private fun stripRedundantOuterParentheses(text: String): String {
  var current = text
  while (true) {
    val start = skipWhitespaceAndComments(current, 0)
    if (start >= current.length || current[start] != '(') return current
    val closeParenthesis = findMatchingCloseParenthesis(current, start)
    if (closeParenthesis < 0) return current
    if (skipWhitespaceAndComments(current, closeParenthesis + 1) != current.length) return current
    current = current.substring(start + 1, closeParenthesis)
  }
}

/**
 * Skips an optional leading SQL set quantifier starting at or after [position] in [sql]: `ALL`, or
 * `DISTINCT` optionally followed by `ON (` ... `)`.
 *
 * [position] is the index right after the `SELECT` keyword, still followed by whitespace: this
 * function skips that whitespace/comments first, then tries `ALL`/`DISTINCT` at the resulting
 * position. [skipOptionalKeyword]'s own word-boundary check is what keeps `ALL`/`DISTINCT` from
 * matching a longer identifier that merely starts with those letters (`all2`,
 * `distinctive_column`).
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
 * `CAST(x AS text) AS my_col` correctly identifies `my_col` as the alias. Skips string literals,
 * quoted identifiers, dollar-quoted strings, and comments via [skipLexicalToken], so an `AS`-like
 * substring or an unbalanced paren inside one of those is not mistaken for a real `AS` keyword or
 * a real parenthesis.
 *
 * Tracks `(`/`)` only, not `[`/`]`: [extractAlias] only ever receives an item already split at the
 * top level ([splitAtTopLevel]), so any `[`/`]` pair it contains is already self-balanced and
 * cannot itself hold an unmatched `(`/`)`.
 *
 * The word-boundary check on either side of a candidate `AS`/`as` uses [isIdentifierChar] rather
 * than `Char.isWhitespace()`: PostgreSQL's `AS` keyword only needs to not be fused into a longer
 * identifier on either side, not to be surrounded by literal whitespace. On PostgreSQL 18.4,
 * `SELECT (1)AS b` returns column `b` — `AS` directly abuts the closing `)` with no whitespace,
 * and `)` is not an identifier character, so this is the real keyword. Likewise `SELECT a AS"b",
 * id FROM t` (columns `b`, `id`, with `a` as the first column's source) recognizes `AS"b"` as the
 * keyword since `"` is not an identifier character either. Conversely `SELECT 1 AS$b` returns
 * column `as$b`, a single implicit alias identifier — `$` is an identifier-continuation character,
 * so `AS$b` is one word, not the keyword `AS` followed by `$b`.
 *
 * @return A pair of (expression, alias). `alias` is `null` when there is no `AS` keyword at all,
 *   and when there is one but [parseAliasToken] finds nothing that legitimately looks like an
 *   alias right after it: a trailing comment, an unterminated quote, or a string literal where an
 *   alias should be, none of which contribute a real alias name.
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
        // A bare ')' with no matching '(' means [item] isn't the well-formed, already-top-level
        // expression this scan assumes. Bail rather than clamp depth at 0 and continue: returning
        // [item] unsplit is safer than a depth count that silently recovers and may misplace a
        // later real AS keyword.
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
 * Extracts exactly the alias token starting at or after [start] in [item] (the position right
 * after the `AS` keyword [extractAlias] already found) — a bare identifier or a double-quoted one,
 * discarding any leading/trailing whitespace or comments around it (`AS /* c */ ux -- note`).
 *
 * The quoted branch is escape-aware: a doubled `""` inside the alias (`AS "zz""q"`) is treated as
 * an escaped literal `"`, not the token's end. An escape-unaware scan would stop at the first `"`,
 * returning the truncated fragment `"zz`, which folds to the shorter name `zz`, wrongly colliding
 * with an unrelated `zz` elsewhere in the same body.
 *
 * @return The alias token — with its surrounding quotes and any internal `""` escape still
 *   attached when quoted — or `null` when there is no legitimate alias token: nothing but
 *   whitespace/comments to the end of [item], an unterminated quoted identifier, a character that
 *   can neither start a bare identifier nor open a quoted one (e.g. `AS 'x'`, not legal
 *   PostgreSQL), or anything other than trailing whitespace/comments following the token (`AS ux
 *   zz` is not legal PostgreSQL either, so this returns `null` rather than silently discarding
 *   `zz`).
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
 * A quoted position whose logical value comes out empty (`SELECT "" FROM t`) is treated as no
 * match at all (`columnName = null`/`tableName = null`), never an empty-string name: PostgreSQL
 * itself rejects a zero-length delimited identifier outright (`SELECT "" FROM t` is a syntax
 * error), so this shape can never actually reach here from an accepted query, but an empty
 * non-null name would still be a worse `null` than `null` itself.
 *
 * An unquoted column/table name is folded via [foldAsciiCase] — PostgreSQL's own
 * `downcase_identifier` behavior, ASCII `A`-`Z` only — so [SelectItem.columnName]/
 * [SelectItem.tableName] agree with what `ResultSetMetaData.getColumnName` reports for the same
 * reference. A quoted name is never folded — quoting is how PostgreSQL preserves a name's
 * original case against this default folding.
 */
internal fun parseColumnReference(expression: String): SelectItem {
  val trimmed = expression.trim()
  // A simple column reference is one or two identifiers separated by a dot, with no parentheses or operators
  val match = COLUMN_REFERENCE.matchEntire(trimmed)
  val noMatch = SelectItem(expression = trimmed, columnName = null, tableName = null)
  if (match == null) return noMatch

  val rawTable = match.groups["table"]?.value
  val rawColumn = match.groups["column"]!!.value
  val tableIsQuoted = rawTable?.startsWith('"') == true
  val columnIsQuoted = rawColumn.startsWith('"')
  // Truncated here so callers comparing these against server-reported names, such as
  // catalog.findColumn, are comparing like with like.
  val column = truncateIdentifier(if (columnIsQuoted) unescapeQuotedIdentifier(rawColumn) else foldAsciiCase(rawColumn))
  val table = rawTable?.let {
    truncateIdentifier(if (tableIsQuoted) unescapeQuotedIdentifier(it) else foldAsciiCase(it))
  }
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
 * Skips PostgreSQL 18's optional `RETURNING WITH (OLD AS alias, NEW AS alias) ...` prologue,
 * which declares a custom name for referring to the `OLD`/`NEW` pseudo-relations in the
 * `RETURNING` list that follows (e.g. `RETURNING WITH (OLD AS o, NEW AS n) o.name, n.name`).
 *
 * @param dml The data-modifying statement.
 * @param afterReturningKeyword The index in [dml] immediately after the `RETURNING` keyword.
 * @return The index where the actual `RETURNING` item list begins — after the prologue's closing
 *   `)`, or unchanged if there is none.
 */
private fun parseOldNewAliasPrologue(dml: String, afterReturningKeyword: Int): Int {
  val beforeWith = skipWhitespaceAndComments(dml, afterReturningKeyword)
  val afterWith = skipOptionalKeyword(dml, beforeWith, "WITH")
  if (afterWith == beforeWith || afterWith >= dml.length || dml[afterWith] != '(') {
    return afterReturningKeyword
  }
  val closeParenthesis = findMatchingCloseParenthesis(dml, afterWith)
  if (closeParenthesis < 0) return afterReturningKeyword
  return closeParenthesis + 1
}
