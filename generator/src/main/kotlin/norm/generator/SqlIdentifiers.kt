package norm.generator

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
 * truncated before it ever reaches this function, a known gap unrelated to and unfixed by this
 * function's own escape handling.
 *
 * A [SelectItem.tableName]/[SelectItem.columnName], in CONTRAST, is a LOGICAL value with its
 * quotes already removed by [parseColumnReference] (see [SelectItem]'s own KDoc) — passing one of
 * those through THIS overload would always take the unquoted, folded branch regardless of
 * whether the source was actually quoted, silently discarding exactly the distinction PostgreSQL
 * folding depends on. Use the two-argument overload below for those instead, passing
 * [SelectItem.isColumnNameQuoted]/[SelectItem.isTableNameQuoted] explicitly.
 */
internal fun foldIdentifier(rawIdentifier: String): String {
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
internal fun foldIdentifier(logicalValue: String, isQuoted: Boolean): String =
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
internal fun foldAsciiCase(text: String): String {
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
internal fun isQuotedIdentifier(rawIdentifier: String): Boolean {
  val trimmed = rawIdentifier.trim()
  return trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')
}

/**
 * The character class an unquoted PostgreSQL identifier's FIRST character may be — the same rule
 * [isIdentifierStartChar] checks, expressed as a regex character class: a letter, `_`, or any
 * character whose code is `>= 0x80` (see [matchTrailingAliasSegment], which enforces the same rule
 * for an implicit alias's own first character) — but NOT a digit or `$`, both of which are legal
 * only after the first character (see [isIdentifierChar]).
 */
internal const val COLUMN_REFERENCE_IDENTIFIER_START = """[\p{L}_\x{80}-\x{10FFFF}]"""

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
internal const val COLUMN_REFERENCE_IDENTIFIER_CONTINUATION = """[\p{L}\p{Nd}_$\x{80}-\x{10FFFF}]"""

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
internal val QUOTED_IDENTIFIER_PATTERN = Regex(QUOTED_IDENTIFIER)

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
internal val COLUMN_REFERENCE = Regex(
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
internal fun unescapeQuotedIdentifier(rawQuotedToken: String): String =
  rawQuotedToken.substring(1, rawQuotedToken.length - 1).replace("\"\"", "\"")
