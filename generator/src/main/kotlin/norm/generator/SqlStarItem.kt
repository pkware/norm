package norm.generator

/**
 * Removes every comment and all whitespace from [text], keeping every other character — including
 * the full contents of a string literal, quoted identifier, or dollar-quoted string — verbatim and
 * in relative order. A comment is removed outright, wherever it sits, including one between two
 * otherwise-adjacent tokens (`tgt./*c*/ *`), not merely one that leads or trails the whole string.
 *
 * Returns a [StrippedText], not a plain `String`, because deleting a separator PostgreSQL itself
 * lexed on can fuse two characters that were never adjacent into a token that never existed:
 * `1 - -1` (two independently-lexed `-` tokens) strips to `1--1`, which a naive re-lex of the
 * output `String` alone would read as a `--` line comment. [StrippedText] carries each stripped
 * character's original offset so a later adjacency decision can be gated on whether two characters
 * were really adjacent in the source text.
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
 * The output of [stripCommentsAndWhitespace]: the stripped characters, plus each one's original
 * offset in the pre-stripping text, so [wereAdjacent] can answer whether two stripped characters
 * that now sit next to each other in [text] were genuinely adjacent before stripping, or had a
 * whitespace/comment separator between them.
 *
 * The raw stripped `String` is kept private: every lexer function stripped-path code needs
 * ([skipLexicalToken], [findMatchingCloseParenthesis]) is exposed as a member here that threads
 * `this` as the [OriginalAdjacency], so stripped-path code cannot bypass the adjacency gate.
 * [asPlainString] is the one escape hatch, for a caller that does no lexing and so has no
 * adjacency decision to make.
 */
internal class StrippedText(private val text: String, private val originalOffsets: IntArray) : OriginalAdjacency {

  val length: Int get() = text.length

  operator fun get(index: Int): Char = text[index]

  override fun wereAdjacent(leftIndex: Int): Boolean = leftIndex >= 0 &&
    leftIndex + 1 < originalOffsets.size &&
    originalOffsets[leftIndex + 1] == originalOffsets[leftIndex] + 1

  /** `true` if the stripped text is exactly [other] — the whole-string equivalent of `==`. */
  fun contentEquals(other: String): Boolean = text == other

  fun endsWith(suffix: String): Boolean = text.endsWith(suffix)

  fun regionMatchesIgnoreCase(position: Int, other: String): Boolean =
    text.regionMatches(position, other, 0, other.length, ignoreCase = true)

  /** A new [StrippedText] over `[from, until)`, slicing both the stripped text and its offset map. */
  fun slice(from: Int, until: Int): StrippedText =
    StrippedText(text.substring(from, until), originalOffsets.copyOfRange(from, until))

  /** The original (pre-stripping) index that stripped index [strippedIndex] came from. */
  fun originalIndexOf(strippedIndex: Int): Int = originalOffsets[strippedIndex]

  fun skipLexicalToken(position: Int): Int = norm.generator.skipLexicalToken(text, position, this)

  fun findMatchingCloseParenthesis(openParenthesisIndex: Int): Int =
    norm.generator.findMatchingCloseParenthesis(text, openParenthesisIndex, this)

  /**
   * The escape hatch out of this class's lexer entry points, back to a plain `String`. Safe only
   * for a caller that does no lexing and so has no adjacency decision to gate.
   */
  fun asPlainString(): String = text
}

/**
 * Whether a single `RETURNING`/`SELECT` item [item] is a star (`*`, `tbl.*`), with or without an
 * implicit (no-`AS`) alias — including parenthesized (`(tgt.*)`) and with any placement of
 * comments and whitespace around or between its tokens (`tgt.* /*c*/`, `tgt . *`, `tgt./*c*/ *`,
 * `tgt.*whatever`).
 *
 * Two paths, tried in order:
 *
 * Path 1 matches every shape whose text (comments and whitespace stripped, wrapping parentheses
 * removed) ends in `.*` verbatim: `t.*`, a parenthesized composite expansion (`(t).*`, `(u.*)`,
 * `((t.*))`), and a Unicode-escape identifier (`U&"my*table".*`).
 *
 * Path 2 is reached when path 1 answers `false`, for an item with a trailing implicit alias.
 * [findTrailingImplicitAliasStart] locates where the alias starts; with it removed, path 1's check
 * is re-run on the remaining prefix, additionally requiring [isStarQualifierAcceptable] to accept
 * the qualifier when the prefix ends in `.*` — needed because a digit run immediately before the
 * dot (`2.` in `SELECT 2.*3 lbl, a FROM t`, which returns 2 columns: arithmetic, not a star) is the
 * one shape a real qualifying dot can be confused with.
 *
 * On PostgreSQL 18.4, `SELECT u.*whatever, preferences FROM users u` returns 5 columns (star
 * expands, implicit alias ignored): an alias directly abutting the star, with no separator at all,
 * must still be recognized as an implicit alias.
 *
 * Not claimed exhaustive: [parseSelectItems] has no independent real-column-count to check this
 * function's answer against, so an item shape this function fails to recognize degrades silently
 * to a wrong, shifted mapping rather than a fail-safe.
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
 * Repeatedly strips a wrapping `(...)` from [text] — via
 * [StrippedText.findMatchingCloseParenthesis], so it is a genuine matching pair, not merely the first
 * and last characters happening to be `(` and `)` — until none remains: `((t.*))` unwraps in two
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
 * An implicit alias exists only if the last segment found (see [matchTrailingAliasSegment] for
 * what counts as one) ends exactly at `text.length` and starts at an index greater than `0` — a
 * segment spanning the entire text is just one bare identifier with no star in it at all (e.g.
 * `preferencesprefs`), not a qualifier plus an alias.
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
 * The expression/alias split of an item with a trailing implicit (no-`AS`) alias — e.g.
 * `UPPER(a) y` splits into expression `UPPER(a)` and alias `y` — or `null` if [item] has no such
 * trailing alias (including a bare column reference like `description`, one segment spanning the
 * whole item).
 *
 * The returned [ItemAndImplicitAlias.expression] is sliced out of [item] itself, original
 * formatting (including any comment) intact.
 *
 * @param item The full item text — expression and any trailing implicit alias together, with no
 *   `AS` keyword already found and split off.
 */
internal fun splitTrailingImplicitAlias(item: String): ItemAndImplicitAlias? {
  val stripped = stripCommentsAndWhitespace(item)
  val aliasStart = findTrailingImplicitAliasStart(stripped) ?: return null
  val alias = stripped.asPlainString().substring(aliasStart)
  val originalAliasStart = stripped.originalIndexOf(aliasStart)
  return ItemAndImplicitAlias(item.substring(0, originalAliasStart).trim(), alias)
}

/**
 * @property expression [splitTrailingImplicitAlias]'s own item text with the trailing implicit
 *   alias removed, original formatting otherwise intact.
 * @property alias The trailing implicit alias token itself.
 */
internal data class ItemAndImplicitAlias(val expression: String, val alias: String)

/**
 * Matches one segment starting at [start] in [text], in this precedence order:
 * 1. A Unicode-escape identifier — `U&`/`u&` immediately followed by a double-quoted identifier,
 *    optionally extended through a `UESCAPE '<char>'` clause (see
 *    [matchUnicodeEscapeIdentifierSegment]). Tried first: for `u.*U&"a"`, matching the bare
 *    identifier rule (3, below) first would consume `U` alone as a segment, then `"a"` as a
 *    separate later segment, leaving a dangling `U&` attached to the prefix and hiding the star.
 * 2. A bare double-quoted identifier, `"..."` (`""`-doubling included).
 * 3. An unquoted identifier: first character [isIdentifierStartChar], every subsequent character
 *    [isIdentifierChar] — PostgreSQL identifiers may not start with a digit or `$`.
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
  // PostgreSQL's lexer admits any byte >= 0x80 to start an unquoted identifier, not merely a
  // Unicode `isLetter()`: a combining mark (an alias written in NFD, e.g. "préfs" spelled
  // p-r-e-COMBINING_ACUTE-f-s), a currency sign (`€`), and a supplementary-plane character (an
  // astral emoji, a mathematical alphanumeric symbol like `𝐀`) are all legal first characters that
  // `isLetter()` does not recognize as letters. A surrogate pair is covered without special
  // handling, since both of its code units are >= 0x80.
  if (!isIdentifierStartChar(text[start])) return null
  var i = start + 1
  while (i < text.length && isIdentifierChar(text[i]) && text.wereAdjacent(i - 1)) {
    // Gated on adjacency for every continuation character, not just "$": stripping deletes the
    // separator between two independently-lexed identifiers, so without this gate the run would
    // fuse both into one segment spanning the whole text and no alias would be found at all. A
    // non-adjacent "$" is handed back to [StrippedText.skipLexicalToken], which recognizes a
    // dollar-quoted string as one opaque token.
    i++
  }
  return i
}

/**
 * Matches a Unicode-escape identifier — `U&`/`u&` immediately followed by a double-quoted
 * identifier, e.g. `U&"my*table"` — starting at [start] in [text], optionally extended by a
 * `UESCAPE '<char>'` clause naming a custom escape character. PostgreSQL merges the identifier,
 * the `UESCAPE` keyword, and the single-quoted escape-character string into one lexical unit:
 * `U&"d!0061t" UESCAPE '!'` and `U&"!0074" UESCAPE '!'` are each a single identifier, both
 * resolving via the `!`-escape to the same characters `U&"data"`/`U&"t"` would spell without one.
 *
 * The `UESCAPE` keyword's adjacency to the identifier before it is left ungated: PostgreSQL itself
 * permits whitespace there (`U&"!0074" UESCAPE '!'` and `U&"!0074"UESCAPE'!'` both resolve
 * identically), so stripping that whitespace does not manufacture a token PostgreSQL didn't
 * already treat as one unit.
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
 * [isStarItem], guaranteed by its caller to end in `.`) is acceptable.
 *
 * Rejects only when the run of [isIdentifierChar] characters immediately preceding the final `.`
 * is non-empty and its first character is an ASCII digit (`'0'..'9'`, not `Char.isDigit()`) — a
 * digit-leading run before a dot is a numeric literal (`2.` in `SELECT 2.*3 lbl, a FROM t`, which
 * returns 2 columns: arithmetic, not a star). `Char.isDigit()` is Unicode-aware and would wrongly
 * reject a qualifier starting with a non-ASCII digit (Unicode category Nd) as if it were numeric —
 * with a table literally named `٣` (ARABIC-INDIC DIGIT THREE), `SELECT ٣.* x, a FROM ٣` returns
 * 3 columns.
 *
 * The run scan uses [isIdentifierChar] rather than a narrower letter-or-digit-only check: a
 * `>= 0x80` character that is not a letter or digit (`€`) would otherwise truncate the run early,
 * making an ASCII digit before it look like the run's own start: scanning `x€9.` backward with a
 * letter-or-digit-only check stops at `€`, so `9` looks like the run's start and the qualifier is
 * wrongly rejected as numeric, when the real run is `x€9` — letter-led, and acceptable.
 *
 * Accepts in every other case, including an empty run (the character immediately before the dot is
 * `"`, `)`, `]`, or a Unicode-escape's closing `'`, as in `U&"!0074" UESCAPE '!'.`).
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
