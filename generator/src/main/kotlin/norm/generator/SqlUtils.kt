package norm.generator

/**
 * Matches `func_name(` to find the start of function calls — the captured name is a PostgreSQL
 * unquoted identifier ([COLUMN_REFERENCE_IDENTIFIER_START] followed by zero or more
 * [COLUMN_REFERENCE_IDENTIFIER_CONTINUATION] characters, the same identifier shape
 * [COLUMN_REFERENCE] uses), never a bare `\w+`: `\w` excludes both `$` and any `>= 0x80`
 * character, both of which PostgreSQL admits after an identifier's first character (see
 * [isIdentifierChar]). For `SELECT my$fn(?)`, `\w+` cannot match `my$fn` as one run (`$` breaks
 * it), so `findAll` instead matches the shorter run `fn` immediately before the `(` — handing
 * `SqlParameterInferrer.extractFunctionCalls` the wrong function name, `fn` instead of `my$fn`.
 * Verified against a real PostgreSQL 18.4: `CREATE FUNCTION "my$fn"(...)` and the unquoted call
 * `my$fn(...)` both resolve to the same function, and an unquoted `>= 0x80`-named function
 * (`fn€(...)`) is likewise legal, while a digit-led name (`2fn(...)`) is rejected outright
 * ("trailing junk after numeric literal") — exactly the identifier shape this regex now encodes.
 */
internal val FUNCTION_CALL_START = Regex(
  """($COLUMN_REFERENCE_IDENTIFIER_START$COLUMN_REFERENCE_IDENTIFIER_CONTINUATION*)\(""",
)

/** SQL keywords to exclude when matching function calls. */
internal val SQL_KEYWORDS = setOf(
  "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
  "DELETE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "ON",
  "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "EXCEPT",
  "INTERSECT", "AS", "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN",
  "CASE", "WHEN", "THEN", "ELSE", "END", "CAST", "IS", "LIKE", "ILIKE",
  "CALL", "DO", "WITH", "RETURNING", "CONFLICT",
)

/**
 * Finds the index of the closing parenthesis that matches an opening `(` at [openParenthesisIndex].
 *
 * Skips over string literals, quoted identifiers, dollar-quoted strings, and comments via
 * [skipLexicalToken] so a `(`/`)` that only appears inside one of those (e.g. `RETURNING
 * regexp_replace(name, '\(', '')`, where the string literal contains an unbalanced `(`) is never
 * mistaken for a real parenthesis.
 *
 * @param text The string to search.
 * @param openParenthesisIndex The index of the opening `(`. The search starts at `openParenthesisIndex + 1`.
 * @return The index of the matching `)`, or `-1` if unbalanced.
 */
internal fun findMatchingCloseParenthesis(text: String, openParenthesisIndex: Int): Int {
  var depth = 1
  var i = openParenthesisIndex + 1
  while (i < text.length && depth > 0) {
    val afterToken = skipLexicalToken(text, i)
    if (afterToken != i) {
      i = afterToken
      continue
    }
    when (text[i]) {
      '(' -> depth++
      ')' -> depth--
    }
    i++
  }
  return if (depth == 0) i - 1 else -1
}

/**
 * Finds the best matching [FunctionOverload] for a call with [argCount] arguments.
 *
 * Prefers an exact match by argument count. Falls back to overloads with more arguments
 * (default parameters) or overloads with no named arguments (variadic/generic).
 */
internal fun findOverload(overloads: List<FunctionOverload>, argCount: Int): FunctionOverload? =
  overloads.find { it.argNames.size == argCount || it.argNames.isEmpty() }
    ?: overloads.find { it.argNames.size >= argCount }

/**
 * Splits text on a delimiter character, respecting nested parentheses AND square brackets.
 *
 * For `"EXISTS(...) AS valid, col1"` split on `,`, returns `["EXISTS(...) AS valid", "col1"]`.
 *
 * Both bracket kinds share ONE depth counter rather than two independently-tracked ones: SQL
 * never interleaves them invalidly (a `[` is always closed by its own `]` before any enclosing
 * `(` closes, and vice versa), so treating `(`/`[` as "one level deeper" and `)`/`]` as "one level
 * shallower" — regardless of which bracket kind opened that level — is sufficient to find the
 * REAL top-level delimiters. Square brackets were NOT tracked here originally: an `ARRAY[1, 2]`
 * item split into two, both because it introduces a delimiter-looking comma with no enclosing
 * `(...)` to hide it (`ARRAY[1, 2] AS arr, OLD.tval AS oldv` — verified against real Postgres to
 * split 2 real columns into 3 items) and, worse, because that error could silently CANCEL OUT a
 * separate star-caused split error elsewhere in the same list, making
 * [oldOrNewReturningColumns]'s real-column-count cross-check see a coincidentally-matching count
 * and trust a garbled, wrongly-indexed split (`tgt . *, OLD.tval AS oldv, ARRAY[1, 2] AS arr` —
 * verified against real Postgres to split into 4 items against 4 real columns, the SAME count,
 * while the split itself is `["tgt . *", "OLD.tval AS oldv", "ARRAY[1", "2] AS arr"]` — nothing
 * about that split's actual items corresponds to the real 4 columns).
 *
 * Skips string literals, quoted identifiers, dollar-quoted strings, and comments via
 * [skipLexicalToken], so a `(`/`)`/`[`/`]`/[delimiter] that only appears inside one of those (e.g.
 * a string literal containing a stray `,` or unbalanced bracket) is not mistaken for a real one.
 *
 * USER-VISIBLE EFFECT OF TRACKING `[...]` (beyond the nullability fixes above): this function is
 * also used by `SqlParameterInferrer.extractFunctionCalls` (a function call's comma-separated
 * arguments) and `SqlParameterInferrer.extractValuesExpressions` (an `INSERT ... VALUES (...)`
 * clause's comma-separated expressions) to attribute each `?` placeholder to its argument/column
 * position for parameter-NAME inference. The precondition for a naming change is: a multi-element
 * `ARRAY[...]` literal (2+ placeholders inside it) shares an argument list or `VALUES` list with
 * ANY other placeholder — INCLUDING placeholders INSIDE the array itself, and regardless of
 * whether any placeholder follows the array at all:
 * - `INSERT INTO arrs2(id, tags) VALUES (?, ARRAY[?, ?])` (nothing follows the array) still
 *   renames: `id, tags, p3` (`main`) → `id, tags, tags2` (`HEAD`, after
 *   `SqlStatement.deduplicatedParameterNames` suffixes the repeated `tags`).
 * - `array_position(ARRAY[?::int, ?::int], 5)` (no placeholder anywhere outside the array) still
 *   renames: `array_position_param1, array_position_param2` (`main`) → `p1, p2` (`HEAD`).
 * - `array_position(ARRAY[?::int, ?::int], ?::int)` renames ALL THREE, not just the one after the
 *   array: `array_position_param{1,2,3}` (`main`) → `p1, p2, array_position_param2` (`HEAD`).
 *
 * This is a BREAKING change for named-argument callers of generated code for any query matching
 * that precondition — but it is NOT simply "old wrong, new right" for every renamed placeholder.
 * For a `VALUES` list feeding real target-table columns, `HEAD` is unambiguously more correct
 * (real column names, not a generic `pN` shifted onto the wrong column, or a name that collides
 * with — and gets suffixed after — an earlier one). For placeholders INSIDE the array argument to
 * a function call, `main`'s naming was an ACCIDENT of the same split bug the nullability fixes
 * closed: the broken split treated `ARRAY[?::int, ?::int]` as two separate one-placeholder
 * "arguments", so `SqlParameterInferrer.inferFunctionArgNames`'s `paramPositions.size == 1` check
 * (see its KDoc) happened to fire for both, minting a function-context name
 * (`array_position_param1`/`param2`) for each — structurally WRONG, since `array_position` only
 * has two real arguments, not three. `HEAD` correctly sees ONE argument containing two
 * placeholders, for which that check does not fire at all (it has no strategy for naming multiple
 * placeholders sharing one argument slot), so those two placeholders fall through to the generic
 * positional default (`p1`, `p2`) — an honest "no name available" rather than a confidently wrong
 * one, but also a loss of the (accidental) function-context `main` provided. The ONE real
 * remaining top-level argument (the scalar `5`/`?::int`) gets `array_position_param2` on `HEAD` —
 * correctly its true argument index — where `main` numbered it `param3`, one too high, because it
 * believed the array contributed two arguments instead of one.
 */
internal fun splitAtTopLevel(text: String, delimiter: Char): List<String> {
  val items = mutableListOf<String>()
  var depth = 0
  var start = 0
  var i = 0
  while (i < text.length) {
    val afterToken = skipLexicalToken(text, i)
    if (afterToken != i) {
      i = afterToken
      continue
    }
    when (text[i]) {
      '(', '[' -> depth++
      ')', ']' -> depth--
      delimiter -> if (depth == 0) {
        items.add(text.substring(start, i))
        start = i + 1
      }
    }
    i++
  }
  items.add(text.substring(start))
  return items
}

/**
 * A parsed item from a SQL SELECT clause.
 *
 * @property expression The full SQL expression text (e.g. `COUNT(*)`, `author.name`, `book.title`).
 * @property columnName The original column name for simple column references. `null` for computed expressions.
 * @property tableName The table qualifier for qualified column references (e.g. `author` in `author.name`).
 *   `null` for unqualified references and computed expressions.
 */
internal data class SelectItem(val expression: String, val columnName: String?, val tableName: String?)

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
internal fun parseSelectItems(sql: String): List<SelectItem> {
  val mainQueryStart = parseCteClause(sql)?.mainQueryStart ?: 0
  val window = sql.substring(mainQueryStart)

  // See this function's KDoc for why RETURNING is gated on the main query's own leading keyword.
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
    itemsStart = afterKeyword
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
    val (expression, _) = extractAlias(item)
    parseColumnReference(expression)
  }

  val firstStarIndex = items.indexOfFirst { isStarItem(it.expression) }
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
 * The word-boundary check on either side of a candidate `AS`/`as` uses [isIdentifierChar] — the
 * same predicate [findTopLevelKeyword] and every other keyword scanner in this file use — rather
 * than `Char.isWhitespace()`: PostgreSQL's `AS` keyword only needs to NOT be fused into a longer
 * identifier on either side, not to be surrounded by literal whitespace. Verified against
 * PostgreSQL 18.4: `SELECT (1)AS b` returns column `b` — `AS` directly abuts the closing `)` with
 * no whitespace, and `)` is not an identifier character, so this is the real keyword. Conversely
 * `SELECT 1 AS$b` returns column `as$b`, a SINGLE implicit alias identifier — `$` is a valid
 * identifier-continuation character (see [isIdentifierChar]), so `AS$b` is one word, not the
 * keyword `AS` followed by `$b`, and the whitespace-based check happened to reject this case for
 * the same reason (`$` is not whitespace either), while the identifier-based check rejects it for
 * the actually-correct reason (`$` continues an identifier). Before this fix, requiring literal
 * whitespace was STRICTER than PostgreSQL's own grammar: whenever `AS` abutted a non-whitespace,
 * non-identifier character such as `)` or a closing quote with no separating space, the keyword
 * went unrecognized, so the alias was never split off — the raw, unsplit item (e.g. `(age)AS b`)
 * was returned as the `expression` half verbatim, instead of the correctly-split `(age)`.
 *
 * This DOES change `columnName`, and for the better, whenever the glued alias prevents the
 * pre-`AS` text from matching [COLUMN_REFERENCE] on its own but the split-off expression matches
 * it once separated. `columnName` is derived by [parseSelectItems] from the split `expression` via
 * [parseColumnReference] (the returned alias itself is discarded — [parseSelectItems] is this
 * function's only caller, and does `val (expression, _) = extractAlias(item)`), so whatever the
 * split changes `expression` to feeds directly into that derivation. Verified against PostgreSQL
 * 18.4: `SELECT a AS"b", id FROM t` is valid (columns `b`, `id`; PostgreSQL reports `a` as the
 * source column of the first result column). On `main`, the boundary check requires literal
 * whitespace after `AS`, so `AS"b"` (no space before the quote) is not recognized as the keyword —
 * the whole glued item `a AS"b"` is returned as `expression` unsplit, which does not match
 * [COLUMN_REFERENCE] (it contains a space and an embedded quote), giving `columnName=null`. On this
 * branch, the identifier-character boundary check correctly recognizes `AS` here (`"` is not an
 * identifier character, so the right-hand boundary holds without requiring whitespace), splitting
 * the item into `expression="a"`, which DOES match [COLUMN_REFERENCE], giving `columnName="a"` —
 * the name PostgreSQL itself reports. Not every glued case benefits this way: a glued expression
 * like `(age)AS b` still fails to match [COLUMN_REFERENCE] once split, because the split-off
 * `expression` (`(age)`) contains parentheses regardless of the split, so `columnName` stays `null`
 * for that shape either way. The effect on `expression` itself is unconditional, independent of
 * `columnName`: `TypeRepository.buildTypeProjectionForQuery` embeds `expression` verbatim in
 * generated KDoc for a computed expression (`selectItem.columnName == null && column.table ==
 * null`), so that embedded text now reflects the correctly-split SQL (`(age)`) instead of the
 * alias-contaminated one (`(age)AS b`) for that boundary shape.
 *
 * @return A pair of (expression, alias) where alias is `null` if there is no `AS` keyword.
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
      ')' -> depth--
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
    item.substring(0, lastAsIndex).trim() to item.substring(lastAsIndex + 2).trim()
  } else {
    item to null
  }
}

/**
 * Parses a SQL expression into a [SelectItem], determining whether it's a simple
 * column reference (possibly qualified with a table name) or a computed expression.
 */
private fun parseColumnReference(expression: String): SelectItem {
  val trimmed = expression.trim()
  // A simple column reference is one or two identifiers separated by a dot, with no parentheses or operators
  val match = COLUMN_REFERENCE.matchEntire(trimmed)
  return if (match != null) {
    val table = match.groups["table"]?.value
    val column = match.groups["column"]!!.value
    SelectItem(expression = trimmed, columnName = column, tableName = table)
  } else {
    SelectItem(expression = trimmed, columnName = null, tableName = null)
  }
}

/**
 * Finds a SQL keyword at the top level (not inside parentheses) in the given string.
 *
 * Skips over string literals, quoted identifiers, dollar-quoted strings, and comments via
 * [skipLexicalToken] so a keyword-like word or a `(`/`)` that only appears inside one of those
 * (e.g. `SET name = 'copied from source'`, which contains the word `from`) is not mistaken for
 * a real keyword or a real parenthesis. A candidate match is also rejected — via [isIdentifierChar]
 * — when it is adjacent to any character PostgreSQL allows inside an unquoted identifier, so
 * `valid_from`, `from_date`, `data_set`, and similar ordinary column/table names are never
 * mistaken for the keywords `FROM`/`SET` they merely contain as a substring.
 *
 * @return The index of the keyword, or `-1` if not found at the top level.
 */
