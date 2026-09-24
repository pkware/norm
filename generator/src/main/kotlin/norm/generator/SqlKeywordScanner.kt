package norm.generator

/**
 * Finds the index of the closing parenthesis that matches an opening `(` at [openParenthesisIndex].
 *
 * Walks [text] via [SqlTokenCursor], which skips string literals, quoted identifiers,
 * dollar-quoted strings, and comments, so a `(`/`)` that only appears inside one of those (e.g.
 * `RETURNING regexp_replace(name, '\(', '')`, where the string literal contains an unbalanced `(`)
 * is never mistaken for a real parenthesis. Tracks `(`/`)` only, not `[`/`]`.
 *
 * @param text The string to search.
 * @param openParenthesisIndex The index of the opening `(`. The search starts at `openParenthesisIndex + 1`.
 * @param adjacency See [OriginalAdjacency]'s KDoc. Defaults to [ALL_ADJACENT], correct for raw SQL
 *   text; [StrippedText] threads itself here for its own [StrippedText.findMatchingCloseParenthesis]
 *   entry point.
 * @return The index of the matching `)`, or `-1` when [text] ends before depth returns to the
 *   level of the opening `(`.
 */
internal fun findMatchingCloseParenthesis(
  text: String,
  openParenthesisIndex: Int,
  adjacency: OriginalAdjacency = ALL_ADJACENT,
): Int {
  val cursor = SqlTokenCursor(text, openParenthesisIndex, adjacency)
  cursor.advance() // The opening "(" itself; cursor.depth is now 1.
  while (true) {
    val span = cursor.advance() ?: return -1
    if (span is SqlSpan.Char && text[span.index] == ')' && cursor.depth == 0) return span.index
  }
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
 * [SqlTokenCursor], so a `(`/`)`/`[`/`]`/[delimiter] that only appears inside one of those (e.g.
 * a string literal containing a stray `,` or unbalanced bracket) is not mistaken for a real one.
 * Depth is never clamped at `0`: a stray unmatched `)`/`]` before a real delimiter does not stop
 * the scan, so a later `(`/`[` bringing depth back to exactly `0` still splits normally.
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
  var start = 0
  val cursor = SqlTokenCursor(text, 0, trackSquareBrackets = true)
  while (true) {
    val span = cursor.advance() ?: break
    if (span is SqlSpan.Char && text[span.index] == delimiter && cursor.depth == 0) {
      items.add(text.substring(start, span.index))
      start = span.index + 1
    }
  }
  items.add(text.substring(start))
  return items
}

/**
 * Finds a SQL keyword at the top level (not inside parentheses) in the given string.
 *
 * Walks [sql] via [SqlTokenCursor], which skips string literals, quoted identifiers, dollar-quoted
 * strings, and comments, so a keyword-like word or a `(`/`)` that only appears inside one of those
 * (e.g. `SET name = 'copied from source'`, which contains the word `from`) is never mistaken for a
 * real keyword or parenthesis. A match requires the whole [SqlSpan.Word] to equal [keyword], so
 * `valid_from`, `from_date`, `data_set`, and similar identifiers that merely contain [keyword] as a
 * substring are never mistaken for it.
 *
 * @return The index of [keyword], or `-1` if not found at the top level, including as soon as an
 *   unmatched closing parenthesis drives depth negative — an unbalanced [sql] is not the
 *   already-balanced text this scan assumes, so it bails rather than risk a match inside the
 *   malformed region.
 */
internal fun findTopLevelKeyword(sql: String, keyword: String, startIndex: Int = 0): Int {
  val cursor = SqlTokenCursor(sql, startIndex)
  while (true) {
    val span = cursor.advance() ?: return -1
    if (cursor.depth < 0) return -1
    if (span is SqlSpan.Word &&
      cursor.depth == 0 &&
      span.to - span.from == keyword.length &&
      sql.regionMatches(span.from, keyword, 0, keyword.length, ignoreCase = true) &&
      !(span.from == startIndex && startIndex > 0 && isIdentifierChar(sql[startIndex - 1]))
    ) {
      return span.from
    }
  }
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
 * A depth-0 word spelled like [keyword] still does not match when it is a qualified name's field
 * (`s.from`, `(s).from`), a column label (`1 AS from`), or the `FROM` of `IS [NOT] DISTINCT FROM`.
 * A negative depth (an unmatched closing parenthesis before this point) does not stop the scan;
 * matching only ever happens at depth `0`.
 *
 * Text inside an [SqlSpan.Opaque] span does not match. A comment separates words the way whitespace
 * does; any other opaque span (a string literal, a quoted identifier, a dollar-quoted string) is a
 * token of its own, so it ends any `AS` or `IS [NOT] DISTINCT` context before it. A `.` after it
 * still qualifies the next word, as in `"s".from`.
 *
 * @return The index of the keyword, or `-1` if there is none.
 */
private fun findTopLevelClauseKeyword(sql: String, keyword: String, startIndex: Int): Int {
  // Set by a `.` that qualifies the next word. The `.` in `1.` belongs to the numeric literal.
  var precededByQualificationDot = false
  var previousTokenIsDigitLeadingWord = false
  var previousWord: String? = null
  var previousWordInPosition = false
  var wordBeforePrevious: String? = null
  var wordBeforePreviousInPosition = false

  fun resetWordState() {
    precededByQualificationDot = false
    previousTokenIsDigitLeadingWord = false
    previousWord = null
    previousWordInPosition = false
    wordBeforePrevious = null
    wordBeforePreviousInPosition = false
  }

  val cursor = SqlTokenCursor(sql, startIndex)
  while (true) {
    val span = cursor.advance() ?: return -1
    when (span) {
      is SqlSpan.Opaque -> if (!span.isComment) resetWordState()
      is SqlSpan.Word -> {
        val word = sql.substring(span.from, span.to)
        val afterAs = previousWordInPosition && previousWord.equals("AS", ignoreCase = true)
        val afterIsDistinct = previousWordInPosition &&
          previousWord.equals("DISTINCT", ignoreCase = true) &&
          wordBeforePreviousInPosition &&
          (wordBeforePrevious.equals("IS", ignoreCase = true) || wordBeforePrevious.equals("NOT", ignoreCase = true))
        val inPosition = !precededByQualificationDot && !afterAs && !afterIsDistinct
        if (cursor.depth == 0 && word.equals(keyword, ignoreCase = true) && inPosition) return span.from
        wordBeforePrevious = previousWord
        wordBeforePreviousInPosition = previousWordInPosition
        previousWord = word
        previousWordInPosition = inPosition
        precededByQualificationDot = false
        previousTokenIsDigitLeadingWord = word[0] in '0'..'9'
      }
      is SqlSpan.Char -> when {
        sql[span.index].isWhitespace() -> Unit
        sql[span.index] == '.' -> {
          // Unquoted identifiers cannot start with an ASCII digit, so such a word before `.` is a
          // numeric literal such as `1.` or `1_000.`. PostgreSQL accepts non-ASCII digits like `٣`
          // as identifier characters.
          precededByQualificationDot = !previousTokenIsDigitLeadingWord
          previousTokenIsDigitLeadingWord = false
        }
        else -> resetWordState()
      }
    }
  }
}
