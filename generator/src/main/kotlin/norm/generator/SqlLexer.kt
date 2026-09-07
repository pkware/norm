package norm.generator

/**
 * Removes every `--` line comment and `/* */` block comment from [text], replacing each with a
 * single space rather than deleting it outright, so two tokens a comment used to separate (e.g.
 * `d\n-- only the active ones\nWHERE`, where [skipLineComment] consumes the comment's own trailing
 * newline along with its text) can't fuse into one run once the comment text is gone.
 * Preserves whitespace, string literals, quoted identifiers, and dollar-quoted strings verbatim.
 */
internal fun stripComments(text: String): String {
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
 * True if [character] can appear inside an unquoted PostgreSQL identifier, at any position after
 * the first: a letter, digit, underscore, dollar sign, or any character whose code is `>= 0x80`.
 *
 * `returning€` is a legal PostgreSQL column name, not the keyword `RETURNING` plus a stray `€`.
 */
internal fun isIdentifierChar(character: Char): Boolean =
  character.isLetterOrDigit() || character == '_' || character == '$' || character.code >= 0x80

/**
 * True if [character] can start an unquoted PostgreSQL identifier: a letter, `_`, or any character
 * whose code is `>= 0x80` — never a digit or `$`, both of which are legal only after the first
 * character (see [isIdentifierChar]).
 *
 * Also the predicate for what can start a dollar-quote tag (the `tag` in `$tag$...$tag$`):
 * PostgreSQL uses the identical character class for both.
 */
internal fun isIdentifierStartChar(character: Char): Boolean =
  character.isLetter() || character == '_' || character.code >= 0x80

/**
 * If [keyword] appears at [position] as a complete word — not adjacent to any character
 * PostgreSQL allows inside an identifier (see [isIdentifierChar]), so it isn't merely a prefix
 * of a longer identifier — advances past it and any trailing whitespace/comments. Otherwise
 * returns [position] unchanged.
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
internal fun skipLineComment(sql: String, start: Int): Int {
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
internal fun skipBlockComment(sql: String, start: Int): Int {
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
 * must treat as an opaque unit — a single-quoted string literal (`E'...'` escape strings,
 * `''`-doubled quotes), a double-quoted identifier (`""`-doubled quotes), a dollar-quoted string
 * (`$$...$$` or `$tag$...$tag$`, but only when the `$` is not itself continuing an identifier),
 * a `--` line comment, or a `/* */` block comment — returns the index immediately after that
 * token. Otherwise returns [position] unchanged, meaning the caller should process this character
 * itself (as a keyword character, a parenthesis, a delimiter, etc.).
 *
 * Every paren-depth or keyword search in this file calls this at each position and jumps ahead
 * when it returns a different index, rather than inspecting `sql[position]` directly — so a `(`
 * or keyword that only appears inside a string, a quoted identifier, or a comment is never
 * misread as a real one (`RETURNING regexp_replace(name, '\(', '')` has an unbalanced `(` inside
 * its string literal).
 *
 * @param adjacency See [OriginalAdjacency]'s KDoc. Defaults to [ALL_ADJACENT], correct for raw SQL
 *   text; [StrippedText] threads itself here for its own [StrippedText.skipLexicalToken] entry
 *   point, gating the checks below on whether stripping actually fused these characters together,
 *   versus PostgreSQL itself having lexed them adjacent.
 * @return The index after the lexical token, or [position] if none starts there.
 */
internal fun skipLexicalToken(sql: String, position: Int, adjacency: OriginalAdjacency = ALL_ADJACENT): Int {
  if (position >= sql.length) return position
  return when {
    sql[position] == '\'' -> skipSingleQuotedString(sql, position, adjacency)
    sql[position] == '"' -> skipDoubleQuotedIdentifier(sql, position, adjacency)
    // A "$" immediately after an identifier character (e.g. the second "$" in "a$b$c") can't open
    // a dollar quote -- it continues the identifier that started before it. Gated on
    // adjacency.wereAdjacent(position - 1): "x $q$...$q$" strips to "x$q$...$q$", where "x" never
    // actually continued into "$q$" in the original query.
    sql[position] == '$' &&
      !(position > 0 && adjacency.wereAdjacent(position - 1) && isIdentifierChar(sql[position - 1])) ->
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
 * digit, or `_` — uses [isIdentifierChar], gated on [adjacency]: a stripped-away separator
 * (e.g. the space in `x€ E'a\'b'`) could otherwise fuse into `E` and manufacture a
 * standalone-`E` escape string match that was never in the query, mis-lexing a valid
 * identifier-then-string-literal (`x E'a\'b'`) as something else entirely.
 *
 * @param adjacency See [OriginalAdjacency]'s KDoc. Gates both the "is the character immediately
 *   before the opening quote genuinely `E`/`e`" check and, when it is, the standalone lookback one
 *   position further back.
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
      // The '' doubled-quote-escape check is gated on adjacency too: if a separator PostgreSQL
      // lexed between two genuinely separate quote characters gets stripped away, fusing them
      // into what looks like a doubled '' escape, treating it as one would overrun past what
      // should have been the first string's real terminator, all the way to sql.length.
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
 * @param adjacency See [OriginalAdjacency]'s KDoc. Gates the `""` doubled-quote-escape check for
 *   consistency with [skipSingleQuotedString]'s own `''` gate — no concrete wrong-answer case has
 *   been found for double-quoted identifiers specifically, but the two scanners should agree.
 * @return The index after the closing quote, or `sql.length` if unterminated.
 */
internal fun skipDoubleQuotedIdentifier(
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
 * True if [character] can continue a dollar-quote tag after its first character: a letter, digit,
 * underscore, or any character whose code is `>= 0x80`. A tag's first character is instead gated
 * by [isIdentifierStartChar], which does not admit digits: a tag may not start with one —
 * PostgreSQL rejects `$1$foo$1$` as a dollar-quoted string entirely, leaving the `$1` to be read
 * as an ordinary, non-quote `$`-prefixed token instead.
 */
private fun isDollarQuoteTagContinuationChar(character: Char): Boolean =
  character.isLetterOrDigit() || character == '_' || character.code >= 0x80

/**
 * `true` if every consecutive pair of characters in `[start, endExclusive)` was genuinely
 * adjacent in the original text, per [adjacency]. Used by [skipDollarQuotedString] to verify its
 * opening delimiter is lexically contiguous, not merely contiguous in a stripped-and-fused string.
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
 * Callers must first confirm [position] is not immediately preceded by an identifier character —
 * this function has no way to tell, from `$` alone, whether it is looking at a genuine
 * dollar-quote opener or the second `$` of an ordinary identifier like `a$b$c`.
 *
 * The opening delimiter is additionally required to be adjacency-contiguous (see
 * [OriginalAdjacency]'s KDoc): every character of it must have been genuinely adjacent to its
 * neighbour in the original text. Without this, stripping can invent a delimiter that was never
 * one lexical unit in the original query — e.g. `$q  b $/ /` strips to `$qb$//`, whose fused
 * `$qb$` would otherwise be read as an opening delimiter with tag `qb`.
 *
 * @param adjacency See [OriginalAdjacency]'s KDoc. Defaults to [ALL_ADJACENT], correct for raw SQL
 *   text.
 * @return The index after the closing tag (or `sql.length` if unterminated), or `null` if
 *   [position] is a `$` that is not followed by a valid closing tag delimiter at all (e.g. a
 *   bare `$` used as an operator, or a positional parameter marker like `$1` with no matching
 *   second `$`), or if the opening delimiter itself is not adjacency-contiguous.
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
 * Collapses the cosmetic whitespace [stripComments]' own single-space substitution can leave behind
 * in an expression about to be embedded verbatim in generated KDoc — a comment directly after an
 * opening parenthesis or before a closing one (`UPPER(/* x */a)` strips to `UPPER( a)`) reads oddly
 * there. Applied only where an expression is resolved for KDoc, never inside [stripComments] itself.
 *
 * Collapses whitespace outside a single-quoted literal, a dollar-quoted string, a quoted identifier,
 * or a comment to a single space, then removes a single such space immediately after `(` or before
 * `)` — never semantically significant in SQL.
 *
 * Walks every span verbatim via [skipLexicalToken] rather than a whitespace-collapse regex, which
 * can't tell a cosmetic space from one inside the developer's own SQL and would rewrite a quoted
 * identifier's internal spacing (`"My  Col"` to `"My Col"`, a column name PostgreSQL then rejects)
 * or a string literal's contents (`'( x )'` to `'(x)'`).
 */
internal fun collapseCosmeticWhitespace(text: String): String {
  val trimmed = text.trim()
  val builder = StringBuilder(trimmed.length)
  var i = 0
  while (i < trimmed.length) {
    val afterToken = skipLexicalToken(trimmed, i)
    if (afterToken != i) {
      builder.append(trimmed, i, afterToken)
      i = afterToken
      continue
    }
    val character = trimmed[i]
    if (!character.isWhitespace()) {
      builder.append(character)
      i++
      continue
    }
    var afterWhitespace = i
    while (afterWhitespace < trimmed.length && trimmed[afterWhitespace].isWhitespace()) afterWhitespace++
    val precededByOpenParenthesis = builder.isNotEmpty() && builder.last() == '('
    val followedByCloseParenthesis = afterWhitespace < trimmed.length && trimmed[afterWhitespace] == ')'
    if (!precededByOpenParenthesis && !followedByCloseParenthesis) builder.append(' ')
    i = afterWhitespace
  }
  return builder.toString()
}