internal fun findTopLevelKeyword(sql: String, keyword: String, startIndex: Int = 0): Int {
  var depth = 0
  var i = startIndex
  while (i <= sql.length - keyword.length) {
    val afterToken = skipLexicalToken(sql, i)
    if (afterToken != i) {
      i = afterToken
      continue
    }
    when (sql[i]) {
      '(' -> {
        depth++
        i++
      }
      ')' -> {
        depth--
        i++
      }
      else -> {
        if (depth == 0 && sql.regionMatches(i, keyword, 0, keyword.length, ignoreCase = true)) {
          val before = i == 0 || !isIdentifierChar(sql[i - 1])
          val after = i + keyword.length >= sql.length || !isIdentifierChar(sql[i + keyword.length])
          if (before && after) return i
        }
        i++
      }
    }
  }
  return -1
}

/**
 * Finds a SQL keyword ANYWHERE in the string, including nested inside parentheses — unlike
 * [findTopLevelKeyword], this does not stop at paren depth 0. Still skips string literals,
 * quoted identifiers, dollar-quoted strings, and comments via [skipLexicalToken], and still
 * applies the same [isIdentifierChar] word-boundary check, so the same false positives
 * [findTopLevelKeyword] avoids (a keyword-like substring inside a literal, or inside an ordinary
 * identifier) are avoided here too.
 *
 * Used by scanners whose job is to detect a keyword's mere PRESENCE anywhere in the text — e.g.
 * [hasOuterJoin], which must notice a `LEFT JOIN` nested inside a `FROM`/`USING`/`MERGE ...
 * USING` subquery (`USING (SELECT ... FROM src LEFT JOIN sx ON ...) s`) — not to parse top-level
 * statement structure, where depth-0-only matching is what's correct instead.
 *
 * @return The index of the keyword, or `-1` if not found anywhere.
 */
internal fun findKeywordAtAnyDepth(sql: String, keyword: String, startIndex: Int = 0): Int {
  var i = startIndex
  while (i <= sql.length - keyword.length) {
    val afterToken = skipLexicalToken(sql, i)
    if (afterToken != i) {
      i = afterToken
      continue
    }
    if (sql.regionMatches(i, keyword, 0, keyword.length, ignoreCase = true)) {
      val before = i == 0 || !isIdentifierChar(sql[i - 1])
      val after = i + keyword.length >= sql.length || !isIdentifierChar(sql[i + keyword.length])
      if (before && after) return i
    }
    i++
  }
  return -1
}

