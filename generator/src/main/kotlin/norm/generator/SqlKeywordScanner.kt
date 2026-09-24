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
 * comma-separated arguments), `SqlParameterInferrer.extractValuesExpressions` (an `INSERT ...
 * VALUES (...)` clause's comma-separated expressions), and its INSERT column list (`INSERT INTO
 * t(col1, col2)`, split the same way so a quoted column name containing its own `)` or `,` is not
 * mistaken for a list boundary) to attribute each `?` placeholder to its argument/column position
 * for parameter-name inference: a multi-element `ARRAY[...]` literal (2+ placeholders inside it)
 * must be tracked as one argument/column slot, not split into several by its own internal commas,
 * or every placeholder sharing that argument list or `VALUES` list — including ones inside the
 * array itself — gets attributed to the wrong position.
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
 * Finds the first occurrence of [keyword] in [sql], starting at [startIndex], as a complete word at
 * any parenthesis depth — unlike [findTopLevelKeyword], which only matches at depth 0.
 *
 * Skips over string literals, quoted identifiers, dollar-quoted strings, and comments via
 * [skipLexicalToken] so a keyword-like word inside one of those (e.g. a `VALUES` inside a `/* */`
 * comment, or a `WHERE` inside the string literal `'copied from WHERE'`) is never mistaken for the
 * real keyword. A candidate match is also rejected — via [isIdentifierChar] — when it is adjacent
 * to any character PostgreSQL allows inside an unquoted identifier, so `wherefore` is never
 * mistaken for the keyword `WHERE`.
 *
 * @return The index of the keyword, or `-1` if not found.
 */
internal fun findKeyword(sql: String, keyword: String, startIndex: Int = 0): Int {
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
 * Finds the `RETURNING` keyword that opens a DML statement's `RETURNING` clause.
 *
 * For `SELECT email AS returning, x FROM t RETURNING id`, this returns the index of the uppercase `RETURNING`.
 *
 * @return The index of the keyword, or `-1` if [sql] has no top-level `RETURNING` clause.
 */
internal fun findTopLevelReturningKeyword(sql: String): Int = findTopLevelClauseKeyword(sql, "RETURNING", 0)

/**
 * Finds the `FROM` keyword that opens a `SELECT`'s `FROM` clause, searching [sql] from [startIndex].
 *
 * For `SELECT a IS DISTINCT FROM b FROM t`, this returns the index of the second `FROM`.
 *
 * @return The index of the keyword, or `-1` if no top-level `FROM` clause starts at or after [startIndex].
 */
internal fun findTopLevelFromClauseKeyword(sql: String, startIndex: Int): Int =
  findTopLevelClauseKeyword(sql, "FROM", startIndex)

/**
 * Finds the first depth-0 [keyword] in [sql], at or after [startIndex], that starts a clause.
 *
 * Text that [skipLexicalToken] skips does not match. A word spelled like [keyword] also does not match when it is:
 * - a qualified name's field, as in `s.from` or `(s).from`;
 * - a column label, as in `1 AS from`;
 * - the `FROM` of `IS [NOT] DISTINCT FROM`.
 *
 * @return The index of the keyword, or `-1` if there is none.
 */
private fun findTopLevelClauseKeyword(sql: String, keyword: String, startIndex: Int): Int {
  var depth = 0
  // Set by a `.` that qualifies the next word. The `.` in `1.` belongs to the numeric literal.
  var precededByQualificationDot = false
  var previousTokenIsDigitLeadingWord = false
  var previousWord: String? = null
  var previousWordInPosition = false
  var wordBeforePrevious: String? = null
  var wordBeforePreviousInPosition = false
  var i = startIndex
  while (i < sql.length) {
    val afterToken = skipLexicalToken(sql, i)
    if (afterToken != i) {
      // A comment separates words the way whitespace does. Any other skipped token clears the state, and a
      // quoted identifier followed by `.` still sets precededByQualificationDot below.
      val isComment = sql[i] == '-' || sql[i] == '/'
      if (!isComment) {
        precededByQualificationDot = false
        previousTokenIsDigitLeadingWord = false
        previousWord = null
        previousWordInPosition = false
        wordBeforePrevious = null
        wordBeforePreviousInPosition = false
      }
      i = afterToken
      continue
    }
    // `Char.isWhitespace()` accepts non-ASCII spaces such as U+00A0, which PostgreSQL reads as identifier
    // characters, so the identifier branch comes first.
    when {
      isIdentifierChar(sql[i]) -> {
        val wordStart = i
        while (i < sql.length && isIdentifierChar(sql[i])) i++
        val word = sql.substring(wordStart, i)
        val afterAs = previousWordInPosition && previousWord.equals("AS", ignoreCase = true)
        val afterIsDistinct = previousWordInPosition &&
          previousWord.equals("DISTINCT", ignoreCase = true) &&
          wordBeforePreviousInPosition &&
          (wordBeforePrevious.equals("IS", ignoreCase = true) || wordBeforePrevious.equals("NOT", ignoreCase = true))
        val inPosition = !precededByQualificationDot && !afterAs && !afterIsDistinct
        if (depth == 0 && word.equals(keyword, ignoreCase = true) && inPosition) return wordStart
        wordBeforePrevious = previousWord
        wordBeforePreviousInPosition = previousWordInPosition
        previousWord = word
        previousWordInPosition = inPosition
        precededByQualificationDot = false
        previousTokenIsDigitLeadingWord = word[0] in '0'..'9'
      }
      sql[i].isWhitespace() -> i++
      sql[i] == '.' -> {
        // Unquoted identifiers cannot start with an ASCII digit, so such a word before `.` is a numeric literal
        // such as `1.` or `1_000.`. PostgreSQL accepts non-ASCII digits like `٣` as identifier characters.
        precededByQualificationDot = !previousTokenIsDigitLeadingWord
        previousTokenIsDigitLeadingWord = false
        i++
      }
      else -> {
        when (sql[i]) {
          '(' -> depth++
          ')' -> depth--
        }
        precededByQualificationDot = false
        previousTokenIsDigitLeadingWord = false
        previousWord = null
        previousWordInPosition = false
        wordBeforePrevious = null
        wordBeforePreviousInPosition = false
        i++
      }
    }
  }
  return -1
}
