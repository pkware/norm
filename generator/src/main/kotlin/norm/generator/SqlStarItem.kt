package norm.generator

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

  /**
   * The ORIGINAL (pre-stripping) index that stripped index [strippedIndex] came from — used by
   * [splitTrailingImplicitAlias] to translate a boundary located in stripped space (comments and
   * whitespace already removed) back into the ORIGINAL text, so the expression half of the split
   * can be sliced out of the caller's own, un-stripped item text, comments and all, rather than the
   * stripped copy this class holds privately.
   */
  fun originalIndexOf(strippedIndex: Int): Int = originalOffsets[strippedIndex]

  /** See [skipLexicalToken]'s KDoc — this threads `this` as the [OriginalAdjacency]. */
  fun skipLexicalToken(position: Int): Int = norm.generator.skipLexicalToken(text, position, this)

  /** See [findMatchingCloseParenthesis]'s KDoc — this threads `this` as the [OriginalAdjacency]. */
  fun findMatchingCloseParenthesis(openParenthesisIndex: Int): Int =
    norm.generator.findMatchingCloseParenthesis(text, openParenthesisIndex, this)

  /**
   * The single, deliberately named escape hatch out of this class's own lexer entry points, back
   * to a plain `String` — see this class's own KDoc for why every OTHER accessor exists instead of
   * this one. Safe only for a caller that does no lexing at all, i.e. has no adjacency decision to
   * gate; [isStarQualifierAcceptable] (inspecting the character class of a qualifier's trailing
   * run) and [splitTrailingImplicitAlias] (extracting an already-located trailing alias segment's
   * own text, which needs no further lexical stepping once found) are the only current callers.
   */
  fun asPlainString(): String = text
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
 * PATH 1 — the `text == "*" || text.endsWith(".*")` check (via [unwrapWrappingParentheses] then a
 * literal suffix comparison). For [isStarItem], `false` is the DANGEROUS answer (an unrecognized
 * star lets a later item survive at its raw list position, silently shifted onto the wrong
 * `ResultSetMetaData` column) and `true` is the SAFE one (later items are dropped, falling back to
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
 * prefix, a Unicode-escape identifier...), which is fragile against a qualifier shape not yet on
 * the list. With the alias located, [unwrapWrappingParentheses] is applied to the PREFIX (the text
 * with that alias
 * removed), and the SAME path-1 logic is re-run on it: accept if the unwrapped prefix is `*`, or if
 * it ends in `.*` AND [isStarQualifierAcceptable] accepts the qualifier (everything before that
 * final `.`) — the ONLY additional check path 2 needs beyond path 1's, since a digit-leading run
 * immediately before the dot (`2.` in `SELECT 2.*3 lbl, a FROM t` — verified arithmetic returning 2
 * columns, not a star) is the ONE lexical ambiguity a numeric literal creates with a real
 * qualifying dot; see [isStarQualifierAcceptable]'s KDoc for why every other qualifier shape is
 * accepted rather than enumerated.
 *
 * Verified against PostgreSQL 18.4: `SELECT u.*whatever, preferences FROM users u` and `SELECT
 * u.*`/*c*/`whatever, preferences FROM users u` both return 5 columns (star expands, implicit
 * alias ignored) — an alias directly ABUTTING the star (no separator at all, comment or otherwise)
 * must still be recognized as an implicit alias, not just one separated by whitespace or a
 * comment. `SELECT *whatever, a FROM t` (a BARE star with an abutting alias) is, by contrast, a
 * genuine PostgreSQL syntax error — a bare `*` cannot itself take an alias — so this function's
 * willingness to call it a star for an empty qualifier is unreachable on real input, not a gap
 * that needs closing.
 *
 * NOT claimed exhaustive: [parseSelectItems] has no independent real-column-count to cross-check
 * against at the point it runs, so a spelling this function fails to recognize there degrades
 * silently to a wrong, shifted mapping rather than a fail-safe.
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
 * The expression/alias split of an item with a trailing implicit (no-`AS`) alias — e.g.
 * `UPPER(a) y` splits into expression `UPPER(a)` and alias `y` — or `null` if [item] has no such
 * trailing alias at all, generalizing [findTrailingImplicitAliasStart] (the same detection
 * [isStarItem] uses for a star's own trailing alias) beyond star items to any item's text.
 *
 * That function already answers only for a trailing segment that is the item's FINAL token and
 * does NOT span the entire item, so a bare column reference (`description` — one segment covering
 * the whole text) correctly returns `null` here, not itself, matching this function's OWN "no
 * implicit alias" contract for that shape.
 *
 * [item] is stripped of comments/whitespace only to LOCATE the split point ([findTrailingImplicitAliasStart]
 * needs that normalized form) — the returned [ItemAndImplicitAlias.expression] is sliced out of
 * [item] itself, ORIGINAL formatting (including any comment [stripComments] must still remove
 * downstream) intact, via [StrippedText.originalIndexOf] translating the stripped split point back
 * to [item]'s own indices.
 *
 * @param item The full item text — expression and any trailing implicit alias together, with no
 *   `AS` keyword having already been found and split off (an item with an explicit `AS` alias is
 *   never passed here; its alias is [extractAlias]'s own, unrelated concern).
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
  while (i < text.length && isIdentifierChar(text[i]) && text.wereAdjacent(i - 1)) {
    // Gated on adjacency for EVERY continuation character, not merely "$" (see below): stripping
    // deletes the whitespace/comment that used to separate two INDEPENDENT identifiers PostgreSQL
    // itself lexed apart — "description dx" (a bare column plus its own implicit alias) strips to
    // "descriptiondx", and without this gate every character of that is `isIdentifierChar`, so the
    // run swallows BOTH tokens into one fused "segment" spanning the whole text. That trips
    // findTrailingImplicitAliasStart's own "a segment spanning the ENTIRE text is not an alias"
    // guard, so no alias is found at all (#238) — the correct answer, expression "description",
    // alias "dx", is never even computed. Stopping the run the moment a continuation character was
    // NOT genuinely adjacent to the one before it correctly ends the FIRST identifier's segment at
    // the real original token boundary, letting the walk in [findTrailingImplicitAliasStart] pick
    // the SECOND identifier up as its own, later segment instead.
    //
    // "$" needs no separate case any more: it is merely the one isIdentifierChar character that can
    // ALSO open an entirely different, opaque lexical token — a dollar-quoted string (see
    // skipLexicalToken's own dollar-quote guard) — rather than merely continuing this identifier,
    // and the general gate above already stops the run before a non-adjacent "$" is consumed,
    // handing that position back to findTrailingImplicitAliasStart's own
    // [StrippedText.skipLexicalToken] fallback, which correctly recognizes the dollar-quoted string
    // as one opaque, non-segment token and walks over it whole.
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
 * [isStarItem], guaranteed by its caller to end in `.`) is acceptable. Deliberately NOT an
 * enumeration of every character that may legitimately precede the dot (`"`, `)`, `]`, an
 * identifier run, a `DISTINCT ON (...)` prefix, a parenthesized composite expansion, an array
 * subscript, a Unicode-escape identifier with a `UESCAPE` clause...): `false` is the DANGEROUS
 * answer here (see [isStarItem]'s KDoc), so it must be EARNED by an actual disqualifying shape,
 * not handed out by default whenever a new qualifier shape isn't yet on an enumerated list.
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
 * predicate makes that symmetry STRUCTURAL rather than a comment to remember to keep in sync.
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
