package norm.generator

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
 * Splits text on a delimiter character, respecting nested parentheses AND square brackets.
 *
 * For `"EXISTS(...) AS valid, col1"` split on `,`, returns `["EXISTS(...) AS valid", "col1"]`.
 *
 * Both bracket kinds share one depth counter rather than two independently-tracked ones: SQL
 * never interleaves them invalidly (a `[` is always closed by its own `]` before any enclosing
 * `(` closes, and vice versa), so treating `(`/`[` as "one level deeper" and `)`/`]` as "one level
 * shallower" — regardless of which bracket kind opened that level — is sufficient to find the
 * real top-level delimiters. Leaving square brackets untracked would split an `ARRAY[1, 2]` item
 * into two, since its internal comma is not hidden by any enclosing `(...)` — on real Postgres,
 * `ARRAY[1, 2] AS arr, OLD.tval AS oldv` splits into 3 items instead of 2 real columns. Worse,
 * that error can silently cancel out a separate star-caused split error elsewhere in the same
 * list, making a real-column-count cross-check see a coincidentally-matching count and trust a
 * garbled, wrongly-indexed split: `tgt . *, OLD.tval AS oldv, ARRAY[1, 2] AS arr` splits into 4
 * items against 4 real columns, the same count, while the split itself is `["tgt . *", "OLD.tval
 * AS oldv", "ARRAY[1", "2] AS arr"]` — nothing about those items corresponds to the real columns.
 *
 * Skips string literals, quoted identifiers, dollar-quoted strings, and comments via
 * [skipLexicalToken], so a `(`/`)`/`[`/`]`/[delimiter] that only appears inside one of those (e.g.
 * a string literal containing a stray `,` or unbalanced bracket) is not mistaken for a real one.
 *
 * This function is also used by `SqlParameterInferrer.extractFunctionCalls` (a function call's
 * comma-separated arguments) and `SqlParameterInferrer.extractValuesExpressions` (an `INSERT ...
 * VALUES (...)` clause's comma-separated expressions) to attribute each `?` placeholder to its
 * argument/column position for parameter-name inference: a multi-element `ARRAY[...]` literal
 * (2+ placeholders inside it) must be tracked as one argument/column slot, not split into several
 * by its own internal commas, or every placeholder sharing that argument list or `VALUES` list —
 * including ones inside the array itself — gets attributed to the wrong position.
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
 * well-formed, already-balanced text this scan assumes — this bails immediately to `-1` (not
 * found) rather than clamping `depth` at `0` and continuing. Clamping would let the scan silently
 * recover and keep searching past the unbalanced point, which is not obviously safe either way:
 * this function's own callers (`parseSelectItems`'s KDoc for one) treat a missing keyword as the
 * dangerous direction — e.g. a missing `FROM` making `parseSelectItems` fall through to
 * `window.substring(itemsStart)`, taking more text as items than it should — a clamp-and-continue
 * scan could just as easily find some later, wrongly-in-scope keyword instead of correctly
 * finding none at all. An unbalanced scan's assumptions are already void by that point, so
 * returning `-1` loudly, rather than guessing which recovery is safe, favors an honestly-wrong
 * "not found" a caller's existing fallback already handles, over a confidently-wrong match this
 * function cannot itself tell apart from a correct one.
 *
 * @return The index of the keyword, or `-1` if not found at the top level, including when [sql]
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
 * Finds the top-level `RETURNING` clause keyword in [sql] — distinguishing it from a bare
 * `returning` used as an explicit `AS returning` column alias, which is otherwise legal PostgreSQL
 * syntax. On PostgreSQL 18.4, `CREATE TABLE bad (returning int)`, `FROM t returning`, `t AS
 * returning`, and the implicit alias `SELECT email returning FROM users` are all syntax errors —
 * an explicit `AS returning` column alias is the only position a bare `returning` token is legal
 * in. A plain [findTopLevelKeyword] search returns the first match, which can be that alias
 * rather than the real clause (`SELECT email AS returning, x FROM t RETURNING id` — the alias
 * comes first); this function instead returns the first `RETURNING` not immediately preceded by
 * the word `AS`, which is exact rather than heuristic given the grammar fact above — an alias
 * position is always `AS`-preceded, and the real clause keyword never is.
 *
 * A forward single-pass walk in the style of [findTopLevelKeyword]: parenthesis depth is tracked
 * so only a depth-0 `RETURNING` counts, and [skipLexicalToken] skips string literals, quoted
 * identifiers, dollar-quoted strings, and comments so a keyword-like substring inside one of those
 * is never mistaken for a real keyword. A comment does not disturb the "was the previous word
 * `AS`" state — a comment between `AS` and its alias is a separator, not a token (both `SELECT 1
 * AS/*c*/returning` and `SELECT 1 AS--x` followed by a newline then `returning` are legal
 * syntax) — while a string literal, quoted identifier, or dollar-quoted string does clear that
 * state, since none of those can themselves be the word `AS`. Plain whitespace, like a comment,
 * is also not disturbing: it is the ordinary separator between `AS` and an unquoted alias, which
 * is the common case this function must not break. Any other character (a parenthesis, comma, or
 * operator) clears the state, since none of those can be the word `AS` either. Word-boundary
 * handling is inherently correct here, unlike a substring search would be, because the walk
 * consumes whole words at a time via [isIdentifierChar] — an ordinary identifier like
 * `returning_batch`, or one continuing with a `>= 0x80` character like `returning€` (a legal
 * PostgreSQL column name — PostgreSQL's lexer admits any byte `>= 0x80` inside an unquoted
 * identifier), is read as one word, never mistaken for the bare keyword. Stopping the word scan
 * at `€` instead would see the bare word `returning` and misidentify the alias position as the
 * real clause.
 *
 * The identifier branch is checked before the whitespace branch, deliberately: Kotlin's
 * `Char.isWhitespace()` is `true` for several `>= 0x80` characters PostgreSQL does not treat as
 * whitespace at all (e.g. U+00A0 no-break space, U+2000-U+200A the various Unicode spaces, U+3000
 * ideographic space) — every character PostgreSQL's own lexer treats as whitespace is ASCII
 * (`< 0x80`), so [isIdentifierChar]'s wide `>= 0x80` branch never conflicts with a genuine
 * PostgreSQL whitespace character. Checking whitespace first would let one of those non-ASCII
 * "whitespace" characters act as an ordinary separator between two otherwise-adjacent words —
 * splitting what PostgreSQL's lexer reads as one identifier into a leading word that can, in turn,
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
 * Finds the top-level `FROM` keyword that opens a `SELECT`'s `FROM` clause, in [sql] starting at or
 * after [startIndex] — distinguishing it from a bare `FROM` that is really part of `IS [NOT]
 * DISTINCT FROM`'s own comparison syntax, which has no clause boundary at that `FROM` at all. A
 * plain [findTopLevelKeyword] search returns the first depth-0 `FROM`, which for `SELECT a IS
 * DISTINCT FROM b FROM t` is the one inside `IS DISTINCT FROM` — truncating the select list to `a IS
 * DISTINCT` and treating `b FROM t` as if it were the real clause.
 *
 * A forward single-pass walk in the style of [findTopLevelReturningKeyword]: a depth-0 `FROM` is
 * skipped when the word immediately preceding it is `DISTINCT` — the only grammar production in
 * which a bare, unparenthesized `FROM` can appear without opening a real `FROM` clause.
 * `EXTRACT(field FROM source)`, `SUBSTRING(x FROM y)`, and `OVERLAY(... FROM z ...)` all wrap their
 * own `FROM` inside that function call's own parentheses, already excluded by
 * [findTopLevelKeyword]'s ordinary depth tracking. A comment between `DISTINCT` and `FROM` does not
 * disturb this check, exactly as [findTopLevelReturningKeyword]'s own `AS` check treats a comment
 * between `AS` and its alias as a separator, not a token.
 *
 * @return The index of the keyword, or `-1` if there is no top-level `FROM` at or after
 *   [startIndex] that isn't itself part of `IS [NOT] DISTINCT FROM`.
 */
internal fun findTopLevelFromClauseKeyword(sql: String, startIndex: Int): Int {
  var depth = 0
  var previousWordIsDistinct = false
  var i = startIndex
  while (i < sql.length) {
    val afterToken = skipLexicalToken(sql, i)
    if (afterToken != i) {
      val isComment = sql[i] == '-' || sql[i] == '/'
      if (!isComment) previousWordIsDistinct = false
      i = afterToken
      continue
    }
    when {
      isIdentifierChar(sql[i]) -> {
        val wordStart = i
        while (i < sql.length && isIdentifierChar(sql[i])) i++
        val word = sql.substring(wordStart, i)
        if (depth == 0 && word.equals("FROM", ignoreCase = true) && !previousWordIsDistinct) {
          return wordStart
        }
        previousWordIsDistinct = word.equals("DISTINCT", ignoreCase = true)
      }
      sql[i].isWhitespace() -> i++
      else -> {
        when (sql[i]) {
          '(' -> depth++
          ')' -> depth--
        }
        previousWordIsDistinct = false
        i++
      }
    }
  }
  return -1
}
