package norm.generator

/**
 * "The characters at [leftIndex] and `leftIndex + 1` were adjacent in the ORIGINAL SQL text" — the
 * fact every multi-character lexical decision in this file that spans two adjacent characters (a
 * `--` line-comment or `/* */` block-comment opener, a `$`-prefixed dollar-quote's identifier
 * lookback, a standalone-`E` escape-string marker's lookback, a `''`/`""` doubled-quote escape, and
 * [matchTrailingAliasSegment]'s own bare-identifier run stopping before a `$` that should instead
 * open a fresh dollar-quoted string) must be gated on, whenever the text being scanned might be
 * [stripCommentsAndWhitespace]'s STRIPPED output rather than raw SQL.
 *
 * This exists because deleting a separator PostgreSQL itself lexed on can manufacture a token that
 * was never in the query: `1 - -1` (two separate `-` tokens, genuinely separated by a space) strips
 * to `1--1`, which [skipLexicalToken] would otherwise read as a `--` line comment that was never
 * there. [StrippedText] is the sole [OriginalAdjacency] implementation with real gaps to report —
 * see its KDoc — while [ALL_ADJACENT] is what every RAW-text caller passes (implicitly, via the
 * default parameter on [skipLexicalToken]/[findMatchingCloseParenthesis]): for text that was never
 * stripped, every neighbouring pair of characters genuinely IS adjacent, so the gate is always
 * satisfied and raw-text callers see no behavior change at all.
 */
internal fun interface OriginalAdjacency {
  fun wereAdjacent(leftIndex: Int): Boolean
}

/** The correct [OriginalAdjacency] for raw, never-stripped SQL text — see its KDoc. */
internal val ALL_ADJACENT: OriginalAdjacency = OriginalAdjacency { true }

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
 * @param adjacency See [OriginalAdjacency]'s KDoc. Defaults to [ALL_ADJACENT], correct for raw SQL
 *   text; [StrippedText] threads itself here for its own [StrippedText.findMatchingCloseParenthesis]
 *   entry point.
 * @return The index of the matching `)`, or `-1` if unbalanced.
 */
internal fun findMatchingCloseParenthesis(
  text: String,
  openParenthesisIndex: Int,
  adjacency: OriginalAdjacency = ALL_ADJACENT,
): Int {
  var depth = 1
  var i = openParenthesisIndex + 1
  while (i < text.length && depth > 0) {
    val afterToken = skipLexicalToken(text, i, adjacency)
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
private data class OutputItemWithAlias(val selectItem: SelectItem, val alias: String?)

/**
 * The shared parsing core behind both [parseSelectItems] (which discards [OutputItemWithAlias.alias])
 * and [resolveCteOutputExpression] (which needs the alias to match a CTE body item against the
 * outer name it's exposed under — see that function's KDoc for why the alias can't be dropped
 * there). Locates the same output clause [parseSelectItems] documents finding — see its KDoc for
 * the window/`RETURNING`-gating/star-truncation rules, all of which apply identically here.
 */
private fun parseOutputItemsWithAlias(sql: String): List<OutputItemWithAlias> {
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
    // first item's own expression — skip past it via parseOldNewAliasPrologue (the same helper
    // oldOrNewReturningColumns already uses for nullability) so itemsStart lands on the real first
    // item, not on `WITH (OLD AS o, NEW AS n) o.x` (which parseColumnReference cannot make sense
    // of, so it would otherwise be embedded verbatim in generated KDoc as that column's expression).
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
 * Resolves the SQL expression text that produced a CTE-wrapped result column, when the outer
 * query's own select item is a plain reference into a CTE's output (rather than a computed
 * expression itself). This is the missing half of provenance for issue #229's shape: a result
 * column that is BOTH CTE-wrapped AND expression-derived — [parseSelectItems] only ever looks at
 * the OUTER select list, where such a column is a bare name (`description_upper`), never the
 * `UPPER(description)` expression that actually produced it inside the CTE body.
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
 * - The outer reference is NOT an unquoted bare [PG_RESERVED_KEYWORDS] name (`user`, `true`,
 *   `system_user`, ...) — see that constant's own KDoc for why PostgreSQL never resolves one of
 *   these to a column, no matter how confidently everything else about this shape lines up, so an
 *   outer `user` can never denote a body item aliased `AS user`.
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
 *   `RETURNING` clause is fine and expected — that is issue #229's own reported shape.
 * - The main query is a plain `SELECT`, not DML (`INSERT`/`UPDATE`/`DELETE`/`MERGE`) — a DML main
 *   query's `RETURNING` list resolves against its OWN target relation plus any `USING`/`FROM`
 *   sources, which reintroduces exactly the DML-target-vs-CTE-name confusion this function exists
 *   to avoid, for marginal benefit (issue #229 never asked for that shape).
 * - The outer reference, if qualified, folds (see [foldIdentifier]) to the same name as the CTE
 *   itself, compared via [CteDefinition.rawName] — NEVER [CteDefinition.name], which has already
 *   had its quotes stripped and so can no longer tell a quoted `"Foo"` apart from an unquoted
 *   `foo` (a DIFFERENT relation in PostgreSQL); see [CteDefinition.rawName]'s own KDoc. This is
 *   the only name the FROM clause makes it addressable as, per the first bullet above (also
 *   compared via `rawName`, for the same reason).
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
 * @return The CTE body's expression text (e.g. `UPPER(description)`), or `null` if any
 *   precondition above fails to hold.
 */
internal fun resolveCteOutputExpression(sql: String, selectItem: SelectItem): String? {
  val outputName = selectItem.columnName ?: return null
  // A bare PostgreSQL reserved word is never resolved as a column -- see PG_RESERVED_KEYWORDS'
  // own KDoc -- but ONLY when unquoted: quoting suppresses that entirely (a quoted "user" is an
  // ordinary column reference), so this must gate on selectItem.isColumnNameQuoted explicitly
  // rather than fold-comparing regardless of it -- outputName is a LOGICAL value (see
  // SelectItem.columnName's own KDoc) that could coincidentally already be lowercase even when
  // quoted, which a fold-without-gating would wrongly treat as matching the (all-lowercase)
  // reserved set.
  if (!selectItem.isColumnNameQuoted && foldIdentifier(outputName, isQuoted = false) in PG_RESERVED_KEYWORDS) {
    return null
  }

  val cteClause = parseCteClause(sql) ?: return null
  if (cteClause.isRecursive) return null

  val cte = cteClause.definitions.singleOrNull() ?: return null
  if (cte.hasColumnList) return null

  // Compared via cte.rawName, NOT cte.name -- CteDefinition.name has already had its quotes
  // stripped, so folding IT loses the quoted/unquoted distinction PostgreSQL's own identifier
  // resolution depends on: it would wrongly let an unquoted "foo" match a CTE declared as "Foo"
  // (a DIFFERENT relation in PostgreSQL) and wrongly reject a quoted "Foo" reference to that same
  // CTE. See CteDefinition.rawName's own KDoc for why it, not name, is the field to use here.
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
 * Collapses the cosmetic whitespace [stripComments]' own single-space substitution can leave
 * behind in an expression about to be embedded VERBATIM in generated KDoc — a comment sitting
 * directly after an opening parenthesis or before a closing one (`UPPER(/* x */a)` strips to
 * `UPPER( a)`) reads oddly there, even though inserting that space is exactly right for
 * [stripComments]' OWN purpose of never fusing two tokens a comment used to separate.
 *
 * Applied ONLY at the point [resolveCteOutputExpression] returns an expression for KDoc, never
 * inside [stripComments] itself: [stripComments]' OTHER caller
 * ([mainQueryFromIsExactlyThisCte]'s own `fromText`) needs the inserted space PRESERVED, to keep
 * the token boundaries that caller's own exact-text comparison depends on intact.
 *
 * Collapses any run of whitespace to a single space, then removes a single space immediately
 * after `(` or immediately before `)` — whitespace directly adjacent to a parenthesis is never
 * semantically significant in SQL, so removing it here can never change what the expression means.
 */
private fun collapseCosmeticWhitespace(text: String): String {
  val singleSpaced = text.trim().replace(Regex("\\s+"), " ")
  return singleSpaced.replace("( ", "(").replace(" )", ")")
}

/**
 * Folds a RAW identifier the way PostgreSQL folds an identifier reference for comparison: an
 * UNQUOTED identifier is folded via [foldAsciiCase] (PostgreSQL's own default case-folding for an
 * unquoted identifier — deliberately NOT Kotlin's `String.lowercase()`; see [foldAsciiCase]'s own
 * KDoc for why); a QUOTED identifier (`"..."`) is compared EXACTLY, with only the surrounding
 * quotes removed and any doubled `""` escape collapsed to the single literal `"` it represents —
 * via [unescapeQuotedIdentifier], never folded at all, since quoting is precisely how PostgreSQL
 * preserves a name's original case against that default folding. Skipping the unescape step here
 * would be exactly the [parseAliasToken] truncation bug one level up: two DIFFERENT quoted names
 * that happen to share the same text up to their first internal `"` (e.g. `"zz""q"` and a lone
 * `"zz"`) would wrongly fold to the SAME value instead of two distinct ones.
 *
 * [rawIdentifier] must be exactly what was written in the source SQL for one identifier position,
 * quotes and all where present. Two callers pass RAW text through here: [extractAlias]'s alias
 * text (via [parseAliasToken], which is itself `""`-escape-aware) and [CteDefinition.rawName]
 * (never [CteDefinition.name], which has already had its quotes stripped — quoting changes
 * case-folding, so a stripped name can no longer tell a quoted `"Foo"` apart from an unquoted
 * `foo`, a DIFFERENT relation in PostgreSQL; see [CteDefinition.rawName]'s own KDoc).
 * [CteDefinition.rawName] itself is NOT `""`-escape-aware — [parseSingleCteDefinition]'s own
 * quoted-name reading stops at the first `"` — so an escaped CTE name (`"He""llo"`) is already
 * truncated before it ever reaches this function; that specific gap is issue #238's, deliberately
 * unrelated to and unfixed by this function's own escape handling.
 *
 * A [SelectItem.tableName]/[SelectItem.columnName], in CONTRAST, is a LOGICAL value with its
 * quotes already removed by [parseColumnReference] (see [SelectItem]'s own KDoc) — passing one of
 * those through THIS overload would always take the unquoted, folded branch regardless of
 * whether the source was actually quoted, silently discarding exactly the distinction PostgreSQL
 * folding depends on. Use the two-argument overload below for those instead, passing
 * [SelectItem.isColumnNameQuoted]/[SelectItem.isTableNameQuoted] explicitly.
 */
private fun foldIdentifier(rawIdentifier: String): String {
  val trimmed = rawIdentifier.trim()
  return if (isQuotedIdentifier(trimmed)) unescapeQuotedIdentifier(trimmed) else foldAsciiCase(trimmed)
}

/**
 * Folds a LOGICAL identifier value (quotes already removed, any doubled `""` escape already
 * collapsed — see [SelectItem]'s own KDoc) the way PostgreSQL folds an identifier reference for
 * comparison, using [isQuoted] (captured separately at parse time, since [logicalValue] itself no
 * longer carries the quotes that would otherwise signal which rule applies) rather than sniffing
 * [logicalValue]'s own text: a QUOTED reference compares EXACTLY (returned unchanged); an
 * UNQUOTED one folds via [foldAsciiCase] — deliberately NOT Kotlin's `String.lowercase()`; see
 * that function's own KDoc for why.
 *
 * This is the overload [SelectItem.columnName]/[SelectItem.tableName] must fold through — see the
 * single-argument overload's own KDoc for why passing a logical value there instead would silently
 * discard the quoted/unquoted distinction.
 */
private fun foldIdentifier(logicalValue: String, isQuoted: Boolean): String =
  if (isQuoted) logicalValue else foldAsciiCase(logicalValue)

/**
 * Folds ONLY the ASCII letters `A`-`Z` to lowercase, leaving every other character — any
 * NON-ASCII letter included — untouched. Both [foldIdentifier] overloads fold an unquoted
 * identifier through THIS function, deliberately never through Kotlin's own `String.lowercase()`,
 * which applies full Unicode case mapping instead.
 *
 * PostgreSQL's own case-folding for an unquoted identifier (`downcase_identifier` in `scan.l`)
 * folds ONLY plain ASCII `A`-`Z`, never a non-ASCII letter, even one with an obvious upper/lower
 * pairing — verified directly against a live PostgreSQL 18.4: with a column named `"ü"` (quoted,
 * lowercase), the bare, unquoted reference `SELECT Ü FROM t` fails outright (`column "Ü" does
 * not exist`) — PostgreSQL does NOT fold `Ü` down to `ü` the way it folds plain ASCII `A` to `a`.
 * The SAME bare `Ü` instead resolves successfully against a column actually named `"Ü"` (quoted,
 * uppercase). Using `String.lowercase()` here would fold `Ü` to `ü` and make this file disagree
 * with PostgreSQL about which of two differently-cased, non-ASCII-named body aliases an unquoted
 * outer reference actually targets — exactly the cross-match mistake [foldIdentifier] exists to
 * prevent, one level up. Do NOT "fix" this back to `String.lowercase()` — the narrower, ASCII-only
 * behavior is deliberate PostgreSQL parity, not an oversight.
 */
private fun foldAsciiCase(text: String): String {
  val builder = StringBuilder(text.length)
  for (character in text) {
    builder.append(if (character in 'A'..'Z') character.lowercaseChar() else character)
  }
  return builder.toString()
}

/**
 * Whether [rawIdentifier] (as written in the source SQL) is a double-quoted identifier —
 * surrounded by a `"` on both ends, with at least the two quote characters themselves present.
 * Used by [foldIdentifier] to decide whether to fold or compare exactly.
 */
private fun isQuotedIdentifier(rawIdentifier: String): Boolean {
  val trimmed = rawIdentifier.trim()
  return trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')
}

/**
 * PostgreSQL's full RESERVED keyword set — every keyword `pg_get_keywords()` categorizes `R`
 * ("reserved") or `T` ("reserved, can be function or type name"), lowercased. Verified against a
 * live PostgreSQL 18.4 (`SELECT word FROM pg_get_keywords() WHERE catcode IN ('R', 'T')`); ~100
 * entries, listed below by category for anyone re-verifying against a future version.
 *
 * Used by [resolveCteOutputExpression] to reject a bare outer reference matching one of these:
 * PostgreSQL never resolves a bare, UNQUOTED reserved word to a column, no matter what a CTE body
 * happens to alias — either it resolves to a runtime VALUE instead (a niladic keyword like
 * `user`, or a literal like `true`/`false`/`null` — verified directly: `WITH c AS (SELECT 'x' AS
 * user) SELECT user FROM c` returns the session user, e.g. `postgres`, never `'x'`; same for
 * `system_user`, added as a niladic keyword in PostgreSQL 16), or the word cannot appear as a
 * bare, unaliased select-list item AT ALL (`select`, `from`, `where`, ...) — a real query
 * containing one could never have reached this far through the pipeline in the first place, so
 * covering that half of the set costs nothing and simply can never fire. Quoting suppresses ALL of
 * this (`SELECT "user"` remains an ordinary column reference), but [resolveCteOutputExpression]
 * does not need to check for it explicitly: [SelectItem.columnName] is always an UNQUOTED
 * identifier already (see [foldIdentifier]'s KDoc), so a quoted reference never reaches this
 * comparison with quotes still attached in the first place.
 *
 * WHY THE FULL RESERVED SET, not just the handful of keywords known to be niladic today: for a
 * wrong emission, the bare outer name must be parseable by PostgreSQL as a value expression on
 * its own — which only a niladic keyword or a bare literal can be. A reserved word that is
 * NEITHER of those cannot appear as a bare select-list item at all (a syntax error), so including
 * it here is free insurance, not a guess. Listing only the keywords currently known to be niladic
 * (as the previous, narrower version of this set did) left a genuine gap: `system_user` became
 * niladic in PostgreSQL 16 without this set being updated, and was missed until reported.
 * Covering the WHOLE reserved category absorbs any future niladic addition automatically, without
 * requiring this set to be revisited every time PostgreSQL adds one.
 *
 * RESIDUAL DRIFT RISK: this is still a lowercased snapshot of one version's `pg_get_keywords()`
 * output, not a live query against the target PostgreSQL server — if a future PostgreSQL version
 * either reserves a genuinely new word (rare) or, in the other direction, DE-reserves one of these
 * (rarer still), this set would be stale until manually re-verified and updated. That risk is
 * accepted as unlikely enough, and the failure mode cheap enough (an extra, unnecessary `null`,
 * never a wrong value), to not warrant a live keyword query from this string-based analysis layer.
 *
 * Category `R` — reserved (cannot be an identifier at all unless quoted):
 * `all`, `analyse`, `analyze`, `and`, `any`, `array`, `as`, `asc`, `asymmetric`, `both`, `case`,
 * `cast`, `check`, `collate`, `column`, `constraint`, `create`, `current_catalog`,
 * `current_date`, `current_role`, `current_time`, `current_timestamp`, `current_user`, `default`,
 * `deferrable`, `desc`, `distinct`, `do`, `else`, `end`, `except`, `false`, `fetch`, `for`,
 * `foreign`, `from`, `grant`, `group`, `having`, `in`, `initially`, `intersect`, `into`,
 * `lateral`, `leading`, `limit`, `localtime`, `localtimestamp`, `not`, `null`, `offset`, `on`,
 * `only`, `or`, `order`, `placing`, `primary`, `references`, `returning`, `select`,
 * `session_user`, `some`, `symmetric`, `system_user`, `table`, `then`, `to`, `trailing`, `true`,
 * `union`, `unique`, `user`, `using`, `variadic`, `when`, `where`, `window`, `with`.
 *
 * Category `T` — reserved, but can also be used as a function or type name:
 * `authorization`, `binary`, `collation`, `concurrently`, `cross`, `current_schema`, `freeze`,
 * `full`, `ilike`, `inner`, `is`, `isnull`, `join`, `left`, `like`, `natural`, `notnull`, `outer`,
 * `overlaps`, `right`, `similar`, `tablesample`, `verbose`.
 */
private val PG_RESERVED_KEYWORDS = setOf(
  "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "both", "case",
  "cast", "check", "collate", "column", "constraint", "create", "current_catalog",
  "current_date", "current_role", "current_time", "current_timestamp", "current_user", "default",
  "deferrable", "desc", "distinct", "do", "else", "end", "except", "false", "fetch", "for",
  "foreign", "from", "grant", "group", "having", "in", "initially", "intersect", "into",
  "lateral", "leading", "limit", "localtime", "localtimestamp", "not", "null", "offset", "on",
  "only", "or", "order", "placing", "primary", "references", "returning", "select",
  "session_user", "some", "symmetric", "system_user", "table", "then", "to", "trailing", "true",
  "union", "unique", "user", "using", "variadic", "when", "where", "window", "with",
  "authorization", "binary", "collation", "concurrently", "cross", "current_schema", "freeze",
  "full", "ilike", "inner", "is", "isnull", "join", "left", "like", "natural", "notnull", "outer",
  "overlaps", "right", "similar", "tablesample", "verbose",
)

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
 *   [foldIdentifier]'s KDoc for why folding the already-quote-stripped `name` would wrongly equate
 *   a quoted CTE with a differently-quoted or unquoted relation of the same letters.
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

/**
 * Removes every `--` line comment and `/* */` block comment from [text], replacing each one with a
 * SINGLE space rather than deleting it outright — so that two tokens a comment used to separate
 * (the far more ordinary `d\n-- only the active ones\nWHERE`, where the line comment's own
 * trailing newline is what [skipLineComment] consumes along with the comment text itself) can
 * never fuse into one run once the comment text is gone. Preserves everything else — including
 * ordinary whitespace, string literals, quoted identifiers, and dollar-quoted strings — verbatim.
 */
private fun stripComments(text: String): String {
  val builder = StringBuilder(text.length)
  var i = 0
  while (i < text.length) {
    when {
      text[i] == '-' && i + 1 < text.length && text[i + 1] == '-' -> {
        i = skipLineComment(text, i)
        builder.append(' ')
      }
      text[i] == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
        i = skipBlockComment(text, i)
        builder.append(' ')
      }
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
 * Tracks `(`/`)` only, deliberately NOT `[`/`]` — unlike [splitAtTopLevel] and
 * [findTopLevelEqualsSign], which share one depth counter across both bracket kinds. Those two
 * scan RAW, not-yet-split clause text, where a top-level-looking `,`/`=` can sit directly inside an
 * unsplit `ARRAY[...]` literal (see [splitAtTopLevel]'s own KDoc). [extractAlias] instead only ever
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
 * it once separated. `columnName` is derived by [parseOutputItemsWithAlias] from the split
 * `expression` via [parseColumnReference] — [parseOutputItemsWithAlias] is this function's only
 * caller, and does `val (expression, alias) = extractAlias(item)`, keeping the alias half (unlike
 * [parseSelectItems], which discards it) — so whatever the split changes `expression` to feeds
 * directly into that derivation. Verified against PostgreSQL
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
 * it into the CLEAN alias [foldIdentifier] expects — not the raw, unbounded remainder of the item
 * [extractAlias] used to return verbatim, which silently swallowed a trailing comment into the
 * "alias" and made it fold-compare unequal to any real name.
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
 * A bare `)` with no matching `(` before it (`depth` going negative) means [sql] is not the
 * well-formed, already-balanced text this scan assumes — BAILS immediately to `-1` (not found)
 * rather than clamping `depth` at `0` and continuing. Clamping would let the scan silently recover
 * and keep searching past the unbalanced point, which is not obviously safe either way: for a
 * search this function's own callers (`parseSelectItems`'s KDoc for one) treat a MISSING keyword
 * as the dangerous direction — e.g. a missing `FROM` making `parseSelectItems` fall through to
 * `window.substring(itemsStart)`, taking MORE text as items than it should — a clamp-and-continue
 * scan could just as easily find some LATER, wrongly-in-scope keyword instead of correctly finding
 * none at all. An unbalanced scan's assumptions are already void by that point, so returning `-1`
 * loudly, rather than guessing which recovery is safe, is the same policy [oldOrNewReturningColumns]'s
 * own caller-side cross-check exists to enforce elsewhere: an honestly-wrong "not found" a caller's
 * existing fallback already handles, over a confidently-wrong match this function cannot itself
 * tell apart from a correct one.
 *
 * @return The index of the keyword, or `-1` if not found at the top level, INCLUDING when [sql]
 *   contains an unbalanced closing parenthesis before any top-level match — see above.
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
        if (depth < 0) return -1
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
 * Matches a double-quoted PostgreSQL identifier, quotes included — `"` followed by any number of
 * (a non-`"` character) or (a doubled `""`, PostgreSQL's escape for a literal `"` inside the
 * name), followed by the closing `"`. Deliberately NOT unescaped by this pattern itself — that is
 * [unescapeQuotedIdentifier]'s job, once a caller has the matched RAW token (quotes and any
 * doubled escapes still intact) in hand.
 *
 * A separate constant from [COLUMN_REFERENCE_IDENTIFIER] (never merged into it): unlike an
 * unquoted identifier, a quoted one is legal ONLY as a `table`/`column` position in
 * [COLUMN_REFERENCE] — never as a bare function/type name (see [FUNCTION_CALL_START], which
 * still uses [COLUMN_REFERENCE_IDENTIFIER_START]/[COLUMN_REFERENCE_IDENTIFIER_CONTINUATION]
 * directly and is unaffected by this constant).
 */
private const val QUOTED_IDENTIFIER = "\"(?:[^\"]|\"\")*\""

/**
 * [QUOTED_IDENTIFIER] compiled once, for callers that need to find/match a quoted identifier
 * token starting at a KNOWN position within a larger string — [Regex.matchAt] — rather than
 * matching an entire already-isolated string the way [COLUMN_REFERENCE] does via
 * [Regex.matchEntire]. [parseAliasToken] is the one caller: it needs the escape-aware end of a
 * quoted alias token starting at a specific index, the exact same escape handling
 * [COLUMN_REFERENCE] already applies via [COLUMN_REFERENCE_IDENTIFIER_OR_QUOTED] — sharing this
 * one compiled pattern (rather than writing a second, hand-rolled quote scanner) is what keeps
 * that handling from drifting out of sync between the two call sites.
 */
private val QUOTED_IDENTIFIER_PATTERN = Regex(QUOTED_IDENTIFIER)

/**
 * Matches EITHER an unquoted [COLUMN_REFERENCE_IDENTIFIER] or a [QUOTED_IDENTIFIER] — the shape
 * [COLUMN_REFERENCE] uses for both its `table` and `column` positions, so either position can
 * independently be quoted or unquoted (`t.col`, `"t".col`, `t."col"`, `"t"."col"`).
 */
private const val COLUMN_REFERENCE_IDENTIFIER_OR_QUOTED =
  """(?:$COLUMN_REFERENCE_IDENTIFIER|$QUOTED_IDENTIFIER)"""

/**
 * Matches `table.column` or just `column`, where `table`/`column` are each either an unquoted
 * PostgreSQL identifier ([COLUMN_REFERENCE_IDENTIFIER_START] followed by zero or more
 * [COLUMN_REFERENCE_IDENTIFIER_CONTINUATION] characters — never a bare `\w+`: PostgreSQL's
 * identifier class is wider than `\w` (it admits `$` and any `>= 0x80` character — see
 * [isIdentifierChar]) but its FIRST character is narrower (`\w` itself, unlike `\w+`, doesn't
 * enforce that a digit or `$` may only appear after the first character at all)) OR a
 * double-quoted one ([QUOTED_IDENTIFIER] — `"ux"`, `"My Col"`, `"He""llo"`), matched via
 * [COLUMN_REFERENCE_IDENTIFIER_OR_QUOTED] for each position independently.
 *
 * The leading-character restriction on the UNQUOTED alternative matters in the WIDENING direction
 * specifically: without it, a digit- or `$`-led fragment that merely happens to be followed by
 * identifier-continuation characters — e.g. the item text `2€`, which PostgreSQL itself rejects
 * outright ("trailing junk after numeric literal", verified against PostgreSQL 18.4) — would
 * [Regex.matchEntire] as a whole "identifier" once the continuation class is widened to admit `€`,
 * handing back a `columnName` PostgreSQL would never actually resolve to that name.
 * [parseColumnReference] returning `null` for anything that isn't a real identifier is the safe,
 * INTENDED outcome (see [parseSelectItems]'s KDoc on why a lost name degrades safely to
 * `ResultSetMetaData` while a WRONG one does not).
 *
 * A matched group's captured text still includes its surrounding quotes (if any) — the WHOLE raw
 * token, exactly as [QUOTED_IDENTIFIER] defines it — since [Regex] group captures always span
 * whatever the sub-pattern matched; [parseColumnReference] is what turns that raw capture into
 * the LOGICAL value [SelectItem.columnName]/[SelectItem.tableName] actually store (see their own
 * KDoc), via [unescapeQuotedIdentifier].
 */
private val COLUMN_REFERENCE = Regex(
  """(?:(?<table>$COLUMN_REFERENCE_IDENTIFIER_OR_QUOTED)\.)?(?<column>$COLUMN_REFERENCE_IDENTIFIER_OR_QUOTED)""",
)

/**
 * Converts a RAW double-quoted identifier token — including its surrounding quotes, exactly as
 * [QUOTED_IDENTIFIER] matches it — into PostgreSQL's LOGICAL identifier value: the surrounding
 * quotes removed, and each doubled `""` escape collapsed to the single literal `"` it represents.
 * Verified directly against a live PostgreSQL 18: `ResultSetMetaData.getColumnName` for `SELECT
 * "He""llo" FROM (SELECT 1 AS "He""llo") s` reports `He"llo` (no quotes, escape already
 * collapsed) — exactly what this function produces from the raw token `"He""llo"`.
 *
 * [rawQuotedToken] must be the exact matched text of a [QUOTED_IDENTIFIER] — starting and ending
 * with `"`, with at least those two characters present. Passing anything else is a caller bug,
 * not a value this function attempts to handle gracefully.
 */
private fun unescapeQuotedIdentifier(rawQuotedToken: String): String =
  rawQuotedToken.substring(1, rawQuotedToken.length - 1).replace("\"\"", "\"")

/**
 * Extracts the LOGICAL content of a double-quoted token starting at the `"` at [openQuoteIndex] —
 * surrounding quotes removed and any doubled `""` escape collapsed to the single literal `"` it
 * represents, via [unescapeQuotedIdentifier] — mirroring [skipDoubleQuotedIdentifier]'s own
 * doubled-quote scan but additionally tracking whether a genuine, non-doubled closing `"` was ever
 * found before [text] ends.
 *
 * That tracking matters for an UNTERMINATED quoted identifier (no real closing `"` before [text]
 * ends): [skipDoubleQuotedIdentifier] returns `text.length` in that case, exactly as it does for a
 * properly closed identifier ending at the last character of [text] — the two are indistinguishable
 * from its return value alone. Naively assuming the LAST character of the scanned range is always a
 * real closing quote (`text.substring(openQuoteIndex + 1, afterQuote - 1)`) is correct for the
 * terminated case but drops the genuinely-real final content character of an unterminated one. This
 * function resolves the ambiguity during the SAME scan that decides where the token ends, rather
 * than reconstructing an answer afterward from indices alone (which, for a run of doubled `""`
 * pairs immediately preceding the cutoff, cannot reliably tell "properly closed" apart from "the
 * last doubled pair's second quote happened to land on the final character" after the fact).
 *
 * @return the decoded content and the index just past the token — the same contract
 *   [skipDoubleQuotedIdentifier] has for its own return value, so a caller can resume scanning [text]
 *   from there exactly as it would after calling that function directly.
 */
private fun extractQuotedIdentifierContent(text: String, openQuoteIndex: Int): Pair<String, Int> {
  var i = openQuoteIndex + 1
  var closedWithRealQuote = false
  while (i < text.length) {
    if (text[i] == '"') {
      i++
      if (i < text.length && text[i] == '"') {
        i++ // doubled "" — an escaped literal quote, not the terminator
      } else {
        closedWithRealQuote = true
        break
      }
    } else {
      i++
    }
  }
  val unterminatedSuffix = if (closedWithRealQuote) "" else "\""
  val rawToken = text.substring(openQuoteIndex, i) + unterminatedSuffix
  return unescapeQuotedIdentifier(rawToken) to i
}

/**
 * A parsed CTE definition from a `WITH` clause.
 *
 * @property name The CTE name, with surrounding double quotes stripped (if any). Safe for DISPLAY,
 *   and for a quote-INSENSITIVE comparison where losing the quoted/unquoted distinction is
 *   genuinely harmless. NOT safe for constructing SQL to send to PostgreSQL, and NOT safe for any
 *   comparison that resolves what the name actually ADDRESSES — quoting changes case-folding
 *   (`"MyCte"` is distinct from `MyCte`, which folds to `mycte`), so both building a `FROM <name>`
 *   reference from this stripped form, and comparing it against another identifier to decide
 *   whether they denote the same relation, can silently pick the wrong (or a nonexistent) one —
 *   see [resolveCteOutputExpression]'s own use of [rawName] instead, for exactly this reason. Use
 *   [rawName] for both constructing SQL and any identifier-resolution comparison.
 * @property rawName The CTE name exactly as written in the original SQL, including surrounding
 *   double quotes if the user quoted it. Safe to splice verbatim into a `FROM <rawName>` probe.
 * @property bodyOpenParenthesis Index of `(` that opens the CTE body in the original SQL.
 * @property bodyCloseParenthesis Index of `)` that closes the CTE body.
 * @property hasColumnList Whether the CTE was declared with an explicit column list
 *   (`name(col1, col2) AS (...)`). The list renames/repositions the body's own output names, and
 *   this file carries no information about which body expression backs any particular listed
 *   name, so [resolveCteOutputExpression] refuses to resolve an expression through a CTE for
 *   which this is `true` — the presence of a list is all that matters, not its contents.
 */
internal data class CteDefinition(
  val name: String,
  val rawName: String,
  val bodyOpenParenthesis: Int,
  val bodyCloseParenthesis: Int,
  val hasColumnList: Boolean = false,
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
  var hasColumnList = false
  if (position < sql.length && sql[position] == '(') {
    hasColumnList = true
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

  return CteDefinition(name, rawName, bodyOpen, bodyClose, hasColumnList) to (bodyClose + 1)
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
 * `queryColumnNullability`'s `analyzeUnconvertibleDml` fallback, which DOES read `isNullable` —
 * OR'd with the same [forcedNullabilityPredicate] safety net the CTE-body stub path applies (issue
 * #207). `analyzeUnconvertibleDml` itself CAN return an empty list (if the probe's own `metaData`
 * is `null`, or the probe throws `SQLException`) — but that path is unreachable via the current
 * caller, `JdbcAnalyzer`: `buildResultColumns` returns `emptyList()` BEFORE ever calling
 * `queryColumnNullability` whenever the statement being analyzed has no `ResultSetMetaData` of its
 * own, so `queryColumnNullability` — and therefore `analyzeUnconvertibleDml` — is only reached for
 * a statement JDBC has already shown DOES have result-column metadata.
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
 * Checks whether [body], after skipping any leading nested `WITH` clause (see
 * [stripLeadingNestedWithClause]), begins with `UPDATE`, `DELETE`, or `MERGE` — the statement
 * kinds [dmlSourceClauseRegion] recognizes.
 *
 * Used by [forcedNullabilityPredicate]'s scoped variant to distinguish two cases
 * [dmlSourceClauseRegion] alone cannot tell apart, since it returns `null` for both: (1) [body] IS
 * one of these statement kinds but has no `FROM`/`USING`/`ON` clause to scope to (e.g. a plain
 * `UPDATE ... SET ... WHERE ...` with no `FROM`) — there the join arm should be forced OFF, since
 * there is no source clause whose join could null-extend `RETURNING` — versus (2) [body] ISN'T one
 * of these statement kinds at all (e.g. a plain `SELECT` CTE body reached via a degraded, non-DML
 * path), where the scoped predicate falls back to today's whole-body scan instead — see
 * [forcedNullabilityPredicate]'s KDoc on its `scopeNullExtendingScanToDmlSourceClause` parameter
 * for why that fallback is deliberate.
 */
internal fun isUpdateDeleteOrMergeStatement(body: String): Boolean {
  val dml = stripLeadingNestedWithClause(body)
  val trimmedStart = skipWhitespaceAndComments(dml, 0)
  return listOf("UPDATE", "DELETE", "MERGE").any { keyword ->
    dml.regionMatches(trimmedStart, keyword, 0, keyword.length, ignoreCase = true) &&
      (trimmedStart + keyword.length >= dml.length || !isIdentifierChar(dml[trimmedStart + keyword.length]))
  }
}

/**
 * Returns the region of a top-level DML statement's own text whose join structure can
 * null-extend its `RETURNING` list: an `UPDATE`'s `FROM` clause, a `DELETE`'s `USING` clause, or a
 * `MERGE`'s `USING ... ON` source clause — or `null` when [sql] is not a top-level
 * `UPDATE`/`DELETE`/`MERGE` (see [isUpdateDeleteOrMergeStatement]), or has no such clause at all
 * (a plain `UPDATE`/`DELETE` with no `FROM`/`USING`, which [convertDmlToSelect] also refuses to
 * convert for the identical reason: no join structure exists to model).
 *
 * Used to SCOPE [hasOuterJoin]/[hasGroupingSetConstruct]'s otherwise whole-statement text scan
 * (see [forcedNullabilityPredicate]'s `scopeNullExtendingScanToDmlSourceClause` parameter) to only
 * the clause whose joins can actually reach `RETURNING`. A `LEFT JOIN` inside a `WHERE ... IN
 * (SELECT ... LEFT JOIN ...)` subquery, for instance, cannot null-extend anything in `RETURNING`
 * — it only narrows which rows the DML's own target touches — but a flat whole-body scan cannot
 * tell that apart from a `LEFT JOIN` that genuinely sits in the `FROM`/`USING` clause.
 *
 * Mirrors the same top-level keyword walks [convertDmlToSelect] and its private
 * `convertMergeToSelect` helper already perform for the identical statement kinds (reusing
 * [findTopLevelKeyword] rather than a new scanning primitive), but returns the clause's own
 * character RANGE instead of a converted `SELECT` string — this function's caller needs to scan
 * that exact substring for a null-extending construct, not prepare it as SQL.
 *
 * A leading `WITH` clause (see [parseCteClause]) is skipped first, so `WITH x AS (...) UPDATE ...`
 * is recognized as an `UPDATE`, not rejected for not starting with the `UPDATE` keyword — the
 * region's returned indices are still relative to [sql] as a whole, not to any stripped prefix.
 *
 * @return The region's character range in [sql] (using [sql]'s own indices), or `null` if [sql]
 *   is not a top-level `UPDATE`/`DELETE`/`MERGE`, or has no `FROM`/`USING` (or, for `MERGE`, no
 *   `ON`) clause to scope to.
 */
internal fun dmlSourceClauseRegion(sql: String): IntRange? {
  val mainQueryStart = parseCteClause(sql)?.mainQueryStart ?: 0
  val trimmedStart = skipWhitespaceAndComments(sql, mainQueryStart)

  if (sql.regionMatches(trimmedStart, "UPDATE", 0, 6, ignoreCase = true)) {
    val setIndex = findTopLevelKeyword(sql, "SET", trimmedStart + 6)
    if (setIndex < 0) return null
    val fromIndex = findTopLevelKeyword(sql, "FROM", setIndex)
    if (fromIndex < 0) return null // No FROM clause → no join structure
    val regionStart = fromIndex + 4
    val regionEnd = endOfDmlSourceClause(sql, regionStart)
    if (!indicesAreOrdered(regionStart, regionEnd)) return null
    return regionStart until regionEnd
  }

  if (sql.regionMatches(trimmedStart, "DELETE", 0, 6, ignoreCase = true)) {
    val fromIndex = findTopLevelKeyword(sql, "FROM", trimmedStart + 6)
    if (fromIndex < 0) return null
    val usingIndex = findTopLevelKeyword(sql, "USING", fromIndex)
    if (usingIndex < 0) return null // No USING clause → no join structure
    val regionStart = usingIndex + 5
    val regionEnd = endOfDmlSourceClause(sql, regionStart)
    if (!indicesAreOrdered(regionStart, regionEnd)) return null
    return regionStart until regionEnd
  }

  if (sql.regionMatches(trimmedStart, "MERGE", 0, 5, ignoreCase = true)) {
    val intoIndex = findTopLevelKeyword(sql, "INTO", trimmedStart)
    if (intoIndex < 0) return null
    val usingIndex = findTopLevelKeyword(sql, "USING", intoIndex)
    if (usingIndex < 0) return null
    val onIndex = findTopLevelKeyword(sql, "ON", usingIndex)
    if (onIndex < 0) return null
    val regionStart = usingIndex + 5
    if (!indicesAreOrdered(regionStart, onIndex)) return null
    return regionStart until onIndex
  }

  return null
}

/**
 * Finds where a DML source clause (an `UPDATE`'s `FROM`, or a `DELETE`'s `USING`) ends: the
 * earlier of a following top-level `WHERE` or `RETURNING` keyword found from [searchFrom] onward
 * — both can only appear AFTER the source clause, per the same statement grammar
 * [convertDmlToSelect] relies on for its own keyword ordering — or [sql]'s own length if neither
 * appears (a `FROM`/`USING` clause with no trailing `WHERE`/`RETURNING` at all).
 */
private fun endOfDmlSourceClause(sql: String, searchFrom: Int): Int {
  val whereIndex = findTopLevelKeyword(sql, "WHERE", searchFrom)
  val returningIndex = findTopLevelKeyword(sql, "RETURNING", searchFrom)
  return listOf(whereIndex, returningIndex).filter { it >= 0 }.minOrNull() ?: sql.length
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
 * Extracts the target relation reference from a top-level data-modifying statement — the text
 * naming the table being inserted into/updated/deleted from/merged into, INCLUDING any `AS alias`
 * or bare alias that follows it, verbatim (so a later `RETURNING` item qualified through that
 * alias, e.g. `u.note` after `UPDATE t AS u`, still resolves once spliced into a probe `FROM`
 * clause).
 *
 * Used by `PgCatalogLoader`'s `columnNullableUnknown` probe (issue #226): a probe SQL of the shape
 * `SELECT <returning items> FROM <target>` needs the ORIGINAL relation reference — not merely the
 * bare table name — since `RETURNING` may reference the DML statement's own alias rather than the
 * table name directly.
 *
 * [stripLeadingNestedWithClause] is applied first, so a body carrying its own leading nested
 * `WITH` clause (`WITH helper AS (...) INSERT INTO t ...`) is recognized by its actual DML
 * keyword, not rejected outright for not starting with one — the same precedent [isInsertBody]
 * and [isUpdateDeleteOrMergeStatement] already set for the identical shape.
 *
 * The termination rule differs by statement kind, mirroring the keyword walks [convertDmlToSelect]
 * already performs over the same grammar:
 * - `INSERT INTO <target>`: ends at the first top-level `(` — an explicit column list
 *   (`INSERT INTO t (a, b) VALUES (...)` must not swallow `(a, b)` into the target) — or the
 *   first top-level keyword among `VALUES`, `SELECT`, `DEFAULT`, `OVERRIDING`, `ON`, `RETURNING`,
 *   `TABLE`, `WITH`, whichever comes first.
 * - `UPDATE <target> SET`: ends at the top-level `SET`.
 * - `DELETE FROM <target>`: ends at the first top-level `USING`, `WHERE`, or `RETURNING`, or the
 *   end of the statement if none of those appear (a bare `DELETE FROM t`).
 * - `MERGE INTO <target> USING`: ends at the top-level `USING`.
 * - Anything else (a plain `SELECT`, or any statement kind not recognized above): `null`.
 *
 * A trailing `;` and any trailing whitespace left over from the slice is stripped — the same
 * `.trim().trimEnd(';')` shortcut [parseSelectItems]/[buildSelectFromDml]/[convertMergeToSelect]
 * already take for their own trailing clause text.
 *
 * @return The target relation reference, verbatim, or `null` if [sql] is not one of the four
 *   recognized statement kinds, or its target parses out empty/blank.
 */
internal fun dmlTargetRelationReference(sql: String): String? {
  val dml = stripLeadingNestedWithClause(sql)
  val trimmedStart = skipWhitespaceAndComments(dml, 0)

  val target: String = when {
    dml.regionMatches(trimmedStart, "INSERT", 0, 6, ignoreCase = true) -> {
      val intoIndex = findTopLevelKeyword(dml, "INTO", trimmedStart)
      if (intoIndex < 0) return null
      val start = intoIndex + 4
      val stopKeywordIndex = INSERT_TARGET_STOP_KEYWORDS
        .mapNotNull { keyword -> findTopLevelKeyword(dml, keyword, start).takeIf { it >= 0 } }
        .minOrNull()
      val stopParenIndex = findFirstTopLevelOpenParenthesis(dml, start).takeIf { it >= 0 }
      val end = listOfNotNull(stopKeywordIndex, stopParenIndex).minOrNull() ?: dml.length
      dml.substring(start, end)
    }

    dml.regionMatches(trimmedStart, "UPDATE", 0, 6, ignoreCase = true) -> {
      val start = trimmedStart + 6
      val setIndex = findTopLevelKeyword(dml, "SET", start)
      if (setIndex < 0) return null
      dml.substring(start, setIndex)
    }

    dml.regionMatches(trimmedStart, "DELETE", 0, 6, ignoreCase = true) -> {
      val fromIndex = findTopLevelKeyword(dml, "FROM", trimmedStart)
      if (fromIndex < 0) return null
      val start = fromIndex + 4
      val end = listOf("USING", "WHERE", "RETURNING")
        .mapNotNull { keyword -> findTopLevelKeyword(dml, keyword, start).takeIf { it >= 0 } }
        .minOrNull() ?: dml.length
      dml.substring(start, end)
    }

    dml.regionMatches(trimmedStart, "MERGE", 0, 5, ignoreCase = true) -> {
      val intoIndex = findTopLevelKeyword(dml, "INTO", trimmedStart)
      if (intoIndex < 0) return null
      val start = intoIndex + 4
      val usingIndex = findTopLevelKeyword(dml, "USING", start)
      if (usingIndex < 0) return null
      dml.substring(start, usingIndex)
    }

    else -> return null
  }

  val cleaned = target.trim().trimEnd(';').trim()
  return cleaned.ifBlank { null }
}

/** The keywords, other than an explicit column list's `(`, that can follow an `INSERT`'s target. */
private val INSERT_TARGET_STOP_KEYWORDS =
  listOf("VALUES", "SELECT", "DEFAULT", "OVERRIDING", "ON", "RETURNING", "TABLE", "WITH")

/**
 * Derives the correlation name a probe's derived table must be given so a `RETURNING` item
 * qualified through the `UPDATE` statement's own target — e.g. `u.note` after `UPDATE t AS u` —
 * still resolves once that item is spliced, verbatim, into a wrapping `FROM (...) AS <alias>`
 * clause (see `PgCatalogLoader.buildUpdateSetAwareFromClause`, issue #228).
 *
 * Parses [target] (the exact text [dmlTargetRelationReference] returned) against the grammar
 * `[ ONLY ] name [ . name ]* [ * ] [ [ AS ] alias ]`:
 * - An explicit alias (`AS u`) or bare alias (`u`) wins outright — that is the name `RETURNING`
 *   items in the real statement would have used to qualify a column.
 * - Otherwise, the target has no alias of its own, so the correlation name PostgreSQL itself uses
 *   for unqualified `RETURNING` items is the bare table name — the LAST dot-separated segment of
 *   a possibly schema-qualified name (`public.t` → `t`).
 *
 * Every returned string is a VERBATIM substring of [target] — quotes and all, never unquoted or
 * case-folded — so splicing it back as `AS <result>` resolves exactly the way the original
 * `RETURNING` clause's own qualifiers would, including for a quoted, case-sensitive alias.
 *
 * @return `null` — the caller's signal to fall back to the plain, unwrapped `FROM <target>` this
 *   function's caller otherwise already builds — whenever [target] doesn't match the grammar
 *   above closely enough to be confident: an unterminated quote, trailing text after a
 *   recognized alias that isn't just whitespace/comments, or anything else this parser doesn't
 *   recognize. Guessing wrong here would silently misattribute a `RETURNING` qualifier to the
 *   wrong relation, so any doubt bails rather than guesses.
 */
internal fun deriveDmlTargetAlias(target: String): String? {
  val nameSegments = parseDmlTargetNameSegments(target) ?: return null
  var position = nameSegments.endPosition

  position = skipWhitespaceAndComments(target, position)
  if (position < target.length && target[position] == '*') {
    position = skipWhitespaceAndComments(target, position + 1)
  }

  if (position >= target.length) return nameSegments.lastSegment // no alias — fall back to the table name itself

  val aliasStart = skipWhitespaceAndComments(target, skipOptionalKeyword(target, position, "AS"))
  if (aliasStart >= target.length) return null // "AS" with nothing after it — malformed, bail

  val aliasEnd = when {
    target[aliasStart] == '"' -> {
      var i = aliasStart + 1
      while (i < target.length && target[i] != '"') i++
      if (i >= target.length) return null // unterminated quoted alias
      i + 1
    }
    isIdentifierStartChar(target[aliasStart]) -> {
      var i = aliasStart
      while (i < target.length && isIdentifierChar(target[i])) i++
      i
    }
    else -> return null
  }

  // Anything left over that isn't whitespace/comments means target wasn't the simple shape this
  // parser understands after all (e.g. a TABLESAMPLE clause) — bail rather than guess.
  if (skipWhitespaceAndComments(target, aliasEnd) != target.length) return null

  return target.substring(aliasStart, aliasEnd)
}

/**
 * The result of parsing the `[ ONLY ] name [ . name ]*` prefix of a [dmlTargetRelationReference]
 * target — shared by [deriveDmlTargetAlias] (which needs only [lastSegment], to fall back on when
 * no alias follows) and [dmlTargetRelationName] (which needs the full [qualifiedName]).
 *
 * @property qualifiedName The `[ ONLY ]`-stripped, dot-joined name text, VERBATIM (quotes and all)
 *   — everything from just after an optional leading `ONLY` through the end of the last
 *   dot-separated segment.
 * @property lastSegment The last dot-separated segment alone, VERBATIM — the bare table name once
 *   any schema qualification is stripped (`public.t` → `t`).
 * @property endPosition The index in the original target text just past the parsed name — where a
 *   caller resumes looking for a `*` or an alias.
 */
private data class DmlTargetNameSegments(val qualifiedName: String, val lastSegment: String, val endPosition: Int)

/**
 * Parses the `[ ONLY ] name [ . name ]*` prefix of [target] (the exact text
 * [dmlTargetRelationReference] returned) — the shared first half of the grammar both
 * [deriveDmlTargetAlias] and [dmlTargetRelationName] need to recognize before going their separate
 * ways (one continuing on to an optional `*` and alias, the other stopping here).
 *
 * @return `null` if [target] has no identifier where a name segment was expected, or a quoted
 *   segment is left unterminated — the same "any doubt bails rather than guesses" rule
 *   [deriveDmlTargetAlias] documents for itself.
 */
private fun parseDmlTargetNameSegments(target: String): DmlTargetNameSegments? {
  val nameStart = skipOptionalKeyword(target, skipWhitespaceAndComments(target, 0), "ONLY")
  var position = nameStart

  var lastSegment: String
  while (true) {
    val segmentStart = position
    position = when {
      position < target.length && target[position] == '"' -> {
        var i = position + 1
        while (i < target.length && target[i] != '"') i++
        if (i >= target.length) return null // unterminated quoted identifier
        i + 1
      }
      position < target.length && isIdentifierStartChar(target[position]) -> {
        var i = position + 1
        while (i < target.length && isIdentifierChar(target[i])) i++
        i
      }
      else -> return null // no identifier where a table name segment was expected
    }
    lastSegment = target.substring(segmentStart, position)
    if (position < target.length && target[position] == '.') {
      position++
    } else {
      break
    }
  }

  return DmlTargetNameSegments(target.substring(nameStart, position), lastSegment, position)
}

/**
 * Extracts the bare, possibly schema-qualified relation name from [target] (the exact text
 * [dmlTargetRelationReference] returned) — with any leading `ONLY`, trailing `*` (the
 * "include descendant tables" wildcard, meaningless to `to_regclass`), and alias all stripped —
 * suitable to pass, VERBATIM (quotes and all, never unquoted or case-folded), as the single text
 * argument to PostgreSQL's `to_regclass(text)`: that function applies the exact same identifier
 * folding rules PostgreSQL's own parser would to resolve the same name, so a quoted, case-sensitive
 * name resolves correctly and an unquoted one still folds to lowercase as it would anywhere else.
 *
 * Used by `PgCatalogLoader` (issue #228) to resolve the `UPDATE`/`DELETE` target relation to an
 * OID before deciding whether a `SET`-assignment-aware `RETURNING` probe can be trusted for it —
 * see its own KDoc for what that decision covers.
 *
 * @return `null` — the caller's signal to bail rather than guess, same as [deriveDmlTargetAlias] —
 *   whenever [target] doesn't match the `[ ONLY ] name [ . name ]*` prefix of the grammar
 *   [deriveDmlTargetAlias] documents closely enough to be confident: an unterminated quote, or no
 *   identifier at all where a name segment was expected.
 */
internal fun dmlTargetRelationName(target: String): String? = parseDmlTargetNameSegments(target)?.qualifiedName

/**
 * A single `UPDATE ... SET` assignment, parsed from the top-level-comma-separated SET list.
 *
 * @property columnNames The column(s) assigned. A single entry for a plain `column = expression`
 *   assignment; two or more for the row form `(a, b) = (...)`, where [isRowForm] is `true`.
 *   Each name is normalized via the same folding rule PostgreSQL itself applies when storing a
 *   column name — lowercased if the source text was an unquoted identifier, left verbatim (minus
 *   the surrounding quotes) if it was quoted — so it compares equal, by plain `String.equals`, to
 *   `ResultSetMetaData.getColumnName`'s output for the same column.
 * @property expression The right-hand side text, verbatim (trimmed of surrounding whitespace),
 *   including the literal `DEFAULT` keyword when that's what was written. Meaningless — never
 *   read — when [isRowForm] is `true`: a row-form assignment's single right-hand side expression
 *   cannot be safely attributed to any one of its several target columns.
 * @property isRowForm `true` for the row form `SET (a, b) = (...)`; `false` for a plain
 *   `SET column = expression`.
 */
internal data class SqlSetAssignment(val columnNames: List<String>, val expression: String, val isRowForm: Boolean)

/**
 * Parses the top-level-comma-separated list of an `UPDATE ... SET` clause in [dml] — an `UPDATE`
 * statement (with any leading nested `WITH` clause already stripped by the caller, matching
 * [dmlTargetRelationReference]'s own precondition).
 *
 * The list runs from just after the top-level `SET` keyword to whichever of a top-level `WHERE`,
 * `RETURNING`, or `FROM` keyword comes first, or the end of [dml] if none do. `FROM` is included
 * defensively even though a genuine `UPDATE ... FROM` never reaches this parser in practice — its
 * join structure is handled by `PgCatalogLoader.convertDmlToSelect` long before
 * `PgCatalogLoader.analyzeUnconvertibleDml` (and this parser) are ever reached — so this is a
 * safety net against a future or unrecognized shape, not a designed-for input.
 *
 * @return One [SqlSetAssignment] per top-level-comma-separated item, in order, or `null` if [dml]
 *   is not a plain `UPDATE`, has no top-level `SET` keyword, the SET list is empty, or any
 *   individual item fails to parse as either a plain `column = expression` or a row-form
 *   `(col, ...) = (...)` assignment — see [SqlSetAssignment]'s KDoc for what each shape means to
 *   the caller. A wholesale `null` (rather than silently dropping the one bad item) is
 *   deliberate: `PgCatalogLoader.buildUpdateSetAwareFromClause` cannot trust that skipping one
 *   bad item still parsed every OTHER item's boundaries correctly, so it falls all the way back
 *   to its own caller's plain, unwrapped `FROM <target>` instead of risking a half-correct
 *   substitution.
 */
internal fun parseSetAssignments(dml: String): List<SqlSetAssignment>? {
  val trimmedStart = skipWhitespaceAndComments(dml, 0)
  if (skipOptionalKeyword(dml, trimmedStart, "UPDATE") == trimmedStart) return null
  val setIndex = findTopLevelKeyword(dml, "SET", trimmedStart)
  if (setIndex < 0) return null

  val listStart = setIndex + 3
  val listEnd = listOf("WHERE", "RETURNING", "FROM")
    .mapNotNull { keyword -> findTopLevelKeyword(dml, keyword, listStart).takeIf { it >= 0 } }
    .minOrNull() ?: dml.length
  if (listEnd < listStart) return null

  val setListText = dml.substring(listStart, listEnd).trim().trimEnd(';')
  if (setListText.isEmpty()) return null

  return splitAtTopLevel(setListText, ',').map { rawItem -> parseSingleSetAssignment(rawItem.trim()) ?: return null }
}

/**
 * Parses one `UPDATE ... SET` list item — either `column = expression`/`column = DEFAULT`, or the
 * row form `(col, ...) = (...)` — into a [SqlSetAssignment].
 *
 * @return `null` if [item] has no top-level `=` sign, its left-hand side isn't recognizable as
 *   either a single column identifier or a fully-parenthesized column list, or (for the row form)
 *   any inner column name fails to parse as a plain identifier.
 */
private fun parseSingleSetAssignment(item: String): SqlSetAssignment? {
  val equalsIndex = findTopLevelEqualsSign(item)
  if (equalsIndex < 0) return null
  val lhs = item.substring(0, equalsIndex).trim()
  val rhs = item.substring(equalsIndex + 1).trim()
  if (lhs.isEmpty() || rhs.isEmpty()) return null

  if (lhs.startsWith("(")) {
    val closeParenthesis = findMatchingCloseParenthesis(lhs, 0)
    if (closeParenthesis != lhs.length - 1) return null
    val innerColumns = splitAtTopLevel(lhs.substring(1, closeParenthesis), ',').map { rawColumn ->
      normalizeAssignmentColumnName(rawColumn.trim()) ?: return null
    }
    if (innerColumns.isEmpty()) return null
    return SqlSetAssignment(columnNames = innerColumns, expression = rhs, isRowForm = true)
  }

  val columnName = normalizeAssignmentColumnName(lhs) ?: return null
  return SqlSetAssignment(columnNames = listOf(columnName), expression = rhs, isRowForm = false)
}

/**
 * Normalizes a `SET`-list left-hand-side column reference to the same form PostgreSQL itself uses
 * when storing (and later reporting, via `ResultSetMetaData.getColumnName`) that column's name:
 * an unquoted identifier folds to lowercase; a quoted identifier keeps its case, with the
 * surrounding quotes (and any escaped `""`) removed.
 *
 * @return `null` if [text] is not, in its entirety, a single quoted or unquoted identifier —
 *   e.g. it's empty, or an unquoted identifier followed by trailing junk.
 */
private fun normalizeAssignmentColumnName(text: String): String? {
  if (text.isEmpty()) return null
  if (text[0] == '"') {
    if (!text.endsWith("\"") || text.length < 2) return null
    return text.substring(1, text.length - 1).replace("\"\"", "\"")
  }
  if (!isIdentifierStartChar(text[0])) return null
  for (index in 1 until text.length) {
    if (!isIdentifierChar(text[index])) return null
  }
  return text.lowercase()
}

/**
 * Finds the first top-level (depth-0, lexically aware) `=` sign in [text] — the separator between
 * a `SET`-list item's left-hand side and its right-hand side. Safe to take the FIRST such sign
 * without further disambiguation from a comparison operator that also contains `=`
 * (`<=`, `>=`, `<>`, `!=`) or PL/pgSQL's `=>` named-argument syntax: the left-hand side of a
 * `SET`-list item is always a bare column reference or a parenthesized column list — never an
 * expression that could itself contain `=` — so the first top-level `=` this scan finds can only
 * ever be the genuine assignment separator.
 *
 * A bare closing `)`/`]` with no matching opener before it (`depth` going negative) means [text]
 * is not the well-formed, already-balanced text this scan assumes — bails immediately to `-1`
 * (not found) rather than clamping `depth` at `0` and continuing; see [findTopLevelKeyword]'s own
 * KDoc for why bailing, not clamping, is the safe direction once a scan's balancing assumption is
 * already broken.
 *
 * @return The index of the `=` sign, or `-1` if [text] has none at the top level, INCLUDING when
 *   [text] contains an unbalanced closing bracket before any top-level `=` — see above.
 */
private fun findTopLevelEqualsSign(text: String): Int {
  var depth = 0
  var i = 0
  while (i < text.length) {
    val afterToken = skipLexicalToken(text, i)
    if (afterToken != i) {
      i = afterToken
      continue
    }
    when (text[i]) {
      '(', '[' -> depth++
      ')', ']' -> {
        depth--
        if (depth < 0) return -1
      }
      '=' -> if (depth == 0) return i
    }
    i++
  }
  return -1
}

/**
 * Checks whether [text] contains a `?` parameter placeholder anywhere at the lexical level —
 * i.e. outside a string literal, quoted identifier, dollar-quoted string, or comment, via the
 * same [skipLexicalToken] every other scanner in this file uses to make that distinction.
 *
 * Used by `PgCatalogLoader.buildUpdateSetAwareFromClause` (issue #228) to detect a `SET`
 * assignment's right-hand side still carrying a genuine parameter placeholder in the ORIGINAL,
 * pre-sentinel-substitution SQL — see that function's KDoc for why the pre-sentinel text,
 * specifically, is what must be checked here.
 */
internal fun containsLexicalPlaceholder(text: String): Boolean {
  var i = 0
  while (i < text.length) {
    val afterToken = skipLexicalToken(text, i)
    if (afterToken != i) {
      i = afterToken
      continue
    }
    if (text[i] == '?') return true
    i++
  }
  return false
}

/**
 * Finds the index of the first top-level `(` at or after [start] — depth-0, lexically aware via
 * [skipLexicalToken] the same way [findTopLevelKeyword] is, so a `(` inside a string literal,
 * quoted identifier, dollar-quoted string, or comment is never mistaken for a real one. Used by
 * [dmlTargetRelationReference] to find an `INSERT`'s explicit column list, the one termination
 * candidate there that is a punctuation character rather than a keyword.
 *
 * @return The index of the first top-level `(` at or after [start], or `-1` if there is none.
 */
private fun findFirstTopLevelOpenParenthesis(sql: String, start: Int): Int {
  var i = start
  while (i < sql.length) {
    val afterToken = skipLexicalToken(sql, i)
    if (afterToken != i) {
      i = afterToken
      continue
    }
    if (sql[i] == '(') return i
    i++
  }
  return -1
}

/**
 * Returns `true` if [indices], read left to right, are non-decreasing.
 *
 * `convertDmlToSelect`/`convertMergeToSelect`/[dmlSourceClauseRegion] locate several keywords
 * independently — each
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
 * That "false positive only" claim depends on the quoted content itself being extracted
 * CORRECTLY, via [extractQuotedIdentifierContent] — a doubled `""` escape inside the quoted text
 * (a CTE literally named `a"b`, written `"a""b"`) must be collapsed to the single `"` it
 * represents before comparison, or [names] containing the real name `a"b` would never match the
 * raw, still-escaped `a""b` this scan would otherwise extract — a FALSE NEGATIVE, not merely lost
 * precision, and exactly the kind of miss this whole function exists to avoid (a sibling CTE
 * reference going undetected leaves a column that should be forced nullable as NOT NULL instead).
 * [extractQuotedIdentifierContent] also resolves an UNTERMINATED quote (no closing `"` before
 * [body] ends) correctly rather than dropping its last real character — see that function's own
 * KDoc for why the two cases cannot be told apart from [skipDoubleQuotedIdentifier]'s return value
 * alone.
 *
 * A PostgreSQL Unicode-escape double-quoted identifier (`U&"..."`, e.g. `U&"d\0061ta"` for
 * `data` — verified live) is never decoded and compared: its raw, still-escaped quoted content
 * would not match a plain name even when the escape decodes to it, a false negative in the
 * dangerous direction. Rather than decode the escape, [isUnicodeEscapeIdentifierStart] bails
 * conservatively — this function returns `true` the moment it sees a `U&"` token anywhere in
 * [body], regardless of [names] — since a false positive here only costs precision, exactly this
 * function's own intended tradeoff, while a false negative would not.
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
      val (quotedContent, afterQuote) = extractQuotedIdentifierContent(body, i)
      if (names.any { name -> name.equals(quotedContent, ignoreCase = true) }) return true
      i = afterQuote
      continue
    }
    if (isUnicodeEscapeIdentifierStart(body, i)) return true
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
 * True if [body] contains a PostgreSQL Unicode-escape double-quoted identifier (`U&"..."`)
 * starting at [position] — a case-insensitive `U`, immediately (no whitespace) followed by `&`,
 * immediately followed by the opening `"`, at a word boundary (not itself part of a longer
 * identifier, e.g. the `u` ending `menu`). Verified live: `U & "x"` (with a space before `&`) is a
 * syntax error, so requiring the three characters adjacent is not an over-narrow guess.
 *
 * See [referencesAnyName]'s KDoc for why this function bails conservatively on this form entirely,
 * rather than decoding it.
 */
private fun isUnicodeEscapeIdentifierStart(body: String, position: Int): Boolean = position + 2 < body.length &&
  (body[position] == 'u' || body[position] == 'U') &&
  body[position + 1] == '&' &&
  body[position + 2] == '"' &&
  (position == 0 || !isIdentifierChar(body[position - 1]))

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
 * dropped the alias from [OldOrNewReturningAnalysis.forcedColumns].
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
 * Builds the FORCED NULLABILITY predicate for a data-modifying [body] whose own result-column
 * nullability is being probed from `ResultSetMetaData` (base-table `attnotnull`) rather than
 * derived from the join-aware node-tree analyzer — the safety net that covers every danger sign
 * this file can detect regardless of WHY the join-aware path was unavailable: a detectable outer
 * join in an `UPDATE`/`DELETE`/`MERGE` body (never `INSERT`, whose `RETURNING` cannot be
 * null-extended by anything in its own source — see [isInsertBody]'s KDoc); a `RETURNING` that
 * reads `OLD.`/`NEW.`, which applies regardless of statement kind, `INSERT` included (e.g.
 * `INSERT ... ON CONFLICT DO UPDATE ... RETURNING OLD.col`, where `OLD` is `NULL` exactly when
 * there was no conflict); a reference, by name, to a sibling that is itself dangerous (a sibling
 * CTE for the CTE-body caller, or a dangerous CTE the OUTER statement references for the
 * top-level-DML caller); or an `OLD`/`NEW` item-to-column mapping this file cannot confirm is
 * reliable.
 *
 * Shared by both callers that need this exact logic: the CTE-body stub path (Phase 1 of
 * `PgCatalogLoader.transformForViewCreation`) and the top-level, no-join-structure DML fallback
 * (`PgCatalogLoader`'s replacement for its former `buildAllNonNullable`) — before this was
 * extracted, only the CTE-body path applied any of this, leaving the top-level fallback to assert
 * every column NOT NULL unconditionally (issue #207).
 *
 * @param body A SQL statement (a CTE body, or a top-level DML statement's own main-query text,
 *   excluding any leading `WITH` clause) with no surrounding parentheses.
 * @param dangerousSiblingNames Names already known to be dangerous that [body] might reference by
 *   name — empty when there is nothing else in scope to be dangerous (e.g. a top-level statement
 *   with no `WITH` clause).
 * @param scopeNullExtendingScanToDmlSourceClause `false` (the CTE-body caller's default, and its
 *   ~90 existing tests' baseline — left untouched) scans [body]'s ENTIRE text for a null-extending
 *   construct, exactly as [hasNullExtendingConstruct] does on its own. `true` (the top-level DML
 *   fallback's choice, `PgCatalogLoader.analyzeUnconvertibleDml`) instead scopes that scan to
 *   [dmlSourceClauseRegion] — the ONLY region of a top-level `UPDATE`/`DELETE`/`MERGE` whose joins
 *   can null-extend its own `RETURNING` list — when [body] IS one of those statement kinds
 *   ([isUpdateDeleteOrMergeStatement]). A `LEFT JOIN` sitting inside a `WHERE ... IN (SELECT ...)`
 *   subquery, for instance, only narrows which rows the DML touches; it cannot null-extend
 *   anything `RETURNING` reads, so scanning it anyway (as the unscoped whole-body scan did)
 *   fabricates nullable for a column that is genuinely NOT NULL — confirmed against real
 *   PostgreSQL for `UPDATE t SET name=? WHERE t.id IN (SELECT a.id FROM a LEFT JOIN b ON b.id=a.id)
 *   RETURNING t.id, t.name`, where both columns are NOT NULL (`t.id` is even the primary key).
 *   `hasWhenNotMatchedBySourceClause` is NOT scoped by this parameter even when it is `true`: a
 *   `MERGE`'s `WHEN` clauses live OUTSIDE its `USING ... ON` source clause, so it is always scanned
 *   over the whole of [body]. When [body] is NOT a top-level `UPDATE`/`DELETE`/`MERGE` (e.g. the
 *   CTE-body caller's own body is a plain `SELECT` reached via a degraded, non-DML path — see
 *   `PgCatalogLoader.analyzeUnconvertibleDml`'s "R3" KDoc note), this parameter has no effect:
 *   scoping to a DML source clause that doesn't exist would silently turn the join arm permanently
 *   off for a body this predicate has no other way to protect, so the whole-body scan is kept
 *   instead — over-nullability on that already-degraded path remains the deliberately accepted,
 *   safe-direction outcome, not a bug this parameter is meant to fix.
 * @return A predicate taking a 1-based result column index and the probe's total column count,
 *   returning `true` when that column should be forced nullable regardless of what
 *   `ResultSetMetaData.isNullable`/base-table `attnotnull` reports for it.
 */
internal fun forcedNullabilityPredicate(
  body: String,
  dangerousSiblingNames: Set<String>,
  scopeNullExtendingScanToDmlSourceClause: Boolean = false,
): (columnIndex: Int, totalColumnCount: Int) -> Boolean {
  val oldOrNewAnalysis = oldOrNewReturningColumns(body)
  val hasNullExtending = if (scopeNullExtendingScanToDmlSourceClause && isUpdateDeleteOrMergeStatement(body)) {
    val sourceClauseRegion = dmlSourceClauseRegion(body)
    val sourceClauseHasJoinOrGroupingSet = sourceClauseRegion != null &&
      body.substring(sourceClauseRegion.first, sourceClauseRegion.last + 1).let {
        hasOuterJoin(it) || hasGroupingSetConstruct(it)
      }
    sourceClauseHasJoinOrGroupingSet || hasWhenNotMatchedBySourceClause(body)
  } else {
    hasNullExtendingConstruct(body)
  }
  val forceAllNullable = referencesAnyName(body, dangerousSiblingNames) ||
    (!isInsertBody(body) && hasNullExtending)
  return { columnIndex, totalColumnCount ->
    val oldOrNewColumns = oldOrNewAnalysis.forcedColumns
    // null forcedColumns means oldOrNewReturningColumns already recognized the
    // mapping as unreliable (a recognized star coincides with an OLD/NEW reference);
    // a non-null but count-mismatched result means it DIDN'T recognize why, but the
    // real column count from this probe proves the mapping unreliable anyway — either
    // way, force every column rather than trust any specific index.
    val oldOrNewMappingUnreliable = oldOrNewColumns == null ||
      (oldOrNewColumns.isNotEmpty() && oldOrNewAnalysis.itemCount != totalColumnCount)
    forceAllNullable || oldOrNewMappingUnreliable || oldOrNewColumns.orEmpty().contains(columnIndex)
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
 *
 * The result is a [StrippedText], not a plain `String`: deleting a separator PostgreSQL itself
 * lexed on can fuse two characters that were never adjacent in the original query into a token
 * that never existed — `1 - -1` (two independently-lexed `-` tokens) strips to `1--1`, which a
 * naive re-lex of the OUTPUT `String` alone would read as a `--` line comment. [StrippedText]
 * carries, alongside the stripped characters, each one's ORIGINAL offset, so any later
 * multi-character adjacency decision made against this output (a `--` line-comment or `/* */`
 * block-comment opener, the `$` dollar-quote identifier lookback, the standalone-`E` escape-string
 * lookback, a `''`/`""` doubled-quote escape) can be gated on whether the two characters were
 * REALLY adjacent in the query PostgreSQL itself lexed, not merely in this function's output.
 */
internal fun stripCommentsAndWhitespace(text: String): StrippedText {
  val builder = StringBuilder(text.length)
  val originalOffsets = ArrayList<Int>(text.length)
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
          for (originalIndex in i until afterToken) originalOffsets.add(originalIndex)
          i = afterToken
        } else {
          builder.append(text[i])
          originalOffsets.add(i)
          i++
        }
      }
    }
  }
  return StrippedText(builder.toString(), originalOffsets.toIntArray())
}

/**
 * The output of [stripCommentsAndWhitespace] — the stripped characters, plus each one's ORIGINAL
 * offset in the pre-stripping text, so [wereAdjacent] can answer whether two stripped characters
 * that now sit next to each other in [text] genuinely were adjacent before stripping, or whether a
 * comment/whitespace separator PostgreSQL itself lexed on used to sit between them. When a lexical
 * token (a string literal, a quoted identifier, a dollar-quoted string) is copied through wholesale
 * by [stripCommentsAndWhitespace], each of its characters maps to CONSECUTIVE original offsets, so
 * [wereAdjacent] is `true` throughout the token's own interior — only a genuinely removed
 * whitespace/comment separator between two DIFFERENT tokens (or bare characters) ever breaks that
 * consecutiveness.
 *
 * The raw stripped `String` is kept PRIVATE: every lexer entry point stripped-path code needs
 * ([skipLexicalToken], [findMatchingCloseParenthesis]) is exposed as a member here that threads
 * `this` as the [OriginalAdjacency], so stripped-path code cannot reach the `String`-taking lexer
 * functions and silently pass the wrong (or no) adjacency — the only way out to a plain `String` is
 * [asPlainString], a single, deliberately named, greppable escape hatch for a caller (currently only
 * [isStarQualifierAcceptable]) that does no lexing at all and therefore has no adjacency decision to
 * gate.
 */
internal class StrippedText(private val text: String, private val originalOffsets: IntArray) : OriginalAdjacency {

  val length: Int get() = text.length

  operator fun get(index: Int): Char = text[index]

  override fun wereAdjacent(leftIndex: Int): Boolean = leftIndex >= 0 &&
    leftIndex + 1 < originalOffsets.size &&
    originalOffsets[leftIndex + 1] == originalOffsets[leftIndex] + 1

  /** `true` if the stripped text is EXACTLY [other] — the whole-string equivalent of `==`. */
  fun contentEquals(other: String): Boolean = text == other

  fun endsWith(suffix: String): Boolean = text.endsWith(suffix)

  fun regionMatchesIgnoreCase(position: Int, other: String): Boolean =
    text.regionMatches(position, other, 0, other.length, ignoreCase = true)

  /** A new [StrippedText] over `[from, until)`, slicing both the stripped text and its offset map. */
  fun slice(from: Int, until: Int): StrippedText =
    StrippedText(text.substring(from, until), originalOffsets.copyOfRange(from, until))

  /** See [skipLexicalToken]'s KDoc — this threads `this` as the [OriginalAdjacency]. */
  fun skipLexicalToken(position: Int): Int = norm.generator.skipLexicalToken(text, position, this)

  /** See [findMatchingCloseParenthesis]'s KDoc — this threads `this` as the [OriginalAdjacency]. */
  fun findMatchingCloseParenthesis(openParenthesisIndex: Int): Int =
    norm.generator.findMatchingCloseParenthesis(text, openParenthesisIndex, this)

  /**
   * The single, deliberately named escape hatch out of this class's own lexer entry points, back
   * to a plain `String` — see this class's own KDoc for why every OTHER accessor exists instead of
   * this one. Safe only for a caller that does no lexing at all, i.e. has no adjacency decision to
   * gate; [isStarQualifierAcceptable] is the only current caller, since it merely inspects the
   * character class of a qualifier's trailing run.
   */
  fun asPlainString(): String = text
}

/**
 * `true` if [expression] is nothing but the bare `DEFAULT` keyword — a `SET column = DEFAULT`
 * assignment's right-hand side, which resolves to the column's table-level default (an unknown,
 * possibly-`null` value the caller makes no attempt to resolve) — once every comment and all
 * whitespace around or inside it is discarded via [stripCommentsAndWhitespace].
 *
 * An exact-string `.trim().equals("DEFAULT", ignoreCase = true)` comparison misses `DEFAULT /*
 * reset */` (a trailing comment after the keyword) and `/* x */ DEFAULT` (a leading one): a
 * comment is not whitespace, so `.trim()` alone leaves it in place and an exact-string check
 * never matches, letting a genuinely unresolved `DEFAULT` value get treated as an ordinary,
 * safe-to-evaluate expression. Reusing [stripCommentsAndWhitespace] — the same comment-aware
 * normalization [isStarItem] already trusts — removes any such comment (and all whitespace)
 * before the length-and-content check below runs, so both shapes above, and any other comment
 * placement, are caught.
 */
internal fun isDefaultKeyword(expression: String): Boolean {
  val stripped = stripCommentsAndWhitespace(expression)
  return stripped.length == "DEFAULT".length && stripped.regionMatchesIgnoreCase(0, "DEFAULT")
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
internal fun isStarItem(item: String): Boolean {
  val text = stripCommentsAndWhitespace(item.trim())
  val unwrappedText = unwrapWrappingParentheses(text)
  if (unwrappedText.contentEquals("*") || unwrappedText.endsWith(".*")) return true

  val aliasStart = findTrailingImplicitAliasStart(text) ?: return false
  val unwrappedPrefix = unwrapWrappingParentheses(text.slice(0, aliasStart))
  if (unwrappedPrefix.contentEquals("*")) return true
  return unwrappedPrefix.endsWith(".*") &&
    isStarQualifierAcceptable(unwrappedPrefix.slice(0, unwrappedPrefix.length - 1).asPlainString())
}

/**
 * Repeatedly strips a wrapping `(...)` from [text] — verified via
 * [StrippedText.findMatchingCloseParenthesis] to be a genuine matching pair (not merely the first
 * and last characters happening to be `(` and `)`) — until none remains: `((t.*))` unwraps in two
 * passes to `t.*`.
 */
private fun unwrapWrappingParentheses(text: StrippedText): StrippedText {
  var result = text
  while (result.length >= 2 &&
    result[0] == '(' &&
    result[result.length - 1] == ')' &&
    result.findMatchingCloseParenthesis(0) == result.length - 1
  ) {
    result = result.slice(1, result.length - 1)
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
private fun findTrailingImplicitAliasStart(text: StrippedText): Int? {
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
    val afterToken = text.skipLexicalToken(i)
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
 *    PostgreSQL identifiers may not start with a digit or `$`. A `$` encountered mid-run stops the
 *    run instead of continuing it, per [OriginalAdjacency]'s own gate, when [text] says it was NOT
 *    genuinely adjacent to the character before it — see the loop below.
 *
 * @return The index immediately after the matched segment, or `null` if [start] does not begin
 *   one.
 */
private fun matchTrailingAliasSegment(text: StrippedText, start: Int): Int? {
  matchUnicodeEscapeIdentifierSegment(text, start)?.let { return it }
  if (start >= text.length) return null
  if (text[start] == '"') {
    val afterToken = text.skipLexicalToken(start)
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
  while (i < text.length && isIdentifierChar(text[i])) {
    // A "$" is the ONE isIdentifierChar character that can also OPEN an entirely different,
    // opaque lexical token — a dollar-quoted string (see skipLexicalToken's own dollar-quote
    // guard) — rather than merely continuing this identifier. PostgreSQL's real lexer only makes
    // that distinction by whether the "$" is genuinely adjacent to the identifier character before
    // it; naively treating every isIdentifierChar "$" as a continuation, regardless of adjacency,
    // would let a stripped-away separator (e.g. "xq $q$a'b$q$" strips to "xq$q$a'b$q$") fuse a
    // dollar-quote's OWN opening "$" into this run, swallowing part of its tag and its embedded
    // "'" as ordinary identifier text — corrupting this segment's own boundary and, via the
    // embedded "'", everything findTrailingImplicitAliasStart scans afterward (see
    // OriginalAdjacency's KDoc). Stopping the run here — rather than consuming the "$" — hands
    // that position back to findTrailingImplicitAliasStart's own [StrippedText.skipLexicalToken]
    // fallback, which correctly recognizes the dollar-quoted string as one opaque, non-segment
    // token and walks over it whole.
    if (text[i] == '$' && !text.wereAdjacent(i - 1)) break
    i++
  }
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
 * The `UESCAPE` keyword's own adjacency to the identifier before it is deliberately left UNGATED,
 * unlike every other multi-character adjacency decision in this file: PostgreSQL itself permits
 * whitespace between a `U&"..."` identifier and its `UESCAPE` clause (verified: `U&"!0074"
 * UESCAPE '!'` and `U&"!0074"UESCAPE'!'` both resolve identically), so the abutment stripping
 * creates here is not a manufactured token — [stripCommentsAndWhitespace] is implementing the real
 * grammar, not accidentally fusing two things PostgreSQL lexed apart. And even if some other
 * adjacency this function doesn't check turned out to matter, a false match here only EXTENDS the
 * matched segment further than it should — the same safe direction [isStarItem] relies on
 * throughout (see its KDoc), not the dangerous one a gate exists to prevent.
 *
 * @return The index immediately after the identifier (and its `UESCAPE` clause, if present), or
 *   `null` if [start] does not begin a `U&`/`u&`-prefixed double-quoted identifier at all.
 */
private fun matchUnicodeEscapeIdentifierSegment(text: StrippedText, start: Int): Int? {
  if (start + 1 >= text.length) return null
  if ((text[start] != 'U' && text[start] != 'u') || text[start + 1] != '&') return null
  val quoteStart = start + 2
  if (quoteStart >= text.length || text[quoteStart] != '"') return null
  val afterIdentifier = text.skipLexicalToken(quoteStart)

  val keyword = "UESCAPE"
  if (!text.regionMatchesIgnoreCase(afterIdentifier, keyword)) {
    return afterIdentifier
  }
  val afterKeyword = afterIdentifier + keyword.length
  val isWordBoundary = afterKeyword >= text.length || !isIdentifierChar(text[afterKeyword])
  if (!isWordBoundary || afterKeyword >= text.length || text[afterKeyword] != '\'') {
    return afterIdentifier
  }
  val afterEscapeString = text.skipLexicalToken(afterKeyword)
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
 * @param adjacency See [OriginalAdjacency]'s KDoc. Defaults to [ALL_ADJACENT], correct for raw SQL
 *   text; [StrippedText] threads itself here for its own [StrippedText.skipLexicalToken] entry
 *   point, gating the `--` line-comment and `/* */` block-comment openers and the `$` dollar-quote
 *   identifier lookback below (and, transitively, the standalone-`E` and `''`/`""` doubled-quote
 *   checks inside [skipSingleQuotedString]/[skipDoubleQuotedIdentifier]) on whether stripping
 *   actually fused these characters together, versus PostgreSQL itself having lexed them adjacent.
 * @return The index after the lexical token, or [position] if none starts there.
 */
internal fun skipLexicalToken(sql: String, position: Int, adjacency: OriginalAdjacency = ALL_ADJACENT): Int {
  if (position >= sql.length) return position
  return when {
    sql[position] == '\'' -> skipSingleQuotedString(sql, position, adjacency)
    sql[position] == '"' -> skipDoubleQuotedIdentifier(sql, position, adjacency)
    // A "$" immediately after an identifier character (e.g. the second "$" in "a$b$c", or
    // either "$" in "x$$y") can never OPEN a dollar quote — it is continuing the identifier
    // that started before it, exactly as PostgreSQL's own lexer only recognizes dollar-quote
    // tags at the START of a new token. Without this guard, "a$b$c" reads as "a" followed by a
    // "$b$"-tagged dollar-quote opener that swallows everything up to the next literal "$b$" (or
    // the rest of the string, if there isn't one).
    //
    // Gated on adjacency.wereAdjacent(position - 1): stripping only ever removes whitespace and
    // comments, neither of which is an identifier character, so if the character now sitting
    // immediately before this "$" was NOT actually adjacent to it in the original text, whatever
    // real separator used to sit there means this "$" genuinely opens a new token, regardless of
    // what character stripping happened to fuse in front of it (e.g. "x $q$...$q$" strips to
    // "x$q$...$q$", where the "x" was never really continuing into "$q$" in the original query).
    sql[position] == '$' &&
      !(position > 0 && adjacency.wereAdjacent(position - 1) && isIdentifierChar(sql[position - 1])) ->
      // skipDollarQuotedString takes the same adjacency and applies its own further gates to the
      // opening and closing delimiters themselves — see its KDoc.
      skipDollarQuotedString(sql, position, adjacency) ?: position
    sql[position] == '-' && position + 1 < sql.length && sql[position + 1] == '-' && adjacency.wereAdjacent(position) ->
      skipLineComment(sql, position)
    sql[position] == '/' && position + 1 < sql.length && sql[position + 1] == '*' && adjacency.wereAdjacent(position) ->
      skipBlockComment(sql, position)
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
 * digit, or `_` — now uses [isIdentifierChar], the SAME predicate every other word-boundary check
 * in this file uses, GATED on [adjacency] (see [OriginalAdjacency]'s KDoc). It did not always: this
 * file's own generator round for issue #219 established (against real PostgreSQL 18.4) that
 * [isStarItem] normalizes a select item through [stripCommentsAndWhitespace] and then RE-LEXES the
 * stripped string — so deleting a separator PostgreSQL actually lexed on can manufacture tokens
 * that were never in the query. Widening this lookback to [isIdentifierChar] WITHOUT a gate made
 * `x E'a\'b'` (valid PostgreSQL: the `AexprConst: func_name Sconst` typed-literal form, `func_name`
 * here being the ordinary identifier `x`) normalize to `xE'a\'b'` — the space PostgreSQL lexed as a
 * separator between two tokens is gone — which then mis-lexed as a SINGLE standalone-`E` escape
 * string, turning `parseSelectItems("SELECT (f(x€ E'a\\'b')).* y, id FROM t")` from `[]` on the
 * pre-#219 code — the fail-safe answer, since callers fall back to
 * `ResultSetMetaData.getColumnName` — into 2 items, a WRONG positional split that misapplies a
 * column-level type override. That regression, issue #222, is what led to reverting this lookback
 * to a narrow letter/digit/`_` class — which does NOT match PostgreSQL's lexer for raw SQL (it
 * misses `$` and any `>= 0x80` character, both legal identifier-continuation characters) — pending
 * a fix to the root cause: [stripCommentsAndWhitespace] returning an index map back to the original
 * text, so this lookback (like the `--` line-comment and `/* */` block-comment openers and the `$`
 * dollar-quote identifier lookback in [skipLexicalToken], and the `''` doubled-quote check just
 * below) could be gated on ORIGINAL adjacency instead of a narrower character class. That fix has
 * now landed — [OriginalAdjacency] — so the widening is safe: for the same `x€ E'a\'b'` shape,
 * `adjacency.wereAdjacent` on the character position immediately before `E` reports `false` (the
 * space that used to separate `€` and `E` is gone from the stripped text, but that fusion is not
 * something stripping is entitled to manufacture), so `E` is STILL recognized as standalone
 * regardless of what character stripping happened to fuse in front of it — see [isIdentifierChar]'s
 * own KDoc for why a widened check here is otherwise the MORE faithful one to PostgreSQL's real
 * lexer.
 *
 * @param adjacency See [OriginalAdjacency]'s KDoc. Gates both the "is the character immediately
 *   before the opening quote genuinely `E`/`e`" check and, when it is, the standalone lookback one
 *   position further back — non-adjacency at that second position means the real predecessor was a
 *   separator PostgreSQL itself lexed on, so `E`/`e` IS standalone regardless of what character
 *   stripping fused in front of it.
 * @return The index after the closing quote, or `sql.length` if unterminated.
 */
private fun skipSingleQuotedString(sql: String, openQuoteIndex: Int, adjacency: OriginalAdjacency = ALL_ADJACENT): Int {
  val precedingChar = if (openQuoteIndex > 0) sql[openQuoteIndex - 1] else null
  val eAbutsQuote = openQuoteIndex > 0 && adjacency.wereAdjacent(openQuoteIndex - 1)
  val precedingCharIsStandalone = openQuoteIndex < 2 ||
    !adjacency.wereAdjacent(openQuoteIndex - 2) ||
    !isIdentifierChar(sql[openQuoteIndex - 2])
  val isEscapeString = eAbutsQuote && (precedingChar == 'E' || precedingChar == 'e') && precedingCharIsStandalone
  var i = openQuoteIndex + 1
  while (i < sql.length) {
    if (isEscapeString && sql[i] == '\\' && i + 1 < sql.length) {
      i += 2
      continue
    }
    if (sql[i] == '\'') {
      // The '' doubled-quote-escape check is gated on adjacency too: this is load-bearing, not
      // uniformity-for-its-own-sake — fusion composes with escape-string mode. If a separator
      // PostgreSQL lexed between two genuinely SEPARATE quote characters gets stripped away,
      // fusing them into what LOOKS like a doubled '' escape, treating it as one would keep this
      // scan going (still in isEscapeString mode, if it started that way) past what should have
      // been the first string's real terminator — potentially overrunning all the way to
      // sql.length and defeating findTrailingImplicitAliasStart's "last segment must end exactly
      // at text.length" anchor (see its KDoc) in the dangerous direction (see isStarItem's KDoc).
      val firstQuoteIndex = i
      i++
      if (i < sql.length && sql[i] == '\'' && adjacency.wereAdjacent(firstQuoteIndex)) {
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
 * @param adjacency See [OriginalAdjacency]'s KDoc. Gates the `""` doubled-quote-escape check, for
 *   the same invariant [skipSingleQuotedString]'s own `''` gate states — though, unlike that one,
 *   this is uniformity rather than a fix earning its own test: every double-quoted-identifier span
 *   this codebase's tests construct happens to coincide whether or not this specific check is
 *   gated, so this is included for the same structural reason as every other multi-character
 *   adjacency decision in this file, not because a concrete wrong-answer case was found.
 * @return The index after the closing quote, or `sql.length` if unterminated.
 */
private fun skipDoubleQuotedIdentifier(
  sql: String,
  openQuoteIndex: Int,
  adjacency: OriginalAdjacency = ALL_ADJACENT,
): Int {
  var i = openQuoteIndex + 1
  while (i < sql.length) {
    if (sql[i] == '"') {
      val firstQuoteIndex = i
      i++
      if (i < sql.length && sql[i] == '"' && adjacency.wereAdjacent(firstQuoteIndex)) {
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
 * `true` if every consecutive pair of characters in `[start, endExclusive)` was genuinely
 * adjacent in the original text, per [adjacency] — see [OriginalAdjacency]'s KDoc. Used by
 * [skipDollarQuotedString] to verify its OPENING delimiter is lexically contiguous, not merely
 * contiguous in [stripCommentsAndWhitespace]'s stripped output — see that function's KDoc for why
 * the closing delimiter needs no such check of its own.
 */
private fun isAdjacencyContiguousSpan(adjacency: OriginalAdjacency, start: Int, endExclusive: Int): Boolean {
  for (leftIndex in start until endExclusive - 1) {
    if (!adjacency.wereAdjacent(leftIndex)) return false
  }
  return true
}

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
 * The OPENING delimiter is additionally required to be adjacency-contiguous (see
 * [OriginalAdjacency]'s KDoc): every character of it — the leading `$` at [position], the tag run,
 * and the tag's closing `$` — must have been genuinely adjacent to its neighbour in the original
 * text. Without this, stripping can invent a delimiter that was never one lexical unit in the
 * original query — e.g. `$q  b $/ /` (two independent `$`-prefixed tokens and a `/`-prefixed
 * token, none of them a real dollar-quote) strips to `$qb$//`, whose fused `$qb$` would otherwise
 * be read as an opening delimiter with tag `qb`, and `//` as its (unterminated) body.
 *
 * The CLOSING delimiter needs no adjacency check of its own: once the opening delimiter has passed
 * both this gate and the caller's own `$`-not-preceded-by-an-identifier-character lookback (see
 * [skipLexicalToken]'s call site), the ORIGINAL text genuinely opened a dollar-quoted string at
 * [position] — and [stripCommentsAndWhitespace] makes that identical determination (via this same
 * function, on the original text) BEFORE ever stripping anything, so it copies the entire matched
 * token — opening delimiter, body, and closing delimiter alike — through to its output VERBATIM,
 * giving every character in that span consecutive original offsets. A real closing tag inside a
 * verbatim-copied span is therefore always adjacency-contiguous already, by construction, and no
 * fused, fake closing tag can appear inside one either — there is nothing left for a second gate to
 * catch. (An UNTERMINATED dollar-quote — no closing tag found in [sql] at all — is unaffected by
 * any of this: it is not a stripping artifact, and is handled the same way regardless.)
 *
 * @param adjacency See [OriginalAdjacency]'s KDoc. Defaults to [ALL_ADJACENT], correct for raw SQL
 *   text — every neighbouring pair is trivially adjacent there, so this gate never rejects a
 *   genuine raw-text dollar-quote. [skipLexicalToken] threads its own `adjacency` parameter through
 *   here.
 * @return The index after the closing tag (or `sql.length` if unterminated), or `null` if
 *   [position] is a `$` that is not followed by a valid closing tag delimiter at all (e.g. a
 *   bare `$` used as an operator, or a positional parameter marker like `$1` with no matching
 *   second `$`), or if the opening delimiter itself is not adjacency-contiguous — the caller
 *   should treat that `$` as an ordinary character, not lexically skip it.
 */
private fun skipDollarQuotedString(sql: String, position: Int, adjacency: OriginalAdjacency = ALL_ADJACENT): Int? {
  var i = position + 1
  val tagStart = i
  if (i < sql.length && isIdentifierStartChar(sql[i])) {
    i++
    while (i < sql.length && isDollarQuoteTagContinuationChar(sql[i])) i++
  }
  if (i >= sql.length || sql[i] != '$') return null
  if (!isAdjacencyContiguousSpan(adjacency, position, i + 1)) return null
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
