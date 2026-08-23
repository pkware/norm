package norm.generator

/**
 * Removes every `--` line comment and `/* */` block comment from [text], replacing each one with a
 * SINGLE space rather than deleting it outright — so that two tokens a comment used to separate
 * (the far more ordinary `d\n-- only the active ones\nWHERE`, where the line comment's own
 * trailing newline is what [skipLineComment] consumes along with the comment text itself) can
 * never fuse into one run once the comment text is gone. Preserves everything else — including
 * ordinary whitespace, string literals, quoted identifiers, and dollar-quoted strings — verbatim.
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
 * PostgreSQL's actual identifier-continuation class (`scan.l`'s `ident_cont`) is wider than a
 * plain letter/digit/`_`/`$` check: a combining mark (an alias written in NFD), a currency or
 * other symbol (`€`, `©`, `¹`, `・`), and a supplementary-plane character (a surrogate pair in a
 * Kotlin/UTF-16 `String` — both code units are `>= 0x80`, so no special surrogate handling is
 * needed) are all legal identifier characters that Kotlin's `isLetterOrDigit()` alone does not
 * recognize (none of them is a Unicode letter or digit).
 *
 * This is the SINGLE predicate every keyword word-boundary check in this file uses
 * ([findTopLevelKeyword], [findTopLevelReturningKeyword],
 * [matchUnicodeEscapeIdentifierSegment]'s `UESCAPE` boundary, [skipOptionalKeyword],
 * [extractAlias]'s `AS` boundary, and [skipLexicalToken]'s
 * dollar-quote guard), as well as [matchTrailingAliasSegment] (an implicit alias's own
 * continuation characters), [isStarQualifierAcceptable] (scanning the identifier run before a
 * star's qualifying `.`), [parseSingleCteDefinition] (a CTE name's characters AFTER the first —
 * see [isIdentifierStartChar] for the first character itself), and [COLUMN_REFERENCE]'s
 * continuation character class — deliberately ONE predicate everywhere: a real PostgreSQL keyword
 * can never legitimately abut a `>= 0x80` character outside a string literal, a quoted identifier,
 * a dollar-quoted string, or a comment (all already skipped by [skipLexicalToken]), so a keyword
 * boundary check and an identifier-run scan must always agree on where an identifier ends —
 * otherwise a PostgreSQL-legal `>= 0x80` character can truncate a run or a word at the wrong place
 * (e.g. `returning€`, a legal column name, misread as the bare keyword `RETURNING` plus a stray
 * `€`, or `x€9.`, where stopping at `€` instead of continuing through it leaves `9` looking like a
 * qualifier's own start and misclassifies the whole thing as a numeric literal).
 */
internal fun isIdentifierChar(character: Char): Boolean =
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
 * [COLUMN_REFERENCE] and [FUNCTION_CALL_START] to embed — deliberately kept separate from
 * [isIdentifierChar] (the CONTINUATION predicate), since using it for a CTE name's first character
 * would wrongly accept a `$`-led name (`WITH $x AS (...)`, which PostgreSQL rejects outright).
 */
internal fun isIdentifierStartChar(character: Char): Boolean =
  character.isLetter() || character == '_' || character.code >= 0x80

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
 * digit, or `_` — uses [isIdentifierChar], the SAME predicate every other word-boundary check in
 * this file uses, GATED on [adjacency] (see [OriginalAdjacency]'s KDoc): [isStarItem] normalizes a
 * select item through [stripCommentsAndWhitespace] and then RE-LEXES the stripped string, so a
 * lookback using the full [isIdentifierChar] class (which admits `$` and any `>= 0x80` character,
 * both legal PostgreSQL identifier-continuation characters that a narrower letter/digit/`_` check
 * would miss) must be gated on whether the character immediately before `E`/`e` was genuinely
 * adjacent in the ORIGINAL text — otherwise a separator stripping removed (e.g. the space in
 * `x€ E'a\'b'`) could fuse into `E` and manufacture a standalone-`E` escape string match that was
 * never in the query, mis-lexing a valid typed-literal call (`x E'a\'b'`, PostgreSQL's
 * `AexprConst: func_name Sconst` form) as something else entirely.
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