/**
 * Finds the top-level `RETURNING` clause keyword in [sql] — distinguishing it from a bare
 * `returning` used as an explicit `AS returning` column alias, which is otherwise legal PostgreSQL
 * syntax (verified against PostgreSQL 18.4: `CREATE TABLE bad (returning int)`, `FROM t returning`,
 * `t AS returning`, and the implicit alias `SELECT email returning FROM users` are ALL syntax
 * errors — an explicit `AS returning` column alias is the ONLY position a bare `returning` token
 * is legal in). A plain [findTopLevelKeyword] search returns the FIRST match, which can be that
 * alias rather than the real clause (`SELECT email AS returning, x FROM t RETURNING id` — the
 * alias comes first); this function instead returns the first `RETURNING` NOT immediately preceded
 * by the word `AS`, which is exact rather than heuristic given the grammar fact above — an alias
 * position is always `AS`-preceded, and the real clause keyword never is.
 *
 * A forward single-pass walk in the style of [findTopLevelKeyword]: parenthesis depth is tracked
 * so only a depth-0 `RETURNING` counts, and [skipLexicalToken] skips string literals, quoted
 * identifiers, dollar-quoted strings, and comments so a keyword-like substring inside one of those
 * is never mistaken for a real keyword. A COMMENT does NOT disturb the "was the previous word
 * `AS`" state — a comment between `AS` and its alias is a separator, not a token (verified valid:
 * both `SELECT 1 AS/*c*/returning` and `SELECT 1 AS--x` followed by a newline then `returning` are
 * legal syntax) — while a string literal, quoted identifier, or dollar-quoted string DOES clear
 * that state, since none of those can themselves be the word `AS`. Plain whitespace, like a
 * comment, is also not disturbing: it is the ordinary separator between `AS` and an unquoted
 * alias, which is the common case this function must not break. Any other character (a
 * parenthesis, comma, or operator) clears the state, since none of those can be the word `AS`
 * either. Word-boundary handling is inherently correct here, unlike a substring search would be,
 * because the walk consumes whole words at a time via [isIdentifierChar] — an ordinary identifier
 * like `returning_batch`, or one continuing with a `>= 0x80` character like `returning€`, is read
 * as ONE word, never mistaken for the bare keyword (issue #219: `returning€` is a legal PostgreSQL
 * column name — PostgreSQL's lexer admits any byte `>= 0x80` inside an unquoted identifier — so
 * stopping the word scan at `€` and seeing the bare word `returning` misidentified the alias
 * position as the real clause).
 *
 * The identifier branch is checked BEFORE the whitespace branch, deliberately: Kotlin's
 * `Char.isWhitespace()` is `true` for several `>= 0x80` characters PostgreSQL does NOT treat as
 * whitespace at all (e.g. U+00A0 NO-BREAK SPACE, U+2000-U+200A the various Unicode spaces, U+3000
 * IDEOGRAPHIC SPACE) — every character PostgreSQL's own lexer treats as whitespace is ASCII
 * (`< 0x80`), so [isIdentifierChar]'s wide `>= 0x80` branch never conflicts with a genuine
 * PostgreSQL whitespace character. Checking whitespace first would let one of those non-ASCII
 * "whitespace" characters act as an ordinary separator between two otherwise-adjacent words —
 * splitting what PostgreSQL's lexer reads as ONE identifier into a leading word that can, in turn,
 * be misread as the bare `RETURNING` keyword.
 *
 * @return The index of the keyword, or `-1` if there is no top-level `RETURNING` that isn't itself
 *   an `AS`-preceded column alias.
 */
internal fun findTopLevelReturningKeyword(sql: String): Int {
  var depth = 0
  var previousWordIsAs = false
  var i = 0
  while (i < sql.length) {
    val afterToken = skipLexicalToken(sql, i)
    if (afterToken != i) {
      val isComment = sql[i] == '-' || sql[i] == '/'
      if (!isComment) previousWordIsAs = false
      i = afterToken
      continue
    }
    when {
      isIdentifierChar(sql[i]) -> {
        val wordStart = i
        while (i < sql.length && isIdentifierChar(sql[i])) i++
        val word = sql.substring(wordStart, i)
        if (depth == 0 && word.equals("RETURNING", ignoreCase = true) && !previousWordIsAs) {
          return wordStart
        }
        previousWordIsAs = word.equals("AS", ignoreCase = true)
      }
      sql[i].isWhitespace() -> i++
      else -> {
        when (sql[i]) {
          '(' -> depth++
          ')' -> depth--
        }
        previousWordIsAs = false
        i++
      }
    }
  }
  return -1
}

/**
 * True if [character] can appear inside an unquoted PostgreSQL identifier, at any position after
 * the first: a letter, digit, underscore, dollar sign, or any character whose code is `>= 0x80`.
 * PostgreSQL's actual identifier-continuation class (`scan.l`'s `ident_cont`) is wider than a
 * plain letter/digit/`_`/`$` check: a combining mark (an alias written in NFD), a currency or
 * other symbol (`€`, `©`, `¹`, `・`), and a supplementary-plane character (a surrogate pair in a
 * Kotlin/UTF-16 `String` — both code units are `>= 0x80`, so no special surrogate handling is
 * needed) are all legal identifier characters that Kotlin's `isLetterOrDigit()` alone does not
 * recognize (none of them is a Unicode letter or digit).
 *
 * This is the SINGLE predicate every keyword word-boundary check in this file uses
 * ([findTopLevelKeyword], [findKeywordAtAnyDepth], [findTopLevelReturningKeyword], [hasOuterJoin],
 * [referencesAnyName], [referencesOldOrNew], [matchUnicodeEscapeIdentifierSegment]'s `UESCAPE`
 * boundary, [skipOptionalKeyword], [extractAlias]'s `AS` boundary, and [skipLexicalToken]'s
 * dollar-quote guard), as well as [matchTrailingAliasSegment] (an implicit alias's own
 * continuation characters), [isStarQualifierAcceptable] (scanning the identifier run before a
 * star's qualifying `.`), [parseSingleCteDefinition] (a CTE name's characters AFTER the first —
 * see [isIdentifierStartChar] for the first character itself), and [COLUMN_REFERENCE]'s
 * continuation character class — deliberately ONE predicate everywhere: a real PostgreSQL keyword
 * can never legitimately abut a `>= 0x80` character outside a string literal, a quoted identifier,
 * a dollar-quoted string, or a comment (all already skipped by [skipLexicalToken]).
 *
 * Widening a boundary check is NOT unconditionally the fail-safe direction — rejecting a keyword
 * match a narrower predicate would have found can itself make a caller do something WORSE.
 * [findTopLevelKeyword] rejecting a `FROM` match, for instance, makes [parseSelectItems] fall
 * through to treating the REST of the window as items (`window.substring(itemsStart)`), producing
 * MORE items than before — the direction that same function's own KDoc calls dangerous.
 * Demonstrated directly: `SELECT src.name AS c ©FROM t, src, helper` parses to 1 item on `main`
 * and 3 items on this branch (the `©` immediately before `FROM`, both `>= 0x80`-adjacent, fuses
 * into the boundary check and hides the keyword) — but real PostgreSQL 18.4 rejects that exact
 * input outright, so this is not a shipped regression, only a fact about the predicate's own
 * behavior in isolation.
 *
 * The widening also changes behavior on VALID SQL, and does so correctly. Demonstrated directly:
 * `INSERT INTO t (a) SELECT 1 AS 𝐀returning RETURNING a, b` executes on PostgreSQL 18.4 and returns
 * 2 columns (`a`, `b`). [findTopLevelReturningKeyword] scans word runs with this predicate and
 * matches the first run equal to `"RETURNING"` (case-insensitive). With the widening reverted,
 * neither UTF-16 code unit of `𝐀` (a supplementary-plane character, written as a surrogate pair) is
 * an identifier character, so the run started by `𝐀` ends after zero characters — the scan then
 * restarts at the immediately-following ASCII text and reads a NEW word, `returning`, in isolation.
 * That word matches `"RETURNING"` case-insensitively, so [findTopLevelReturningKeyword] returns the
 * position of the alias's own lowercase suffix instead of the real, later `RETURNING` keyword,
 * and `parseSelectItems` treats everything from there on as the `RETURNING` list:
 * `[RETURNING a(null), b]` — 2 items, but the first is `"RETURNING a"` (unparsed, `columnName=null`)
 * because the genuine keyword got absorbed into it. With the widening in place, both surrogate
 * halves are identifier characters, so `𝐀returning` is scanned as ONE word (not equal to
 * `"RETURNING"`), and the scan correctly finds the real keyword further on: `[a(a), b(b)]` —
 * matching what PostgreSQL itself reports. (Verified directly: reverting only this line to drop the
 * `>= 0x80` disjunct reproduces the `[RETURNING a(null), b]` result on this exact input.)
 *
 * So the honest claim is not "no valid input is affected" — this example is valid input, and IS
 * affected — but something narrower: on valid SQL, the cases this widening changes are corrections,
 * and on SQL PostgreSQL rejects, it may change the result in either direction (as the `©FROM`
 * example above shows). The evidence for that is empirical, over a corpus, not a proof: an
 * 11,542-input differential run comparing this whole change against the code it replaced found that
 * every input where the item COUNT INCREASED was SQL PostgreSQL itself rejects, and every one of the
 * 484 distinct column/table names this branch assigns across that same differential is a legal
 * unquoted PostgreSQL identifier that re-lexes correctly. Valid SQL does change count in the other
 * direction: roughly 55 valid statements drop to zero items, all of them star shapes — e.g. `SELECT
 * t.*café, a FROM t`, which PostgreSQL expands to 15 columns while the narrower predicate produced 2
 * items, so pairing `a` against column 2 (`name`) would have reported a WRONG name. Dropping to zero
 * is the fail-safe answer: callers fall back to `ResultSetMetaData.getColumnName`. No structural
 * guarantee follows from any of this — only that the corpus exercised did not turn up a
 * counterexample.
 *
 * Keeping a narrower ASCII-only predicate for keyword boundaries "in sync by convention" with a
 * wider one for identifier-run scanning already caused three separate regressions in the other
 * direction: whenever only one side was widened, a PostgreSQL-legal, `>= 0x80` character truncated
 * a run or a word at the wrong place — e.g. `returning€` (a legal column name) read as the bare
 * keyword `RETURNING` plus a stray `€` (issue #219), or `x€9.` (stopping at `€` instead of
 * continuing through it left `9` looking like a qualifier's own start, misclassifying the whole
 * thing as a numeric literal). Calling the same function from every call site makes that symmetry
 * structural, not a comment to remember to keep in sync.
 */
private fun isIdentifierChar(character: Char): Boolean =
  character.isLetterOrDigit() || character == '_' || character == '$' || character.code >= 0x80

/**
 * True if [character] can START an unquoted PostgreSQL identifier: a letter, `_`, or any character
 * whose code is `>= 0x80` — never a digit or `$`, both of which are legal only after the first
 * character (see [isIdentifierChar]).
 *
 * This is also the predicate for what can START a dollar-quote TAG (the `tag` in `$tag$...$tag$`):
 * per PostgreSQL's `scan.l`, `ident_start` (an ordinary identifier's first character) and
 * `dolq_start` (a dollar-quote tag's first character) are defined by the IDENTICAL character class,
 * `[A-Za-z\200-\377_]` — a letter, `_`, or any byte `>= 0x80` (`\200`-`\377` in octal), never a
 * digit and never `$`. That is a genuine coincidence in `scan.l`, not an approximation: unlike the
 * two CONTINUATION classes, which `scan.l` defines DIFFERENTLY (`ident_cont` admits `$` in addition
 * to letters/digits/`_`/`>= 0x80`; `dolq_cont` admits digits but never `$` — see
 * [isDollarQuoteTagContinuationChar]'s KDoc), the two START classes have no such divergence, so
 * unifying them here does not "collapse two positions onto one class" the way merging the
 * continuation predicates would. Do NOT extend this merge to the continuation predicates for that
 * reason: [isIdentifierChar] and [isDollarQuoteTagContinuationChar] must stay separate.
 *
 * This is the SINGLE predicate every identifier-START and dollar-quote-tag-START check in this file
 * uses — [matchTrailingAliasSegment] (an implicit alias's own first character),
 * [parseSingleCteDefinition] (an unquoted CTE name's first character), and [skipDollarQuotedString]
 * (a dollar-quote tag's first character) all call this function directly, and
 * [COLUMN_REFERENCE_IDENTIFIER_START] is the same rule expressed as a regex character class for
 * [COLUMN_REFERENCE] and [FUNCTION_CALL_START] to embed. Before [parseSingleCteDefinition] was
 * changed to call this function, it used [isIdentifierChar] — the CONTINUATION predicate — for a
 * CTE name's first character too, wrongly accepting a `$`-led name (`WITH $x AS (...)`, which
 * PostgreSQL rejects outright) — the exact "two positions collapsed onto one class" mistake this
 * predicate's existence, split from [isIdentifierChar], exists to prevent.
 */
private fun isIdentifierStartChar(character: Char): Boolean =
  character.isLetter() || character == '_' || character.code >= 0x80

/**
 * The character class an unquoted PostgreSQL identifier's FIRST character may be — the same rule
 * [isIdentifierStartChar] checks, expressed as a regex character class: a letter, `_`, or any
 * character whose code is `>= 0x80` (see [matchTrailingAliasSegment], which enforces the same rule
 * for an implicit alias's own first character) — but NOT a digit or `$`, both of which are legal
 * only after the first character (see [isIdentifierChar]).
 */
private const val COLUMN_REFERENCE_IDENTIFIER_START = """[\p{L}_\x{80}-\x{10FFFF}]"""

/**
 * The character class an unquoted PostgreSQL identifier's characters AFTER the first may be — the
 * same class [isIdentifierChar] checks, expressed as a regex character class: a Unicode letter
 * (`\p{L}`, matching [Char.isLetter]) or decimal digit (`\p{Nd}`, matching [Char.isDigit]), `_`,
 * `$`, or any character whose code is `>= 0x80` (`\x{80}-\x{10FFFF}`, a CODE-POINT range, not a
 * per-`Char` one — this is what lets it match a supplementary-plane character written as a
 * surrogate pair in a Kotlin `String`, verified directly: `Regex("[\\x{80}-\\x{10FFFF}]").matches`
 * on a single surrogate-pair string returns `true`, consuming both UTF-16 code units as the ONE
 * code point they represent, rather than requiring the range to be repeated to cover each half).
 */
private const val COLUMN_REFERENCE_IDENTIFIER_CONTINUATION = """[\p{L}\p{Nd}_$\x{80}-\x{10FFFF}]"""

private const val COLUMN_REFERENCE_IDENTIFIER =
  """$COLUMN_REFERENCE_IDENTIFIER_START$COLUMN_REFERENCE_IDENTIFIER_CONTINUATION*"""

/**
 * Matches `table.column` or just `column`, where `table`/`column` are each an unquoted PostgreSQL
 * identifier — [COLUMN_REFERENCE_IDENTIFIER_START] followed by zero or more
 * [COLUMN_REFERENCE_IDENTIFIER_CONTINUATION] characters — never a bare `\w+`: PostgreSQL's
 * identifier class is wider than `\w` (it admits `$` and any `>= 0x80` character — see
 * [isIdentifierChar]) but its FIRST character is narrower (`\w` itself, unlike `\w+`, doesn't
 * enforce that a digit or `$` may only appear after the first character at all).
 *
 * The leading-character restriction matters in the WIDENING direction specifically: without it, a
 * digit- or `$`-led fragment that merely happens to be followed by identifier-continuation
 * characters — e.g. the item text `2€`, which PostgreSQL itself rejects outright ("trailing junk
 * after numeric literal", verified against PostgreSQL 18.4) — would [Regex.matchEntire] as a whole
 * "identifier" once the continuation class is widened to admit `€`, handing back a `columnName`
 * PostgreSQL would never actually resolve to that name. [parseColumnReference] returning `null`
 * for anything that isn't a real identifier is the safe, INTENDED outcome (see [parseSelectItems]'s
 * KDoc on why a lost name degrades safely to `ResultSetMetaData` while a WRONG one does not).
 */
private val COLUMN_REFERENCE = Regex(
  """(?:(?<table>$COLUMN_REFERENCE_IDENTIFIER)\.)?(?<column>$COLUMN_REFERENCE_IDENTIFIER)""",
)

/**
 * A parsed CTE definition from a `WITH` clause.
 *
 * @property name The CTE name, with surrounding double quotes stripped (if any). Use this for
 *   display or comparison, but NOT for constructing SQL to send to PostgreSQL — quoting changes
 *   case-folding (`"MyCte"` is distinct from `MyCte`, which folds to `mycte`), so building a
 *   `FROM <name>` reference from this stripped form can resolve to the wrong (or no) relation.
 *   Use [rawName] instead when generating SQL that references the CTE by name.
 * @property rawName The CTE name exactly as written in the original SQL, including surrounding
 *   double quotes if the user quoted it. Safe to splice verbatim into a `FROM <rawName>` probe.
 * @property bodyOpenParenthesis Index of `(` that opens the CTE body in the original SQL.
 * @property bodyCloseParenthesis Index of `)` that closes the CTE body.
 */
internal data class CteDefinition(
  val name: String,
  val rawName: String,
  val bodyOpenParenthesis: Int,
  val bodyCloseParenthesis: Int,
)

/**
 * Result of parsing a SQL `WITH` clause.
 *
 * @property definitions The CTEs in declaration order.
 * @property mainQueryStart Index in the original SQL where the main query (after all CTEs) begins.
 * @property isRecursive Whether the clause is `WITH RECURSIVE`. This determines name visibility:
 *   under `WITH RECURSIVE`, every CTE's body can see every other CTE in the clause (including
 *   ones declared later); under a plain `WITH`, a CTE's body can only see CTEs declared before
 *   it (and, per SQL semantics, an outer name with the same name as a later CTE — a later CTE
 *   never shadows for an earlier body).
 */
internal data class ParsedCteClause(
  val definitions: List<CteDefinition>,
  val mainQueryStart: Int,
  val isRecursive: Boolean,
)

/**
 * Parses CTE definitions from a SQL `WITH` clause.
 *
 * Extracts each CTE's name and the character indices of its body parentheses so callers
 * can replace CTE bodies by simple string splicing. Handles `WITH RECURSIVE`, optional
 * column lists (`name(col1, col2) AS (...)`), and `[NOT] MATERIALIZED` hints.
 *
 * Skips single-line (`--`) and block (`/* */`) comments between CTE definitions.
 *
 * @return The parsed CTE clause, or `null` if the SQL does not start with `WITH`
 *   (after leading whitespace and comments).
 */
internal fun parseCteClause(sql: String): ParsedCteClause? {
  val withIndex = findTopLevelKeyword(sql, "WITH")
  if (withIndex < 0) return null

  // Make sure WITH is before any DML/SELECT keyword (it's the leading WITH, not WITH inside a subquery)
  for (keyword in listOf("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE")) {
    val keywordIndex = findTopLevelKeyword(sql, keyword)
    if (keywordIndex in 0 until withIndex) return null
  }

  var position = withIndex + 4
  position = skipWhitespaceAndComments(sql, position)

  // Skip optional RECURSIVE keyword (word-boundary check avoids matching CTE names like "recursive_cte")
  val positionAfterRecursive = skipOptionalKeyword(sql, position, "RECURSIVE")
  val isRecursive = positionAfterRecursive != position
  position = positionAfterRecursive

  val definitions = mutableListOf<CteDefinition>()

  while (position < sql.length) {
    val parsed = parseSingleCteDefinition(sql, position) ?: break
    definitions.add(parsed.first)
    position = skipWhitespaceAndComments(sql, parsed.second)
    if (position >= sql.length || sql[position] != ',') break
    position++ // skip the comma
  }

  return if (definitions.isEmpty()) {
    null
  } else {
    ParsedCteClause(definitions, position, isRecursive)
  }
}

/**
 * Parses a single CTE definition starting at [position].
 *
 * Expected syntax: `name [(col_list)] AS [NOT MATERIALIZED | MATERIALIZED] (body)`
 *
 * @return A pair of the parsed [CteDefinition] and the position after the closing `)`,
 *   or `null` if parsing fails at any step.
 */
private fun parseSingleCteDefinition(sql: String, startPosition: Int): Pair<CteDefinition, Int>? {
  var position = skipWhitespaceAndComments(sql, startPosition)

  // Read CTE name: an unquoted identifier (isIdentifierStartChar, then a run of isIdentifierChar
  // characters), or a double-quoted one. The FIRST character is gated by isIdentifierStartChar,
  // not isIdentifierChar — a leading digit or "$" is not a legal identifier start (see
  // isIdentifierStartChar's KDoc; issue #219 originally used isIdentifierChar for the whole run,
  // which wrongly accepted "$x" as a CTE name — PostgreSQL rejects "WITH $x AS (...)" outright).
  val nameStart = position
  if (position < sql.length && sql[position] == '"') {
    position++ // skip opening quote
    while (position < sql.length && sql[position] != '"') position++
    if (position < sql.length) position++ // skip closing quote
  } else if (position < sql.length && isIdentifierStartChar(sql[position])) {
    position++
    while (position < sql.length && isIdentifierChar(sql[position])) position++
  }
  if (position == nameStart) return null
  val rawName = sql.substring(nameStart, position)
  val name = rawName.trim('"')

  position = skipWhitespaceAndComments(sql, position)

  // Skip optional column list: name(col1, col2)
  if (position < sql.length && sql[position] == '(') {
    val closeParenthesis = findMatchingCloseParenthesis(sql, position)
    if (closeParenthesis < 0) return null
    position = closeParenthesis + 1
    position = skipWhitespaceAndComments(sql, position)
  }

  // Expect AS
  if (!sql.regionMatches(position, "AS", 0, 2, ignoreCase = true)) return null
  position += 2
  position = skipWhitespaceAndComments(sql, position)

  // Skip optional NOT MATERIALIZED or MATERIALIZED
  position = skipOptionalKeyword(sql, position, "NOT")
  position = skipOptionalKeyword(sql, position, "MATERIALIZED")

  // Expect ( — start of CTE body
  if (position >= sql.length || sql[position] != '(') return null
  val bodyOpen = position
  val bodyClose = findMatchingCloseParenthesis(sql, position)
  if (bodyClose < 0) return null

  return CteDefinition(name, rawName, bodyOpen, bodyClose) to (bodyClose + 1)
}

/**
 * Builds a SELECT statement equivalent to an `UPDATE ... FROM ... RETURNING`,
 * `DELETE ... USING ... RETURNING`, or `MERGE ... RETURNING` statement, preserving the join
 * structure so that outer join nullability in the RETURNING columns is detectable via view
 * analysis — the same node-tree analysis this file already uses for a plain `SELECT`, rather
 * than the `PreparedStatement.getMetaData()`-based probe/stub fallback in `PgCatalogLoader`
 * (which only sees base-table `attnotnull` and is structurally blind to outer-join null
 * extension — see its KDoc).
 *
 * For `UPDATE target SET ... FROM from_clause WHERE where_clause RETURNING cols`:
 * produces `SELECT cols FROM target, from_clause WHERE where_clause`.
 *
 * For `DELETE FROM target USING using_clause WHERE where_clause RETURNING cols`:
 * produces `SELECT cols FROM target, using_clause WHERE where_clause`.
 *
 * For `MERGE INTO target USING source ON condition ... RETURNING cols`: produces `SELECT cols
 * FROM source [RIGHT] JOIN target ON condition` — SOURCE first, TARGET second — see
 * [convertMergeToSelect] for which join kind and why, and for why the ORDER matters here in a
 * way it doesn't for `UPDATE`/`DELETE`.
 *
 * `cols` (including a bare `*`) is passed through VERBATIM — no rewriting needed, but the
 * reason differs by statement:
 * - `UPDATE`/`DELETE`: `RETURNING *` is NOT limited to the target table's columns — verified
 *   directly against PostgreSQL: it expands to every relation named in the statement's scope
 *   (target AND the `FROM`/`USING` relations), identically to a plain `SELECT *` over the same
 *   `FROM` list. A converted `SELECT * FROM target, from_clause` already reproduces `RETURNING
 *   *`'s real column set — same columns, same order.
 * - `MERGE`: also NOT limited to the target table's columns, but the ORDER is NOT identical to
 *   a naive `SELECT *` over `target JOIN source` — verified directly against PostgreSQL
 *   (`\gdesc` on a live `MERGE ... RETURNING *`): the source relation's columns always come
 *   FIRST, target's second, regardless of which `WHEN` clauses are present. That is exactly why
 *   [convertMergeToSelect] builds `FROM source ... JOIN target`, not `FROM target ... JOIN
 *   source` — getting the join operands in that order is what makes `cols` passing through
 *   verbatim correct for a bare `*`.
 *
 * Any duplicate column NAMES either conversion produces (e.g. both tables having an `id`
 * column) are already handled downstream by `PgCatalogLoader.analyzeViaTemporaryView`'s
 * retry-with-aliases fallback.
 *
 * For DML without a FROM/USING/join clause (no join structure), for INSERT (where RETURNING
 * can only reference the target table), or for MERGE without RETURNING, returns `null`. What the
 * CALLER does with that `null` differs by which caller: for a CTE body (`convertDmlCteBodyToSelect`
 * → `PgCatalogLoader`'s Phase 1), it falls back to the metadata-probe stub, which DOES read each
 * column's real `ResultSetMetaData.isNullable`. For the TOP-LEVEL statement (`PgCatalogLoader`'s
 * Phase 2), `null` here (or a conversion that fails to validate) instead reaches
 * `queryColumnNullability`'s `buildAllNonNullable` fallback, which does NOT read `isNullable` at
 * all — it asserts every column `false` (NOT NULL) unconditionally. `buildAllNonNullable` itself
 * CAN return an empty list (if the probe's own `metaData` is `null`, or the probe throws
 * `SQLException`) — but that path is unreachable via the current caller, `JdbcAnalyzer`:
 * `buildResultColumns` returns `emptyList()` BEFORE ever calling `queryColumnNullability`
 * whenever the statement being analyzed has no `ResultSetMetaData` of its own, so
 * `queryColumnNullability` — and therefore `buildAllNonNullable` — is only reached for a
 * statement JDBC has already shown DOES have result-column metadata. "No outer joins are
 * possible" is true for the shapes this function returns `null` for, but that does not mean
 * nullability is left undetermined by the caller that matters today — the top-level path in
 * particular still asserts a specific (and, for a genuinely nullable column, WRONG) answer.
 *
 * `OLD.`/`NEW.` (PostgreSQL 18): for an `UPDATE`/`DELETE`/`MERGE` body whose `RETURNING` reads
 * these pseudo-relations, this function still builds a converted `SELECT` — `OLD`/`NEW` are
 * spliced into the output list verbatim, since this function has no reason to recognize them
 * specially — but that converted `SELECT` is not valid outside an actual `RETURNING` clause
 * (`OLD`/`NEW` are not ordinary range variables there), so PostgreSQL fails to prepare it. What
 * catches that failure and rejects the conversion depends on WHERE this function's output is
 * being used, not on anything in this function itself: for a CTE body,
 * `PgCatalogLoader.validatedConversion` rejects it, falling back to the probe/stub path — whose
 * own safety net (see `PgCatalogLoader.buildSelectStub`'s KDoc) is what actually makes an
 * `OLD`/`NEW` reference safe. For the TOP-LEVEL (outer) statement, `PgCatalogLoader`'s Phase 2 in
 * `transformForViewCreation` applies the same validate-before-trusting gate directly (there is no
 * CTE-body stub fallback to defer to at that level — a rejected top-level conversion instead
 * falls through to the same "no join structure" `null` result this function itself returns for
 * an INSERT), and `queryColumnNullability` additionally never lets a `SQLException` from
 * attempting to use an unvalidated conversion escape uncaught, as defense in depth.
 *
 * @param sql The DML statement (without any CTE prefix).
 * @return An equivalent SELECT preserving the join structure, or `null` if the DML
 *   has no join structure that could affect RETURNING column nullability.
 */
internal fun convertDmlToSelect(sql: String): String? {
  val trimmedStart = skipWhitespaceAndComments(sql, 0)

  // UPDATE target SET ... [FROM ...] [WHERE ...] RETURNING ...
  if (sql.regionMatches(trimmedStart, "UPDATE", 0, 6, ignoreCase = true)) {
    val setIndex = findTopLevelKeyword(sql, "SET", trimmedStart + 6)
    if (setIndex < 0) return null

    val fromIndex = findTopLevelKeyword(sql, "FROM", setIndex)
    if (fromIndex < 0) return null // No FROM clause → no join structure

    val returningIndex = findTopLevelKeyword(sql, "RETURNING", setIndex)
    if (returningIndex < 0) return null // No RETURNING → no result columns
    if (!indicesAreOrdered(trimmedStart + 6, setIndex, fromIndex + 4, returningIndex)) return null

    val target = sql.substring(trimmedStart + 6, setIndex).trim()
    return buildSelectFromDml(sql, target, fromIndex + 4, returningIndex)
  }

  // DELETE FROM target [USING ...] [WHERE ...] RETURNING ...
  if (sql.regionMatches(trimmedStart, "DELETE", 0, 6, ignoreCase = true)) {
    val fromIndex = findTopLevelKeyword(sql, "FROM", trimmedStart + 6)
    if (fromIndex < 0) return null

    val usingIndex = findTopLevelKeyword(sql, "USING", fromIndex)
    if (usingIndex < 0) return null // No USING clause → no join structure

    val returningIndex = findTopLevelKeyword(sql, "RETURNING", fromIndex)
    if (returningIndex < 0) return null
    if (!indicesAreOrdered(fromIndex + 4, usingIndex, usingIndex + 5, returningIndex)) return null

    val target = sql.substring(fromIndex + 4, usingIndex).trim()
    return buildSelectFromDml(sql, target, usingIndex + 5, returningIndex)
  }

  // MERGE INTO target [[AS] alias] USING source [[AS] alias] ON condition WHEN ... RETURNING ...
  if (sql.regionMatches(trimmedStart, "MERGE", 0, 5, ignoreCase = true)) {
    return convertMergeToSelect(sql, trimmedStart)
  }

  // INSERT, or other DML: no join structure in RETURNING
  return null
}

/**
 * Checks whether [body] is an `INSERT` statement, skipping past `body`'s own leading nested
 * `WITH` clause first if it has one (the CTE prefix, not the `INSERT` itself, would otherwise
 * make this check meaningless for a body shaped like `WITH helper AS (...) INSERT ...`).
 *
 * Used to EXCLUDE `INSERT` bodies from the stub path's outer-join safety net: an `INSERT`'s
 * `RETURNING` sees only the row just inserted — nothing in its own `SELECT`/`VALUES` source
 * (including any join there) can null-extend the columns being inserted, because a row either
 * gets inserted with whatever values that source produced, or (for a joined source with no
 * match) simply isn't inserted at all. Confirmed against real PostgreSQL: `INSERT INTO b(id,
 * bval) SELECT a.id, 'v' FROM a LEFT JOIN b2 ON b2.id = a.id RETURNING id, bval` returns
 * non-null values for both columns despite the `LEFT JOIN` in its source.
 */
internal fun isInsertBody(body: String): Boolean {
  val dml = stripLeadingNestedWithClause(body)
  val trimmedStart = skipWhitespaceAndComments(dml, 0)
  return dml.regionMatches(trimmedStart, "INSERT", 0, 6, ignoreCase = true)
}

/**
 * If [body] carries its own leading nested `WITH` clause, returns the portion after it — the
 * actual DML/SELECT statement `WITH` introduces. Otherwise returns [body] unchanged.
 *
 * Shared by [isInsertBody] (classifying what KIND of statement `body` is) and
 * `PgCatalogLoader.convertDmlCteBodyToSelect` (converting that statement, with the nested
 * `WITH` reattached in front of the result) — both need to see past a body's own `WITH` before
 * they can do their jobs, since `convertDmlToSelect` only recognizes a statement starting with
 * `UPDATE`/`DELETE`/`MERGE` (or, here, `INSERT`) as the very first token.
 */
internal fun stripLeadingNestedWithClause(body: String): String {
  val nestedCteClause = parseCteClause(body) ?: return body
  return body.substring(nestedCteClause.mainQueryStart)
}

/**
 * Returns `true` if [indices], read left to right, are non-decreasing.
 *
 * `convertDmlToSelect`/`convertMergeToSelect` locate several keywords independently — each
 * search anchored at a fixed prior position, not necessarily at the PREVIOUS keyword's own
 * match — so a keyword-boundary false positive (or any other bug, present or future) can return
 * an out-of-order index. Used as a substring range without checking that would silently build
 * wrong SQL, or throw `StringIndexOutOfBoundsException` (uncaught by the `SQLException` handling
 * everywhere else in this pipeline, aborting generation outright on input PostgreSQL itself
 * accepts fine). Checking order explicitly turns either failure mode into a clean `null` — the
 * existing, always-safe "fall back to the probe/stub path" behavior — instead.
 */
private fun indicesAreOrdered(vararg indices: Int): Boolean {
  for (i in 1 until indices.size) {
    if (indices[i] < indices[i - 1]) return false
  }
  return true
}

/**
 * Builds `SELECT <returning> FROM <target>, <joinClause> [WHERE <where>]` from the components
 * of an UPDATE FROM or DELETE USING statement.
 *
 * @param sql The full DML statement.
 * @param target The target table expression.
 * @param joinClauseStart Index in [sql] where the join clause text begins (after FROM/USING keyword).
 * @param returningIndex Index in [sql] where the RETURNING keyword starts.
 * @return The converted SELECT, or `null` if [joinClauseStart] and [returningIndex] (or a
 *   `WHERE` keyword found between them) are not in a sane order — see [indicesAreOrdered].
 */
private fun buildSelectFromDml(sql: String, target: String, joinClauseStart: Int, returningIndex: Int): String? {
  if (!indicesAreOrdered(joinClauseStart, returningIndex)) return null
  val whereIndex = findTopLevelKeyword(sql, "WHERE", joinClauseStart)

  val fromClause: String
  val whereClause: String?
  if (whereIndex in joinClauseStart until returningIndex) {
    if (!indicesAreOrdered(whereIndex + 5, returningIndex)) return null
    fromClause = sql.substring(joinClauseStart, whereIndex).trim()
    whereClause = sql.substring(whereIndex + 5, returningIndex).trim()
  } else {
    fromClause = sql.substring(joinClauseStart, returningIndex).trim()
    whereClause = null
  }
  val returningClause = sql.substring(returningIndex + 9).trimEnd().trimEnd(';')

  return buildString {
    append("SELECT ").append(returningClause)
    append(" FROM ").append(target).append(", ").append(fromClause)
    if (whereClause != null) append(" WHERE ").append(whereClause)
  }
}

/**
 * Builds a SELECT statement approximating a `MERGE ... RETURNING` statement's join structure and
 * COLUMN ORDER, for outer-join nullability analysis.
 *
 * `MERGE INTO target [[AS] alias] USING source [[AS] alias] ON condition WHEN ... RETURNING
 * cols` becomes `SELECT cols FROM source [RIGHT] JOIN target ON condition`. SOURCE comes first —
 * verified directly against PostgreSQL (`\gdesc` on a live `MERGE ... RETURNING *`): the source
 * relation's columns are always listed before the target's, regardless of which `WHEN` clauses
 * are present, so a target-first conversion would silently transpose a bare `RETURNING *`'s
 * column order (right column values, wrong column positions — a defect [validatedConversion] in
 * `PgCatalogLoader` cannot always catch: when target and source happen to share column names and
 * types, e.g. `id, val, id, val` either way, the two conversions are indistinguishable by
 * metadata alone). Whether the join is `RIGHT` or plain (inner) depends on whether the statement
 * has a `WHEN NOT MATCHED BY SOURCE` clause (PostgreSQL 17+): that clause fires for TARGET rows
 * with no matching source row — precisely the shape a `source RIGHT JOIN target` represents,
 * where a target row can appear with no corresponding source row and source columns
 * null-extend. Without that clause, every row RETURNING can see has a genuine source match
 * (`WHEN MATCHED`, or `WHEN NOT MATCHED [BY TARGET]`, where the "no match" side is the freshly
 * INSERTed row itself, not a null-extended target row), so a plain (inner) join is accurate.
 *
 * This is a deliberately narrow model of MERGE, not a simulation of every WHEN branch's own
 * filtering (`WHEN MATCHED AND cond THEN ...` conditions are dropped) — like
 * [buildSelectFromDml], it exists only to recover the join SHAPE and column ORDER for
 * outer-join nullability analysis, not to reproduce MERGE's full row-selection semantics.
 *
 * KNOWN IMPRECISION (documented, not solved): `WHEN NOT MATCHED BY SOURCE THEN DO NOTHING`
 * produces NO `RETURNING` row at all for an unmatched target row — verified directly against
 * PostgreSQL (`\gdesc`/execution on a live statement: 0 rows). So a source column can never
 * actually come back `NULL` through this specific branch, yet this function still models the
 * clause's mere PRESENCE as `source RIGHT JOIN target`, reporting that column nullable anyway.
 * This is over-nullable, not under-nullable — the safe direction the same way
 * `PgCatalogLoader.transformForViewCreation`'s `forceAllNullable` local (and the
 * `forceNullableColumn` predicate it feeds into `tryPrepareStub`) deliberately over-approximate —
 * so it is left as an accepted imprecision rather than special-cased for the `DO NOTHING` action
 * specifically.
 *
 * @param sql The full MERGE statement.
 * @param trimmedStart Index in [sql] where the `MERGE` keyword begins.
 * @return The converted SELECT, or `null` if there is no `RETURNING` clause (MERGE always has
 *   `USING ... ON`, so a missing `RETURNING` is the only realistic reason for `null`), or if the
 *   located keywords are not in a sane order (see [indicesAreOrdered]).
 */
private fun convertMergeToSelect(sql: String, trimmedStart: Int): String? {
  val intoIndex = findTopLevelKeyword(sql, "INTO", trimmedStart)
  if (intoIndex < 0) return null
  val usingIndex = findTopLevelKeyword(sql, "USING", intoIndex)
  if (usingIndex < 0) return null
  val onIndex = findTopLevelKeyword(sql, "ON", usingIndex)
  if (onIndex < 0) return null
  val returningIndex = findTopLevelKeyword(sql, "RETURNING", onIndex)
  if (returningIndex < 0) return null // No RETURNING → no result columns
  if (!indicesAreOrdered(intoIndex + 4, usingIndex, usingIndex + 5, onIndex, onIndex + 2, returningIndex)) {
    return null
  }

  val target = sql.substring(intoIndex + 4, usingIndex).trim()
  val source = sql.substring(usingIndex + 5, onIndex).trim()
  val whenIndex = findTopLevelKeyword(sql, "WHEN", onIndex)
  val conditionEnd = if (whenIndex in (onIndex + 2) until returningIndex) whenIndex else returningIndex
  val condition = sql.substring(onIndex + 2, conditionEnd).trim()
  val returningClause = sql.substring(returningIndex + 9).trimEnd().trimEnd(';')
  // SOURCE first, TARGET second — verified against PostgreSQL: "RETURNING *" on MERGE always
  // expands source-relation-columns-first, target-relation-columns-second, regardless of which
  // WHEN clauses are present. RIGHT JOIN (rather than LEFT) keeps the null-extended side on the
  // right, matching this source-first layout, when a target row can appear with no source match.
  val joinKeyword = if (hasWhenNotMatchedBySourceClause(sql, onIndex, returningIndex)) "RIGHT JOIN" else "JOIN"

  return "SELECT $returningClause FROM $source $joinKeyword $target ON $condition"
}

/**
 * Checks whether the SQL between [startIndex] and [endIndex] contains a top-level `WHEN NOT
 * MATCHED BY SOURCE` clause, matched as the exact keyword sequence (skipping whitespace and
 * comments between each word) rather than generic scanning for "SOURCE" — which could also
 * appear as an ordinary identifier (a column or table literally named `source`) elsewhere in a
 * MERGE statement, e.g. `MERGE INTO t USING source AS s ON ...`.
 *
 * [startIndex] and [endIndex] default to the whole of [sql], for callers (e.g.
 * `PgCatalogLoader`'s stub-path over-nullability check) that just need to know whether the
 * clause appears ANYWHERE, without already knowing precise `ON`/`RETURNING` bounds.
 */
internal fun hasWhenNotMatchedBySourceClause(sql: String, startIndex: Int = 0, endIndex: Int = sql.length): Boolean {
  var searchFrom = startIndex
  while (true) {
    val whenIndex = findTopLevelKeyword(sql, "WHEN", searchFrom)
    if (whenIndex < 0 || whenIndex >= endIndex) return false
    var position = skipWhitespaceAndComments(sql, whenIndex + 4)
    var matchedFullPhrase = true
    for (keyword in listOf("NOT", "MATCHED", "BY", "SOURCE")) {
      val afterKeyword = skipOptionalKeyword(sql, position, keyword)
      if (afterKeyword == position) {
        matchedFullPhrase = false
        break
      }
      position = afterKeyword
    }
    if (matchedFullPhrase) return true
    searchFrom = whenIndex + 4
  }
}

/**
 * Checks whether [body] contains a `LEFT`/`RIGHT`/`FULL [OUTER] JOIN` at ANY nesting depth,
 * including inside a `FROM`/`USING`/`MERGE ... USING` subquery — an outer join whose
 * null-extension the stub path's metadata-based nullability (base-table `attnotnull` via
 * `ResultSetMetaData.isNullable`, see `PgCatalogLoader.buildSelectStub`'s KDoc) cannot see.
 * Depth-0-only scanning would miss `MERGE INTO tgt USING (SELECT ... FROM src LEFT JOIN sx ON
 * ...) s ON ...`, where the join sits inside the `USING` subquery, not at the top level of the
 * MERGE statement — confirmed against real PostgreSQL to fabricate NOT NULL for exactly that
 * shape when this scanned only depth 0.
 *
 * Matched as the anchored phrase (`LEFT`/`RIGHT`/`FULL`, then an optional `OUTER`, then `JOIN`),
 * not generic scanning for `LEFT`/`RIGHT`/`FULL` alone — those words are also PostgreSQL's
 * builtin string functions (`LEFT(str, n)`, `RIGHT(str, n)`), and scanning for them bare would
 * misfire on `SET name = LEFT(name, 5)`, which has no join at all.
 *
 * Over-approximating (matching a `LEFT`/`RIGHT`/`FULL JOIN` that turns out not to affect the
 * columns actually referenced by `RETURNING`) is acceptable here: this function is used ONLY to
 * decide whether the stub-path safety net should mark every column nullable, so a false
 * positive costs precision, not correctness.
 */
internal fun hasOuterJoin(body: String): Boolean {
  for (sideKeyword in listOf("LEFT", "RIGHT", "FULL")) {
    var searchFrom = 0
    while (true) {
      val sideIndex = findKeywordAtAnyDepth(body, sideKeyword, searchFrom)
      if (sideIndex < 0) break
      var position = skipWhitespaceAndComments(body, sideIndex + sideKeyword.length)
      position = skipOptionalKeyword(body, position, "OUTER")
      if (body.regionMatches(position, "JOIN", 0, 4, ignoreCase = true)) {
        val after = position + 4
        if (after >= body.length || !isIdentifierChar(body[after])) return true
      }
      searchFrom = sideIndex + sideKeyword.length
    }
  }
  return false
}

/**
 * Checks whether [body] contains a `ROLLUP`, `CUBE`, or `GROUPING SETS` construct, at ANY nesting
 * depth, followed by `(`, with a `GROUP BY` (the two keywords, skipping whitespace/comments
 * between them, at any depth) appearing somewhere BEFORE the match — a grouping set can produce a
 * "supertotal" row where a grouped column is `NULL` by definition, independently of any join or
 * `WHEN NOT MATCHED BY SOURCE`. Confirmed against real PostgreSQL: `MERGE INTO tgt USING (SELECT
 * id, count(*) AS c FROM a WHERE id = 1 GROUP BY ROLLUP(id)) s ON tgt.id = COALESCE(s.id, 2) WHEN
 * MATCHED THEN UPDATE SET tval = 'z' RETURNING merge_action(), s.id AS sid, tgt.id` returns `sid =
 * NULL` for the `ROLLUP` supertotal row, matched into the target only via the `COALESCE` in the
 * `ON` condition — no `LEFT`/`RIGHT`/`FULL JOIN` keyword and no `WHEN NOT MATCHED BY SOURCE`
 * clause appears anywhere in the body for [hasOuterJoin] or [hasWhenNotMatchedBySourceClause] to
 * find.
 *
 * The trailing `(` is required, not optional: `CUBE` is also an ordinary type/function name from
 * the `cube` extension (verified: `rollup` ships no such function — `ROLLUP` is merely
 * syntactically callable as `ROLLUP(...)`, not an actual extension function), so a bare-word
 * match on `CUBE` alone would misfire far more widely than intended. Requiring `(` alone is still
 * not enough, though — an ordinary `cube(av)` FUNCTION CALL with no `GROUP BY` anywhere in `body`
 * has nothing to do with grouping sets at all and was confirmed to misfire (fabricating an
 * unrelated target column nullable) before this check was added — requiring a preceding
 * `GROUP BY` rules that out. A `GROUP BY`
 * that DOES precede the match but belongs to a DIFFERENT, unrelated subquery than the `cube(...)`
 * call is still an accepted false positive in the safe direction, the same tradeoff [hasOuterJoin]
 * makes for a keyword match that turns out not to affect the columns `RETURNING` actually reads —
 * this function does not track subquery scoping precisely enough to rule that out.
 *
 * `GROUPING SETS` is matched as the exact two-word phrase (skipping whitespace/comments between
 * them, like [hasWhenNotMatchedBySourceClause] does for its own multi-word phrase) rather than
 * scanning for `GROUPING` alone, since `GROUPING` is also a standalone aggregate function
 * (`GROUPING(col)`) unrelated to `GROUPING SETS`.
 */
internal fun hasGroupingSetConstruct(body: String): Boolean {
  for (keyword in listOf("ROLLUP", "CUBE")) {
    var searchFrom = 0
    while (true) {
      val keywordIndex = findKeywordAtAnyDepth(body, keyword, searchFrom)
      if (keywordIndex < 0) break
      val position = skipWhitespaceAndComments(body, keywordIndex + keyword.length)
      if (position < body.length && body[position] == '(' && hasGroupByBefore(body, keywordIndex)) return true
      searchFrom = keywordIndex + keyword.length
    }
  }
  var searchFrom = 0
  while (true) {
    val groupingIndex = findKeywordAtAnyDepth(body, "GROUPING", searchFrom)
    if (groupingIndex < 0) return false
    val beforeSets = skipWhitespaceAndComments(body, groupingIndex + "GROUPING".length)
    val afterSets = skipOptionalKeyword(body, beforeSets, "SETS")
    if (afterSets != beforeSets &&
      afterSets < body.length &&
      body[afterSets] == '(' &&
      hasGroupByBefore(body, groupingIndex)
    ) {
      return true
    }
    searchFrom = groupingIndex + "GROUPING".length
  }
}

/**
 * Checks whether a `GROUP BY` phrase (the two keywords, skipping whitespace/comments between
 * them, at any nesting depth) appears anywhere in [body] before [beforeIndex]. Used by
 * [hasGroupingSetConstruct] to require a `ROLLUP`/`CUBE`/`GROUPING SETS` match to actually be
 * preceded by a `GROUP BY`, ruling out an ordinary `cube(...)`/`rollup(...)` function call that
 * has no grouping-set construct anywhere nearby.
 */
private fun hasGroupByBefore(body: String, beforeIndex: Int): Boolean {
  var searchFrom = 0
  while (true) {
    val groupIndex = findKeywordAtAnyDepth(body, "GROUP", searchFrom)
    if (groupIndex < 0 || groupIndex >= beforeIndex) return false
    val position = skipWhitespaceAndComments(body, groupIndex + "GROUP".length)
    val afterBy = skipOptionalKeyword(body, position, "BY")
    if (afterBy != position) return true
    searchFrom = groupIndex + "GROUP".length
  }
}

/**
 * The single predicate for "this body's OWN text contains a construct that can null-extend a
 * value base-table `attnotnull` reports NOT NULL" — an outer join ([hasOuterJoin]), a `MERGE`'s
 * `WHEN NOT MATCHED BY SOURCE` branch ([hasWhenNotMatchedBySourceClause]), or a grouping-set
 * construct's supertotal row ([hasGroupingSetConstruct]). Callers apply the `INSERT` exclusion
 * ([isInsertBody]) themselves — this function does not, since whether that exclusion applies
 * depends on the caller's own context (e.g. the sibling-danger seed in
 * `PgCatalogLoader.transformForViewCreation` applies it, but propagation through that same
 * fixpoint does not).
 */
internal fun hasNullExtendingConstruct(body: String): Boolean =
  hasOuterJoin(body) || hasWhenNotMatchedBySourceClause(body) || hasGroupingSetConstruct(body)

/**
 * Checks whether [body] mentions any of [names] as a standalone identifier, at any nesting
 * depth — UNQUOTED (`pre`, `Pre`), or double-quoted (`"pre"`). Used to detect whether a rejected
 * data-modifying CTE body references a SIBLING CTE by name (see
 * `PgCatalogLoader.transformForViewCreation`'s KDoc on the sibling-CTE stub-path trigger): a
 * sibling's own join structure — e.g. a `LEFT JOIN` inside a CTE the body merely reads FROM — can
 * null-extend a column the body's own text has no join at all to show, so neither [hasOuterJoin]
 * nor [referencesOldOrNew] can catch it; only knowing the other CTE names in scope (from
 * [parseCteClause]) and checking whether the body mentions one of them can.
 *
 * A quoted reference is compared against [names] case-INSENSITIVELY, even though PostgreSQL
 * itself is case-sensitive inside double quotes (a quoted `"PRE"` does NOT resolve to a lowercase
 * CTE named `pre`) — this is a deliberate over-approximation, not a bug: getting this exactly
 * right would mean tracking each CTE's exact declared quoting, and a false-positive match here
 * only costs precision (an extra column forced nullable that didn't need to be), never
 * correctness. No trailing `.` is required after a quoted match — `MERGE INTO tgt USING "pre" ON
 * ...` (a bare relation reference, no column access) must match just as `"pre".id` does.
 *
 * Otherwise, [body] is scanned token by token exactly as [referencesOldOrNew] does: any other
 * lexical token (a string literal, a dollar-quoted string, or a comment) is skipped opaquely via
 * [skipLexicalToken] — a name that only appears inside one of those is NOT a real reference — and
 * an unquoted match is checked with the same case-insensitive word-boundary rule
 * [findKeywordAtAnyDepth] already used before this rewrite.
 *
 * Over-approximating is the intended tradeoff here, same as [hasOuterJoin]: a body that merely
 * mentions a sibling's name in a context that turns out not to be a null-extending reference
 * costs precision, not correctness — the caller only uses this to decide whether to force the
 * stub path's columns nullable.
 */
internal fun referencesAnyName(body: String, names: Collection<String>): Boolean {
  var i = 0
  while (i < body.length) {
    if (body[i] == '"') {
      val afterQuote = skipDoubleQuotedIdentifier(body, i)
      val quotedContent = body.substring(i + 1, (afterQuote - 1).coerceAtLeast(i + 1))
      if (names.any { name -> name.equals(quotedContent, ignoreCase = true) }) return true
      i = afterQuote
      continue
    }
    val afterToken = skipLexicalToken(body, i)
    if (afterToken != i) {
      i = afterToken
      continue
    }
    val matchedName = names.firstOrNull { name ->
      body.regionMatches(i, name, 0, name.length, ignoreCase = true) &&
        (i == 0 || !isIdentifierChar(body[i - 1])) &&
        (i + name.length >= body.length || !isIdentifierChar(body[i + name.length]))
    }
    if (matchedName != null) return true
    i++
  }
  return false
}

/**
 * Checks whether [text] contains a reference to PostgreSQL 18's `OLD`/`NEW` `RETURNING`
 * pseudo-relations, or to one of [additionalNames] (aliases declared for them via the `RETURNING
 * WITH (OLD AS alias, NEW AS alias)` prologue — see [parseOldNewAliasPrologue]), at any nesting
 * depth. `OLD` is `NULL` for a row that didn't previously exist (an `INSERT`, or an `INSERT ...
 * ON CONFLICT DO UPDATE` that inserted rather than updated); `NEW` is `NULL` for a row that no
 * longer exists (a `DELETE`, or the not-matched-by-source branch of a `MERGE`). Neither the
 * join-preserving conversion (which splices the reference into a plain `SELECT`, where
 * `OLD`/`NEW` are not valid range variables, so it fails to prepare and is rejected by
 * validation) nor the metadata probe/stub (whose `ResultSetMetaData.isNullable` reflects
 * base-table `attnotnull`, oblivious to `OLD`/`NEW`'s own conditional existence) can account for
 * this on their own — see `PgCatalogLoader`'s stub-path safety net, which this function feeds
 * alongside [hasOuterJoin].
 *
 * A reference must be followed by a `.` — the only valid use of the `OLD`/`NEW` pseudo-relations
 * and their declared aliases — but the `.` need not be IMMEDIATELY adjacent: any amount of
 * whitespace or a comment may separate them (`NEW . name`, `NEW\n.name`, and `NEW/*c*/.name` are
 * all valid PostgreSQL syntax; verified against PostgreSQL 18). The name itself is matched with
 * the same word-boundary rules as any other keyword (see [findTopLevelKeyword]) when written
 * unquoted — case-insensitively, so literal text or a comment mentioning "old"/"new" does not
 * misfire unless followed by a qualifying `.` — or, when double-quoted, matched EXACTLY against
 * the lowercase form (`"old"`, `"new"`): PostgreSQL folds an unquoted identifier to lowercase
 * before resolving it against the (lowercase) pseudo-relation name, so a quoted `"OLD"` does NOT
 * refer to it and is correctly rejected by PostgreSQL itself with "missing FROM-clause entry for
 * table \"OLD\"" (verified). A table genuinely ALIASED `old` or `new` (e.g. `UPDATE t AS old SET
 * ... RETURNING old.col`) is indistinguishable from the pseudo-relation by this check and
 * triggers it too — over-approximating in the safe direction, the same tradeoff [hasOuterJoin]
 * makes.
 */
internal fun referencesOldOrNew(text: String, additionalNames: Set<String> = emptySet()): Boolean {
  val unquotedNames = setOf("OLD", "NEW") + additionalNames
  var i = 0
  while (i < text.length) {
    if (text[i] == '"') {
      val afterQuote = skipDoubleQuotedIdentifier(text, i)
      val quotedContent = text.substring(i + 1, (afterQuote - 1).coerceAtLeast(i + 1))
      val referencesPseudoRelation = quotedContent == "old" || quotedContent == "new"
      if (referencesPseudoRelation || quotedContent in additionalNames) {
        val afterDot = skipWhitespaceAndComments(text, afterQuote)
        if (afterDot < text.length && text[afterDot] == '.') return true
      }
      i = afterQuote
      continue
    }
    val afterToken = skipLexicalToken(text, i)
    if (afterToken != i) {
      i = afterToken
      continue
    }
    val matchedName = unquotedNames.firstOrNull { name ->
      text.regionMatches(i, name, 0, name.length, ignoreCase = true) &&
        (i == 0 || !isIdentifierChar(text[i - 1])) &&
        (i + name.length >= text.length || !isIdentifierChar(text[i + name.length]))
    }
    if (matchedName != null) {
      val afterDot = skipWhitespaceAndComments(text, i + matchedName.length)
      if (afterDot < text.length && text[afterDot] == '.') return true
      i += matchedName.length
    } else {
      i++
    }
  }
  return false
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
    .mapNotNull { entry ->
      val asIndex = findTopLevelKeyword(entry, "AS")
      if (asIndex < 0) null else entry.substring(asIndex + 2).trim().trim('"')
    }
    .toSet()
  return aliasNames to (closeParenthesis + 1)
}

/**
 * The result of analyzing a data-modifying body's `RETURNING` list for `OLD`/`NEW` references —
 * see [oldOrNewReturningColumns]'s KDoc.
 *
 * @property itemCount The number of comma-separated `RETURNING` items found. This is an
 *   ASSUMPTION, not a fact: it equals the real result column count only if every item produces
 *   exactly one column — true for an ordinary expression, false for a star item (`*`, `tbl.*`),
 *   which expands to as many columns as the starred relation has. Whenever [forcedColumns] is
 *   non-empty, the caller MUST additionally cross-check [itemCount] against the real column count
 *   (from `ResultSetMetaData.getColumnCount()`) before trusting it — see
 *   [oldOrNewReturningColumns]'s KDoc for why this is the authoritative fail-safe, not
 *   [isStarItem]'s best-effort pattern matching.
 * @property forcedColumns 1-based indices, into [itemCount], of items that reference `OLD`/`NEW`
 *   — or `null` if a RECOGNIZED star item coincides with an `OLD`/`NEW` reference, meaning the
 *   mapping is KNOWN unreliable without needing the real-column-count cross-check at all (the
 *   caller should force every column nullable, same conclusion the cross-check would reach for
 *   an unrecognized star, just reached earlier).
 */
internal data class OldOrNewReturningAnalysis(val itemCount: Int, val forcedColumns: Set<Int>?)

/**
 * Analyzes a data-modifying [body]'s `RETURNING` list to determine which SPECIFIC result columns
 * reference PostgreSQL 18's `OLD`/`NEW` pseudo-relations (directly, or via a `RETURNING WITH (OLD
 * AS alias, ...)` declared alias — see [parseOldNewAliasPrologue]) — the only columns whose own
 * conditional existence (see [referencesOldOrNew]'s KDoc) neither the join-preserving conversion
 * nor plain metadata can see. `RETURNING` items map 1:1 onto result columns in declaration order
 * (PostgreSQL preserves it) UNLESS one of them is a star item (`*`, `tbl.*`), which expands to
 * however many columns the starred relation has — an item count this text-only function cannot
 * resolve without asking PostgreSQL.
 *
 * This is intentionally per-column rather than per-body: forcing an ENTIRE `RETURNING OLD.name,
 * t.id` body nullable just because ONE item reads `OLD` was itself a regression this function
 * fixes — `t.id`, untouched by `OLD`/`NEW`, has no reason to lose its real NOT NULL status.
 *
 * [isStarItem] (parenthesized, comment-trailing, and whitespace variants included) is a
 * best-effort, TEXT-ONLY heuristic for recognizing a star item, used here only to short-circuit
 * [OldOrNewReturningAnalysis.forcedColumns] to `null` when this function can already tell the
 * mapping is unreliable (a RECOGNIZED star coincides with an `OLD`/`NEW` reference) — never
 * claimed exhaustive, since a spelling it doesn't recognize is always possible. The CALLER'S
 * item-count-vs-real-column-count cross-check (see [OldOrNewReturningAnalysis]'s KDoc) is a
 * SEPARATE, independent layer for exactly that gap: it catches any star spelling this function's
 * own heuristic misses PROVIDED [splitAtTopLevel]'s split is otherwise correct — i.e. provided the
 * only reason [OldOrNewReturningAnalysis.itemCount] disagrees with the real column count is the
 * star's own expansion. It does NOT, and cannot, catch a case where [splitAtTopLevel] itself
 * mis-splits the list for an UNRELATED reason that happens to move the item count back to
 * matching the real column count by coincidence — this is not hypothetical: an earlier version of
 * this file had exactly that failure, where `splitAtTopLevel` didn't track `[...]`, so an
 * `ARRAY[1, 2]` item's internal comma split into an extra item that numerically canceled out a
 * star's split error (`tgt . *, OLD.tval AS oldv, ARRAY[1, 2] AS arr`: 4 real columns, and a
 * broken split that ALSO produced 4 items, none of which were the real 4 columns) and defeated
 * this exact cross-check. [splitAtTopLevel] now tracks `[...]` too, closing that specific case,
 * but the cross-check's guarantee is still bounded by [splitAtTopLevel]'s own correctness, not
 * unconditional. Precision (forcing only the specific columns identified here) is trustworthy
 * only when [forcedColumns] is non-`null` AND that cross-check passes; otherwise the caller falls
 * back to forcing every column nullable — two independent layers reaching for the same safe
 * conclusion by different means, neither one a substitute for the other's blind spot.
 *
 * @return An empty-[OldOrNewReturningAnalysis.forcedColumns] result (with
 *   [OldOrNewReturningAnalysis.itemCount] `0`) if there is no `RETURNING` clause; empty
 *   [OldOrNewReturningAnalysis.forcedColumns] if no item references `OLD`/`NEW` (recognized star
 *   items included, or not — irrelevant when there is nothing to force); an analysis whose
 *   [OldOrNewReturningAnalysis.itemCount] and [OldOrNewReturningAnalysis.forcedColumns] the
 *   caller must cross-check otherwise (see [OldOrNewReturningAnalysis]'s KDoc).
 */
internal fun oldOrNewReturningColumns(body: String): OldOrNewReturningAnalysis {
  val dml = stripLeadingNestedWithClause(body)
  // findTopLevelReturningKeyword, not a plain findTopLevelKeyword search, so an "AS returning"
  // column alias inside this body's own source SELECT (e.g. "INSERT INTO t SELECT 1 AS returning
  // ... RETURNING a, b") is never mistaken for the real clause keyword — see its KDoc.
  val returningIndex = findTopLevelReturningKeyword(dml)
  if (returningIndex < 0) return OldOrNewReturningAnalysis(0, emptySet())
  val (aliasNames, itemsStart) = parseOldNewAliasPrologue(dml, returningIndex + "RETURNING".length)
  val items = splitAtTopLevel(dml.substring(itemsStart).trim().trimEnd(';'), ',')
  val hasRecognizedStarItem = items.any(::isStarItem)
  val oldOrNewColumnIndices = items.withIndex()
    .filter { (_, item) -> referencesOldOrNew(item, aliasNames) }
    .map { (index, _) -> index + 1 }
    .toSet()
  return if (hasRecognizedStarItem && oldOrNewColumnIndices.isNotEmpty()) {
    OldOrNewReturningAnalysis(items.size, forcedColumns = null)
  } else {
    OldOrNewReturningAnalysis(items.size, oldOrNewColumnIndices)
  }
}

/**
 * Removes every comment AND all whitespace from [text], keeping every other character — including
 * the full contents of a string literal, quoted identifier, or dollar-quoted string — verbatim
 * and in relative order. A comment is always removed OUTRIGHT, with nothing put in its place.
 * Comments are recognized (and dropped) via the same [skipLineComment]/[skipBlockComment] logic
 * [skipWhitespaceAndComments] uses, applied at EVERY position in [text] rather than only at an
 * edge, so this finds and removes a comment ANYWHERE — including one sitting between two
 * otherwise-adjacent tokens (`tgt./*c*/ *`) — not merely one that leads or trails the whole
 * string. A string literal, quoted identifier, or dollar-quoted string is recognized via
 * [skipLexicalToken] and copied through UNCHANGED (including any whitespace or `--`/`/* */`-shaped
 * text inside it, which is real content, not a comment) rather than having its own contents
 * stripped.
 *
 * Used by [isStarItem] to normalize an item before its star check, which needs neither comments
 * nor whitespace to survive — it locates a trailing implicit alias (via
 * [findTrailingImplicitAliasStart]) and inspects the qualifier and star around it, so the
 * separator that used to sit between them (whitespace, a comment, or nothing at all) makes no
 * difference once it's gone.
 */
private fun stripCommentsAndWhitespace(text: String): String {
  val builder = StringBuilder(text.length)
  var i = 0
  while (i < text.length) {
    when {
      text[i].isWhitespace() -> i++
      text[i] == '-' && i + 1 < text.length && text[i + 1] == '-' -> i = skipLineComment(text, i)
      text[i] == '/' && i + 1 < text.length && text[i + 1] == '*' -> i = skipBlockComment(text, i)
      else -> {
        val afterToken = skipLexicalToken(text, i)
        if (afterToken != i) {
          builder.append(text, i, afterToken)
          i = afterToken
        } else {
          builder.append(text[i])
          i++
        }
      }
    }
  }
  return builder.toString()
}

/**
 * Check for whether a single `RETURNING`/`SELECT` item is a star (`*`, `tbl.*`), with or without
 * an implicit (no-`AS`) alias — including parenthesized (`(tgt.*)`), and any placement of
 * comments and whitespace around or BETWEEN its tokens (`tgt.* /*c*/`, `tgt.* -- comment`,
 * `tgt . *`, `tgt./*c*/ *`, `tgt.*whatever`, `tgt.*`/*c*/`whatever`), in ANY order relative to a
 * wrapping `(...)` (a trailing comment can sit inside OR outside the parentheses: `(tgt.*) -- c`,
 * `(tgt.*) /*c*/`).
 *
 * Normalizes by first stripping every comment and all whitespace from the ENTIRE item — via
 * [stripCommentsAndWhitespace], which finds a comment anywhere in the text, not merely at an
 * edge.
 *
 * Two paths, tried in order:
 *
 * PATH 1 — the ORIGINAL `text == "*" || text.endsWith(".*")` check (via
 * [unwrapWrappingParentheses] then a literal suffix comparison), preserved BYTE-FOR-BYTE. For
 * [isStarItem], `false` is the DANGEROUS answer (an unrecognized star lets a later item survive at
 * its raw list position, silently shifted onto the wrong `ResultSetMetaData` column — the
 * #212/#215 failure mode) and `true` is the SAFE one (later items are dropped, falling back to
 * metadata names instead of a wrong mapping), so a rule that already answers `true` for some text
 * must never be replaced by one that answers `false` for that same text — only new `true` answers
 * may be ADDED on top, never removed. This path alone already covers every shape whose NORMALIZED
 * text ends in `.*` verbatim with nothing after it: `t.*`, a parenthesized composite expansion
 * (`(t).*`, `(u.*)`, `((t.*))` — verified valid PostgreSQL syntax; the unwrap loop only requires
 * the OUTERMOST wrapping pair to match, so it repeats until no more wrapping parens remain), a
 * `DISTINCT ON (...)` prefix ([parseSelectItems] strips this before [isStarItem] ever sees it —
 * see its KDoc), and a Unicode-escape quoted identifier (`U&"my*table".*`, verified valid).
 *
 * PATH 2 — reached ONLY when path 1 answers `false`, i.e. only for an item with a trailing
 * implicit alias (something other than `*` is the last character, so path 1's literal suffix check
 * can never match). [findTrailingImplicitAliasStart] finds where that alias starts by walking the
 * text FORWARD and tracking the last SEGMENT seen — PostgreSQL's grammar guarantees an implicit
 * alias is always the FINAL token of a select item, so anchoring on "the last segment reaches
 * exactly the end of the text" is grammar-backed, unlike enumerating everything that may
 * legitimately precede a star's qualifying dot (parentheses, brackets, quotes, a `DISTINCT ON`
 * prefix, a Unicode-escape identifier...) — an earlier version of this function tried exactly that
 * enumeration and missed a new qualifier shape on each of three separate review passes. With the
 * alias located, [unwrapWrappingParentheses] is applied to the PREFIX (the text with that alias
 * removed), and the SAME path-1 logic is re-run on it: accept if the unwrapped prefix is `*`, or if
 * it ends in `.*` AND [isStarQualifierAcceptable] accepts the qualifier (everything before that
 * final `.`) — the ONLY additional check path 2 needs beyond path 1's, since a digit-leading run
 * immediately before the dot (`2.` in `SELECT 2.*3 lbl, a FROM t` — verified arithmetic returning 2
 * columns, not a star) is the ONE lexical ambiguity a numeric literal creates with a real
 * qualifying dot; see [isStarQualifierAcceptable]'s KDoc for why every other qualifier shape is
 * accepted rather than enumerated.
 *
 * Verified against PostgreSQL 18.4: `SELECT u.*whatever, preferences FROM users u` and `SELECT
 * u.*`/*c*/`whatever, preferences FROM users u` both return 5 columns (star expands, implicit alias
 * ignored) — an earlier version of this function required a whitespace- or comment-shaped
 * separator between the star and its implicit alias to even attempt recognizing one, so an alias
 * directly ABUTTING the star (no separator at all, comment or otherwise) went unrecognized,
 * letting the #212 failure mode (a later item shifted onto the wrong `ResultSetMetaData` column)
 * survive for exactly that spelling. `SELECT *whatever, a FROM t` (a BARE star with an abutting
 * alias) is, by contrast, a genuine PostgreSQL syntax error — a bare `*` cannot itself take an
 * alias — so this function's willingness to call it a star for an empty qualifier is unreachable
 * on real input, not a gap that needs closing.
 *
 * NOT claimed exhaustive — see [oldOrNewReturningColumns]'s KDoc for why the caller's
 * real-column-count cross-check, not this function, is what makes the overall mapping fail safe
 * against a spelling this misses. [parseSelectItems] has no such cross-check (it has no
 * independent real-column-count to compare against at the point it runs), so a spelling this
 * function fails to recognize there degrades silently to a wrong, shifted mapping rather than the
 * documented empty-list fail-safe.
 */
private fun isStarItem(item: String): Boolean {
  val text = stripCommentsAndWhitespace(item.trim())
  val unwrappedText = unwrapWrappingParentheses(text)
  if (unwrappedText == "*" || unwrappedText.endsWith(".*")) return true

  val aliasStart = findTrailingImplicitAliasStart(text) ?: return false
  val unwrappedPrefix = unwrapWrappingParentheses(text.substring(0, aliasStart))
  if (unwrappedPrefix == "*") return true
  return unwrappedPrefix.endsWith(".*") && isStarQualifierAcceptable(unwrappedPrefix.dropLast(1))
}

/**
 * Repeatedly strips a wrapping `(...)` from [text] — verified via [findMatchingCloseParenthesis]
 * to be a genuine matching pair (not merely the first and last characters happening to be `(` and
 * `)`) — until none remains: `((t.*))` unwraps in two passes to `t.*`.
 */
private fun unwrapWrappingParentheses(text: String): String {
  var result = text
  while (result.length >= 2 &&
    result.startsWith("(") &&
    result.endsWith(")") &&
    findMatchingCloseParenthesis(result, 0) == result.length - 1
  ) {
    result = result.substring(1, result.length - 1)
  }
  return result
}

/**
 * Finds where a trailing implicit alias starts in [text] (already normalized by
 * [stripCommentsAndWhitespace]), or `null` if there is none.
 *
 * Walks [text] FORWARD, recording the start and end of the last SEGMENT seen (see
 * [matchTrailingAliasSegment] for what counts as one). A character that doesn't start a segment,
 * and a lexical token that ISN'T a segment (a single-quoted string literal, a dollar-quoted
 * string — skipped via [skipLexicalToken]), are walked over without ending the search; they simply
 * mean the segment recorded so far is not the final one. An implicit alias EXISTS only if the last
 * recorded segment ends EXACTLY at `text.length` (nothing trails it) AND starts at an index
 * greater than `0` (there is something — the qualifier and its star — before it; a segment
 * spanning the ENTIRE text is not "prefix plus alias", it's just one bare identifier with no star
 * in it at all, e.g. `preferencesprefs`).
 *
 * @return The index where the trailing alias segment starts, or `null` if [text] has no such
 *   segment.
 */
private fun findTrailingImplicitAliasStart(text: String): Int? {
  var lastSegmentStart = -1
  var lastSegmentEnd = -1
  var i = 0
  while (i < text.length) {
    val segmentEnd = matchTrailingAliasSegment(text, i)
    if (segmentEnd != null) {
      lastSegmentStart = i
      lastSegmentEnd = segmentEnd
      i = segmentEnd
      continue
    }
    val afterToken = skipLexicalToken(text, i)
    i = if (afterToken != i) afterToken else i + 1
  }
  return if (lastSegmentEnd == text.length && lastSegmentStart > 0) lastSegmentStart else null
}

/**
 * Matches ONE segment starting at [start] in [text], in this precedence order:
 * 1. A Unicode-escape identifier — `U&`/`u&` immediately followed by a double-quoted identifier
 *    (via [skipLexicalToken]), optionally followed by the word `UESCAPE` and a single-quoted
 *    escape-character string, in which case the segment extends through that string — see
 *    [matchUnicodeEscapeIdentifierSegment]. Tried FIRST: for `u.*U&"a"`, checking the bare-identifier
 *    rule (3, below) first would match `U` alone as a one-character identifier segment, then
 *    `"a"` as a separate later segment — the alias would appear to end at `"a"`, but the prefix
 *    would wrongly include the dangling `U&`, and the star would never be found as `.` + `*`
 *    immediately before it. Trying the Unicode-escape rule first consumes `U&"a"` as ONE segment,
 *    so the star at `.*` immediately precedes it, exactly as PostgreSQL itself parses it.
 * 2. A bare double-quoted identifier, `"..."` (`""`-doubling included, via [skipLexicalToken]).
 * 3. An unquoted identifier: first character [isIdentifierStartChar] (a letter, `_`, or any
 *    character whose code is `>= 0x80`), every subsequent character [isIdentifierChar] —
 *    PostgreSQL identifiers may not start with a digit or `$`.
 *
 * @return The index immediately after the matched segment, or `null` if [start] does not begin
 *   one.
 */
private fun matchTrailingAliasSegment(text: String, start: Int): Int? {
  matchUnicodeEscapeIdentifierSegment(text, start)?.let { return it }
  if (start >= text.length) return null
  if (text[start] == '"') {
    val afterToken = skipLexicalToken(text, start)
    return if (afterToken != start) afterToken else null
  }
  // PostgreSQL's lexer admits ANY byte >= 0x80 to START an unquoted identifier too (not merely to
  // continue one, which [isIdentifierChar] already covers) — not merely a
  // Unicode `isLetter()`. Verified: a combining mark (an alias written in NFD, e.g. "préfs" spelled
  // p-r-e-COMBINING_ACUTE-f-s), a symbol (a currency sign `€`, `©`, `°`), and a supplementary-plane
  // character (an astral emoji `🚀`, a mathematical alphanumeric symbol `𝐀`) are all legal FIRST
  // characters of an unquoted identifier that PostgreSQL accepts, none of which `isLetter()`
  // recognizes as a letter — `isLetter()` alone therefore MISSED every one of those alias shapes,
  // the dangerous direction (see [isStarItem]'s KDoc). A surrogate pair (as a supplementary-plane
  // character always is, in a Kotlin/UTF-16 `String`) is naturally covered without special
  // handling: BOTH of its code units are >= 0x80, so the ordinary per-character loop below
  // consumes each half in turn. isIdentifierStartChar is the shared predicate for this rule — see
  // its KDoc for the other call sites.
  if (!isIdentifierStartChar(text[start])) return null
  var i = start + 1
  while (i < text.length && isIdentifierChar(text[i])) i++
  return i
}

/**
 * Matches a Unicode-escape identifier — `U&`/`u&` immediately followed by a double-quoted
 * identifier, e.g. `U&"my*table"` — starting at [start] in [text], optionally extended by a
 * `UESCAPE '<char>'` clause naming a custom escape character (PostgreSQL merges the identifier,
 * the `UESCAPE` keyword, and the single-quoted escape-character string into ONE lexical unit —
 * verified: `U&"d!0061t" UESCAPE '!'` and `U&"!0074" UESCAPE '!'` are each a single identifier,
 * both resolving via the `!`-escape to the same characters `U&"data"`/`U&"t"` would spell without
 * one). [text] has already had all whitespace removed by [stripCommentsAndWhitespace], so the
 * `UESCAPE` keyword and its string abut the identifier directly with no separator to skip.
 *
 * @return The index immediately after the identifier (and its `UESCAPE` clause, if present), or
 *   `null` if [start] does not begin a `U&`/`u&`-prefixed double-quoted identifier at all.
 */
private fun matchUnicodeEscapeIdentifierSegment(text: String, start: Int): Int? {
  if (start + 1 >= text.length) return null
  if ((text[start] != 'U' && text[start] != 'u') || text[start + 1] != '&') return null
  val quoteStart = start + 2
  if (quoteStart >= text.length || text[quoteStart] != '"') return null
  val afterIdentifier = skipLexicalToken(text, quoteStart)

  val keyword = "UESCAPE"
  if (!text.regionMatches(afterIdentifier, keyword, 0, keyword.length, ignoreCase = true)) {
    return afterIdentifier
  }
  val afterKeyword = afterIdentifier + keyword.length
  val isWordBoundary = afterKeyword >= text.length || !isIdentifierChar(text[afterKeyword])
  if (!isWordBoundary || afterKeyword >= text.length || text[afterKeyword] != '\'') {
    return afterIdentifier
  }
  val afterEscapeString = skipLexicalToken(text, afterKeyword)
  return if (afterEscapeString != afterKeyword) afterEscapeString else afterIdentifier
}

/**
 * Checks that [qualifierEndingInDot] (the qualifier before a star recognized on path 2 of
 * [isStarItem], guaranteed by its caller to end in `.`) is acceptable — INVERTED relative to an
 * earlier version of this check, which enumerated every character that may legitimately precede
 * the dot (`"`, `)`, `]`, an identifier run) and missed a new shape on each of three separate
 * review passes (a `DISTINCT ON (...)` prefix, a parenthesized composite expansion, an array
 * subscript, a Unicode-escape identifier with a `UESCAPE` clause...). Enumerating acceptable
 * endings is backwards: `false` is the DANGEROUS answer here (see [isStarItem]'s KDoc), so it must
 * be EARNED, not handed out by default whenever a new qualifier shape isn't yet on the list.
 *
 * Rejects ONLY when the run of [isIdentifierChar] characters immediately preceding the final `.`
 * is NON-EMPTY and its first character is an ASCII digit (`'0'..'9'`, NOT `Char.isDigit()`) — a
 * digit-leading run before a dot is a numeric literal (`2.` in `SELECT 2.*3 lbl, a FROM t` —
 * verified arithmetic returning 2 columns, not a star), the ONE lexical ambiguity a real
 * qualifying dot has, and PostgreSQL numeric literals use ASCII digits EXCLUSIVELY — so a
 * non-ASCII digit (Unicode category Nd, e.g. `٣` ARABIC-INDIC DIGIT THREE, `３` FULLWIDTH DIGIT
 * THREE) can only be an identifier's first character there, never a numeral: verified with a real
 * table literally named `٣`, `SELECT ٣.* x, a FROM ٣` returns 3 columns. `Char.isDigit()` is
 * Unicode-aware and would wrongly reject that qualifier as if it were numeric.
 *
 * The run scan uses [isIdentifierChar] — the same predicate every identifier-continuation check in
 * this file shares (see its KDoc), including [matchTrailingAliasSegment]'s own character class —
 * rather than a narrower letter-or-digit-only check: a `>= 0x80` character that is not a letter or
 * digit (`€`, `©`, `¹`) would otherwise truncate the run early, leaving whatever ASCII digit sits
 * before it looking like the run's own start (`x€9.`: scanning backward with a letter-or-digit-only
 * check stops at `€`, making `9` look like the run's start and wrongly rejecting the whole thing as
 * numeric, when the real run is `x€9` — letter-led, and correctly acceptable). Sharing one
 * predicate makes that symmetry STRUCTURAL rather than a comment to remember to keep in sync — a
 * "keep these two in sync" convention note is exactly what failed here, three separate times.
 *
 * Accepts in every other case, INCLUDING an empty run (the character immediately before the dot is
 * `"`, `)`, `]`, or anything else that isn't an identifier-continuation character at all) — this
 * is what lets a Unicode-escape qualifier like `U&"!0074"UESCAPE'!'.` (ending in the escape
 * string's closing `'`) work with no special case for it whatsoever.
 */
private fun isStarQualifierAcceptable(qualifierEndingInDot: String): Boolean {
  val beforeDotIndex = qualifierEndingInDot.length - 2
  if (beforeDotIndex < 0 || !isIdentifierChar(qualifierEndingInDot[beforeDotIndex])) {
    return true
  }
  var runStart = beforeDotIndex
  while (runStart > 0 && isIdentifierChar(qualifierEndingInDot[runStart - 1])) {
    runStart--
  }
  return qualifierEndingInDot[runStart] !in '0'..'9'
}

/**
 * If [keyword] appears at [position] as a complete word — not adjacent to any character
 * PostgreSQL allows inside an identifier (see [isIdentifierChar]), so it isn't merely a prefix
 * of a longer identifier — advances past it and any trailing whitespace/comments. Otherwise
 * returns [position] unchanged.
 *
 * A comment immediately abutting the keyword (`WHEN NOT/*c*/MATCHED`) is a valid separator: `/`
 * is not an identifier character, so the boundary check accepts it, and the trailing
 * `skipWhitespaceAndComments` call then advances past the comment itself.
 */
internal fun skipOptionalKeyword(sql: String, position: Int, keyword: String): Int {
  if (!sql.regionMatches(position, keyword, 0, keyword.length, ignoreCase = true)) return position
  val afterKeyword = position + keyword.length
  if (afterKeyword < sql.length && isIdentifierChar(sql[afterKeyword])) return position
  return skipWhitespaceAndComments(sql, afterKeyword)
}

/**
 * Advances past whitespace, single-line comments (`--`), and block comments (`/* */`).
 *
 * Block comments are matched with nesting depth, following PostgreSQL's documented behavior
 * where `/* ... /* ... */ ... */` is a single comment (the inner `/*` opens a nested comment,
 * and the outer comment only ends at the `*/` that brings the depth back to zero).
 *
 * @return The index of the first non-whitespace, non-comment character at or after [start].
 */
internal fun skipWhitespaceAndComments(sql: String, start: Int): Int {
  var i = start
  while (i < sql.length) {
    when {
      sql[i].isWhitespace() -> i++
      sql[i] == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> i = skipLineComment(sql, i)
      sql[i] == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> i = skipBlockComment(sql, i)
      else -> return i
    }
  }
  return i
}

/** Advances past a `--` line comment starting at [start]. Returns the index after the newline, or `sql.length`. */
private fun skipLineComment(sql: String, start: Int): Int {
  val eol = sql.indexOf('\n', start)
  return if (eol < 0) sql.length else eol + 1
}

/**
 * Advances past a block comment starting at [start] (where `sql[start] == '/'` and
 * `sql[start + 1] == '*'`), honoring nesting depth per PostgreSQL's documented behavior: an
 * inner comment-open opens a nested comment, and the outer comment only ends at the
 * comment-close that brings the nesting depth back to zero.
 *
 * @return The index after the comment's closing delimiter, or `sql.length` if unterminated.
 */
private fun skipBlockComment(sql: String, start: Int): Int {
  var depth = 1
  var j = start + 2
  while (j < sql.length && depth > 0) {
    if (sql[j] == '/' && j + 1 < sql.length && sql[j + 1] == '*') {
      depth++
      j += 2
    } else if (sql[j] == '*' && j + 1 < sql.length && sql[j + 1] == '/') {
      depth--
      j += 2
    } else {
      j++
    }
  }
  return j
}

/**
 * If `sql[position]` begins a lexical token that character-by-character scanners in this file
 * must treat as an OPAQUE unit — a single-quoted string literal (including `E'...'` escape
 * strings and `''`-doubled quotes), a double-quoted identifier (including `""`-doubled quotes),
 * a dollar-quoted string (`$$...$$` or `$tag$...$tag$`, but ONLY when the `$` is not itself
 * continuing an identifier — see the dollar-quote branch below and [skipDollarQuotedString]'s
 * KDoc; PostgreSQL allows `$` inside an unquoted identifier, so `a$b$c` is one identifier, not a
 * dollar-quote-delimited string starting after `a`), a `--` line comment, or a `/* */` block
 * comment — returns the index immediately after that token. Otherwise returns [position]
 * unchanged, meaning the caller should process this character itself (as a keyword character, a
 * parenthesis, a delimiter, etc.).
 *
 * This is the single place that understands enough of SQL's lexical structure to keep the raw
 * text scanners in this file ([findTopLevelKeyword], [findMatchingCloseParenthesis],
 * `splitAtTopLevel`, `extractAlias`, and any other paren-depth or keyword search) from
 * misreading a `(`, `)`, or keyword that only APPEARS inside a string, a quoted identifier, or a
 * comment — e.g. `RETURNING regexp_replace(name, '\(', '')` has an unbalanced `(` inside its
 * string literal, and `SET name = 'copied from source'` has the word `from` inside a string
 * literal, neither of which is a real paren or keyword. Every such scanner MUST call this at
 * each position and jump ahead when it returns a different index, rather than inspecting
 * `sql[position]` directly — as of this writing, every character-by-character scanner in this
 * file does.
 *
 * @return The index after the lexical token, or [position] if none starts there.
 */
internal fun skipLexicalToken(sql: String, position: Int): Int {
  if (position >= sql.length) return position
  return when {
    sql[position] == '\'' -> skipSingleQuotedString(sql, position)
    sql[position] == '"' -> skipDoubleQuotedIdentifier(sql, position)
    // A "$" immediately after an identifier character (e.g. the second "$" in "a$b$c", or
    // either "$" in "x$$y") can never OPEN a dollar quote — it is continuing the identifier
    // that started before it, exactly as PostgreSQL's own lexer only recognizes dollar-quote
    // tags at the START of a new token. Without this guard, "a$b$c" reads as "a" followed by a
    // "$b$"-tagged dollar-quote opener that swallows everything up to the next literal "$b$" (or
    // the rest of the string, if there isn't one).
    sql[position] == '$' && !(position > 0 && isIdentifierChar(sql[position - 1])) ->
      skipDollarQuotedString(sql, position) ?: position
    sql[position] == '-' && position + 1 < sql.length && sql[position + 1] == '-' -> skipLineComment(sql, position)
    sql[position] == '/' && position + 1 < sql.length && sql[position + 1] == '*' -> skipBlockComment(sql, position)
    else -> position
  }
}

/**
 * Advances past a single-quoted string literal opening at [openQuoteIndex] (where
 * `sql[openQuoteIndex] == '\''`), honoring both `''`-doubled-quote escapes (standard SQL, valid
 * in any string) and, for an `E'...'` escape string (detected by a standalone `E`/`e`
 * immediately before the opening quote), backslash escapes (`\'`, `\\`, etc. — a backslash
 * always consumes the following character as a literal, so it can never end the string).
 *
 * The "standalone" check — the character before that `E`/`e`, if any, is not itself a letter,
 * digit, or `_` — deliberately does NOT use [isIdentifierChar], even though every other
 * word-boundary check in this file does. [isIdentifierChar] is evaluated against
 * [stripCommentsAndWhitespace]'s STRIPPED output, and this file's own generator round for
 * issue #219 established (against real PostgreSQL 18.4) that [isStarItem] normalizes a select
 * item through [stripCommentsAndWhitespace] and then RE-LEXES the stripped string — so deleting a
 * separator PostgreSQL actually lexed on can manufacture tokens that were never in the query.
 * Widening this lookback to [isIdentifierChar] made `x E'a\'b'` (valid PostgreSQL: the
 * `AexprConst: func_name Sconst` typed-literal form, `func_name` here being the ordinary
 * identifier `x`) normalize to `xE'a\'b'` — the space PostgreSQL lexed as a separator between two
 * tokens is gone — which then mis-lexed as a SINGLE standalone-`E` escape string, turning
 * `parseSelectItems("SELECT (f(x€ E'a\\'b')).* y, id FROM t")` from `[]` on the pre-#219 code —
 * the fail-safe answer, since callers fall back to `ResultSetMetaData.getColumnName` — into 2
 * items, a WRONG positional split that misapplies a column-level type override. Replacing a safe
 * answer with a dangerous one is not acceptable even though the wider check is more faithful to
 * PostgreSQL's own lexer for raw, un-stripped SQL.
 *
 * The narrow letter/digit/`_` class here does NOT match PostgreSQL's lexer for raw SQL (it misses
 * `$` and any `>= 0x80` character, both legal identifier-continuation characters) — that is
 * DELIBERATE, pending a fix to the root cause: [stripCommentsAndWhitespace] returning an index map
 * back to the original text, so that every multi-character adjacency decision made against its
 * stripped output — the `--` line-comment opener, the block-comment opener, the `$` dollar-quote
 * identifier lookback, and this standalone-`E` lookback — can be gated on ORIGINAL adjacency
 * instead. Do not re-widen
 * this to [isIdentifierChar] before that fix lands.
 *
 * @return The index after the closing quote, or `sql.length` if unterminated.
 */
private fun skipSingleQuotedString(sql: String, openQuoteIndex: Int): Int {
  val precedingChar = if (openQuoteIndex > 0) sql[openQuoteIndex - 1] else null
  val precedingCharIsStandalone = openQuoteIndex < 2 ||
    !(sql[openQuoteIndex - 2].isLetterOrDigit() || sql[openQuoteIndex - 2] == '_')
  val isEscapeString = (precedingChar == 'E' || precedingChar == 'e') && precedingCharIsStandalone
  var i = openQuoteIndex + 1
  while (i < sql.length) {
    if (isEscapeString && sql[i] == '\\' && i + 1 < sql.length) {
      i += 2
      continue
    }
    if (sql[i] == '\'') {
      i++
      if (i < sql.length && sql[i] == '\'') {
        i++ // doubled '' — an escaped quote, not the terminator
      } else {
        return i
      }
    } else {
      i++
    }
  }
  return i
}

/**
 * Advances past a double-quoted identifier opening at [openQuoteIndex] (where
 * `sql[openQuoteIndex] == '"'`), honoring `""`-doubled-quote escapes (`"foo""bar"` is the single
 * identifier `foo"bar`). Unlike string literals, double-quoted identifiers do not support
 * backslash escapes.
 *
 * @return The index after the closing quote, or `sql.length` if unterminated.
 */
private fun skipDoubleQuotedIdentifier(sql: String, openQuoteIndex: Int): Int {
  var i = openQuoteIndex + 1
  while (i < sql.length) {
    if (sql[i] == '"') {
      i++
      if (i < sql.length && sql[i] == '"') {
        i++ // doubled "" — an escaped quote, not the terminator
      } else {
        return i
      }
    } else {
      i++
    }
  }
  return i
}

/**
 * True if [character] can continue a dollar-quote TAG after its first character, per
 * PostgreSQL's `scan.l`: `dolq_cont = [A-Za-z\200-\377_0-9]`. A tag's first character is instead
 * gated by [isIdentifierStartChar] — per `scan.l`, `dolq_start` and `ident_start` (an ordinary
 * identifier's first character) are the IDENTICAL class, so this file shares one predicate for
 * both (see [isIdentifierStartChar]'s KDoc). This continuation predicate does NOT admit `$`
 * (unlike [isIdentifierChar], an ordinary identifier's continuation class), since `$` delimits the
 * tag rather than continuing it — this is the one place `scan.l` splits the two kinds of run
 * apart, which is why continuation stays a separate predicate even though start does not. It DOES
 * admit digits, unlike a tag's first character (a tag may not START with one — PostgreSQL rejects
 * `$1$foo$1$` as a dollar-quoted string entirely, leaving the `$1` to be read as an ordinary,
 * non-quote `$`-prefixed token instead) — only a tag's first character is restricted, per
 * `scan.l`'s `dolq_start`/`dolq_cont` split.
 */
private fun isDollarQuoteTagContinuationChar(character: Char): Boolean =
  character.isLetterOrDigit() || character == '_' || character.code >= 0x80

/**
 * If `sql[position]` starts a dollar-quote opening tag — `$$` or `$tag$`, where `tag` is either
 * empty or a run beginning with an [isIdentifierStartChar] character and continuing with
 * [isDollarQuoteTagContinuationChar] characters — advances past the matching closing tag (the
 * same `$$`/`$tag$` again).
 *
 * Callers MUST first confirm [position] is not immediately preceded by an identifier character
 * (see [skipLexicalToken]'s call site) — this function has no way to tell, from `$` alone,
 * whether it is looking at a genuine dollar-quote opener or the second `$` of an ordinary
 * identifier like `a$b$c` (PostgreSQL allows `$` inside an unquoted identifier), so that
 * decision is made by the caller before this is even invoked.
 *
 * @return The index after the closing tag (or `sql.length` if unterminated), or `null` if
 *   [position] is a `$` that is not followed by a valid closing tag delimiter at all (e.g. a
 *   bare `$` used as an operator, or a positional parameter marker like `$1` with no matching
 *   second `$`) — the caller should treat that `$` as an ordinary character, not lexically skip it.
 */
private fun skipDollarQuotedString(sql: String, position: Int): Int? {
  var i = position + 1
  val tagStart = i
  if (i < sql.length && isIdentifierStartChar(sql[i])) {
    i++
    while (i < sql.length && isDollarQuoteTagContinuationChar(sql[i])) i++
  }
  if (i >= sql.length || sql[i] != '$') return null
  val tag = sql.substring(tagStart, i)
  val openingTagEnd = i + 1
  val closingTag = "$" + tag + "$"
  val closeIndex = sql.indexOf(closingTag, openingTagEnd)
  return if (closeIndex < 0) sql.length else closeIndex + closingTag.length
}

/**
 * Replaces `?` parameter placeholders with `NULL` for use in view definitions.
 *
 * PostgreSQL views cannot contain parameter placeholders. This function replaces each `?` that
 * represents a parameter with `NULL`, which allows the query to be used as a view body for
 * node tree analysis. The replacement value does not affect nullability analysis — outer join
 * nullability is determined by join structure, not parameter values.
 *
 * Correctly skips `?` inside:
 * - Single-quoted string literals (`'really?'`), including `''` escape sequences
 * - Line comments (`-- why?`)
 * - Block comments (`/* what? */`)
 *
 * Note: Dollar-quoted string literals (`$$...$$`) are not handled. A `?` inside a dollar-quoted
 * string would be replaced with `NULL`. This is an acceptable limitation since dollar quoting is
 * only used in function bodies, not in WHERE clauses or other contexts where Norm analyzes queries.
 */
internal fun replaceParameterPlaceholders(sql: String): String {
  if ('?' !in sql) return sql
  // +20: NULL is 3 chars longer than ?, so +20 accommodates ~6 replacements before a reallocation.
  val result = StringBuilder(sql.length + 20)
  var i = 0
  while (i < sql.length) {
    when {
      sql[i] == '\'' -> {
        result.append('\'')
        i++
        while (i < sql.length) {
          if (sql[i] == '\'') {
            result.append('\'')
            i++
            if (i < sql.length && sql[i] == '\'') {
              result.append('\'')
              i++
            } else {
              break
            }
          } else {
            result.append(sql[i])
            i++
          }
        }
      }
      sql[i] == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
        val eol = sql.indexOf('\n', i)
        if (eol < 0) {
          result.append(sql, i, sql.length)
          i = sql.length
        } else {
          result.append(sql, i, eol + 1)
          i = eol + 1
        }
      }
      sql[i] == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
        val close = sql.indexOf("*/", i + 2)
        if (close < 0) {
          result.append(sql, i, sql.length)
          i = sql.length
        } else {
          result.append(sql, i, close + 2)
          i = close + 2
        }
      }
      sql[i] == '?' -> {
        result.append("NULL")
        i++
      }
      else -> {
        result.append(sql[i])
        i++
      }
    }
  }
  return result.toString()
}

/**
 * Replaces `?` parameter placeholders with typed non-null sentinel values.
 *
 * Each `?` is replaced with the corresponding sentinel from [sentinels] (consumed in order).
 * If there are more `?` placeholders than sentinels, excess `?` are replaced with `NULL`
 * (safe fallback). Question marks inside string literals and comments are left untouched.
 *
 * @param sql The SQL text with `?` parameter placeholders.
 * @param sentinels Non-null sentinel expressions in parameter order (e.g., `"0::int4"`, `"''::text"`).
 * @return The SQL with `?` replaced by sentinels.
 */
internal fun replaceParameterPlaceholdersWithSentinels(sql: String, sentinels: List<String>): String {
  if ('?' !in sql) return sql
  val result = StringBuilder(sql.length + sentinels.sumOf { it.length })
  var characterIndex = 0
  var sentinelIndex = 0
  while (characterIndex < sql.length) {
    when {
      sql[characterIndex] == '\'' -> {
        result.append('\'')
        characterIndex++
        while (characterIndex < sql.length) {
          if (sql[characterIndex] == '\'') {
            result.append('\'')
            characterIndex++
            if (characterIndex < sql.length && sql[characterIndex] == '\'') {
              result.append('\'')
              characterIndex++
            } else {
              break
            }
          } else {
            result.append(sql[characterIndex])
            characterIndex++
          }
        }
      }
      sql[characterIndex] == '-' && characterIndex + 1 < sql.length && sql[characterIndex + 1] == '-' -> {
        val endOfLine = sql.indexOf('\n', characterIndex)
        if (endOfLine < 0) {
          result.append(sql, characterIndex, sql.length)
          characterIndex = sql.length
        } else {
          result.append(sql, characterIndex, endOfLine + 1)
          characterIndex = endOfLine + 1
        }
      }
      sql[characterIndex] == '/' && characterIndex + 1 < sql.length && sql[characterIndex + 1] == '*' -> {
        val close = sql.indexOf("*/", characterIndex + 2)
        if (close < 0) {
          result.append(sql, characterIndex, sql.length)
          characterIndex = sql.length
        } else {
          result.append(sql, characterIndex, close + 2)
          characterIndex = close + 2
        }
      }
      sql[characterIndex] == '?' -> {
        result.append(sentinels.getOrElse(sentinelIndex) { "NULL" })
        sentinelIndex++
        characterIndex++
      }
      else -> {
        result.append(sql[characterIndex])
        characterIndex++
      }
    }
  }
  return result.toString()
}

/**
 * Returns a non-null SQL literal expression for the given PostgreSQL type name.
 *
 * Used to replace `?` parameter placeholders with typed non-null constants when creating
 * temporary views for nullability analysis. Strict functions like `digest(?, ?)` need non-null
 * inputs to correctly evaluate as non-null in the query's node tree.
 *
 * Falls back to `NULL::<typeName>` for unrecognized types, which is safe (produces a nullable
 * result — the conservative direction).
 *
 * @param typeName The PostgreSQL type name from `ParameterMetaData.getParameterTypeName()`.
 * @return A SQL expression that is a valid non-null literal of the given type.
 */
internal fun nonNullSentinel(typeName: String): String = when (typeName) {
  "int2", "int4", "int8", "float4", "float8", "numeric", "oid" -> "0::$typeName"
  "text", "varchar", "bpchar", "char", "name" -> "''::$typeName"
  "bool" -> "false::bool"
  "bytea" -> "'\\x00'::bytea"
  "date" -> "'2000-01-01'::date"
  "timestamp" -> "'2000-01-01'::timestamp"
  "timestamptz" -> "'2000-01-01'::timestamptz"
  "time" -> "'00:00:00'::time"
  "timetz" -> "'00:00:00'::timetz"
  "interval" -> "'0'::interval"
  "uuid" -> "'00000000-0000-0000-0000-000000000000'::uuid"
  "json" -> "'{}'::json"
  "jsonb" -> "'{}'::jsonb"
  "xml" -> "'<x/>'::xml"
  "inet", "cidr" -> "'0.0.0.0/0'::$typeName"
  "macaddr", "macaddr8" -> "'00:00:00:00:00:00'::$typeName"
  else -> if (typeName.startsWith("_")) {
    // Array types: _int4, _text, etc.
    "ARRAY[]::$typeName"
  } else {
    // Unknown type — fall back to NULL (safe: column becomes nullable).
    "NULL::$typeName"
  }
}
