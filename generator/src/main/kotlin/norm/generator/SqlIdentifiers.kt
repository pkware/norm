package norm.generator

/**
 * Folds a raw identifier the way PostgreSQL folds an identifier reference for comparison: an
 * unquoted identifier folds via [foldAsciiCase] (PostgreSQL's own default case-folding —
 * deliberately not Kotlin's `String.lowercase()`, see that function's KDoc); a quoted identifier
 * (`"..."`) compares exactly, with the surrounding quotes removed and any doubled `""` escape
 * collapsed to the literal `"` it represents, via [unescapeQuotedIdentifier] — never folded,
 * since quoting is how PostgreSQL preserves a name's original case. Skipping the unescape step
 * would repeat the [parseAliasToken] truncation bug: two different quoted names sharing the same
 * text up to their first internal `"` (e.g. `"zz""q"` and a lone `"zz"`) would wrongly fold to
 * the same value.
 *
 * [rawIdentifier] must be exactly what was written in the source SQL, quotes and all where
 * present — [extractAlias]'s alias text (via the escape-aware [parseAliasToken]) and
 * [CteDefinition.rawName] are the two callers. [CteDefinition.rawName] (never
 * [CteDefinition.name], whose quotes are already stripped and so can no longer distinguish
 * quoted `"Foo"` from unquoted `foo`, a different relation in PostgreSQL) reads via the same
 * escape-aware [QUOTED_IDENTIFIER_PATTERN] [parseAliasToken] uses, so an escaped CTE name like
 * `"He""llo"` round-trips intact.
 *
 * A [SelectItem.tableName]/[SelectItem.columnName] is, in contrast, a logical value with its
 * quotes already removed by [parseColumnReference] — passing one through this overload would
 * always take the unquoted, folded branch regardless of whether the source was quoted. Use the
 * two-argument overload below for those, passing
 * [SelectItem.isColumnNameQuoted]/[SelectItem.isTableNameQuoted] explicitly.
 */
internal fun foldIdentifier(rawIdentifier: String): String {
  val trimmed = rawIdentifier.trim()
  return if (isQuotedIdentifier(trimmed)) unescapeQuotedIdentifier(trimmed) else foldAsciiCase(trimmed)
}

/**
 * Folds a logical identifier value (quotes already removed, any doubled `""` escape already
 * collapsed) the way PostgreSQL folds an identifier reference for comparison, using [isQuoted]
 * (captured separately at parse time, since [logicalValue] no longer carries the quotes that
 * would otherwise signal which rule applies): a quoted reference compares exactly (returned
 * unchanged); an unquoted one folds via [foldAsciiCase] — deliberately not `String.lowercase()`,
 * see that function's KDoc for why.
 *
 * This is the overload [SelectItem.columnName]/[SelectItem.tableName] must fold through — see
 * the single-argument overload's KDoc for why passing a logical value there instead would
 * silently discard the quoted/unquoted distinction.
 */
internal fun foldIdentifier(logicalValue: String, isQuoted: Boolean): String =
  if (isQuoted) logicalValue else foldAsciiCase(logicalValue)

/**
 * Folds only the ASCII letters `A`-`Z` to lowercase, leaving every other character — including
 * any non-ASCII letter — untouched. Both [foldIdentifier] overloads fold an unquoted identifier
 * through this function, never through Kotlin's `String.lowercase()`, which applies full Unicode
 * case mapping instead.
 *
 * PostgreSQL's own case-folding for an unquoted identifier (`downcase_identifier` in `scan.l`)
 * folds only plain ASCII `A`-`Z`, never a non-ASCII letter, even one with an obvious upper/lower
 * pairing — on PostgreSQL 18.4, with a column named `"ü"` (quoted, lowercase), the bare,
 * unquoted reference `SELECT Ü FROM t` fails outright (`column "Ü" does not exist`); PostgreSQL
 * does not fold `Ü` down to `ü` the way it folds plain ASCII `A` to `a`. The same bare `Ü`
 * instead resolves against a column actually named `"Ü"` (quoted, uppercase). Using
 * `String.lowercase()` here would fold `Ü` to `ü` and make this file disagree with PostgreSQL
 * about which of two differently-cased, non-ASCII-named aliases an unquoted outer reference
 * targets — the cross-match mistake [foldIdentifier] exists to prevent. Do not "fix" this back
 * to `String.lowercase()`; the ASCII-only behavior is PostgreSQL parity, not an oversight.
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
 * The character class an unquoted PostgreSQL identifier's first character may be — the same rule
 * [isIdentifierStartChar] checks, expressed as a regex character class: a letter, `_`, or any
 * character whose code is `>= 0x80` (see [matchTrailingAliasSegment], which enforces the same
 * rule for an implicit alias's own first character) — never a digit or `$`, both legal only after
 * the first character (see [isIdentifierChar]).
 */
internal const val COLUMN_REFERENCE_IDENTIFIER_START = """[\p{L}_\x{80}-\x{10FFFF}]"""

/**
 * The character class an unquoted PostgreSQL identifier's characters after the first may be —
 * the same class [isIdentifierChar] checks, expressed as a regex character class: a Unicode
 * letter (`\p{L}`, matching [Char.isLetter]) or decimal digit (`\p{Nd}`, matching [Char.isDigit]),
 * `_`, `$`, or any character whose code is `>= 0x80` (`\x{80}-\x{10FFFF}`, a code-point range, not
 * a per-`Char` one — this is what lets it match a supplementary-plane character written as a
 * surrogate pair in a Kotlin `String`: `Regex("[\\x{80}-\\x{10FFFF}]").matches` on a single
 * surrogate-pair string returns `true`, consuming both UTF-16 code units as the one code point
 * they represent, rather than needing the range repeated for each half).
 */
internal const val COLUMN_REFERENCE_IDENTIFIER_CONTINUATION = """[\p{L}\p{Nd}_$\x{80}-\x{10FFFF}]"""

private const val COLUMN_REFERENCE_IDENTIFIER =
  """$COLUMN_REFERENCE_IDENTIFIER_START$COLUMN_REFERENCE_IDENTIFIER_CONTINUATION*"""

/**
 * Matches a double-quoted PostgreSQL identifier, quotes included — `"` followed by any number of
 * (a non-`"` character) or (a doubled `""`, PostgreSQL's escape for a literal `"` inside the
 * name), followed by the closing `"`. Not unescaped by this pattern itself — that is
 * [unescapeQuotedIdentifier]'s job, once a caller has the matched raw token (quotes and any
 * doubled escapes still intact) in hand.
 *
 * A separate constant from [COLUMN_REFERENCE_IDENTIFIER] (never merged into it): unlike an
 * unquoted identifier, a quoted one is legal only as a `table`/`column` position in
 * [COLUMN_REFERENCE] — never as a bare function/type name (see [FUNCTION_CALL_START], which
 * still uses [COLUMN_REFERENCE_IDENTIFIER_START]/[COLUMN_REFERENCE_IDENTIFIER_CONTINUATION]
 * directly and is unaffected by this constant).
 */
private const val QUOTED_IDENTIFIER = "\"(?:[^\"]|\"\")*\""

/**
 * [QUOTED_IDENTIFIER] compiled once, for callers that need to find/match a quoted identifier
 * token starting at a known position within a larger string — [Regex.matchAt] — rather than
 * matching an entire already-isolated string the way [COLUMN_REFERENCE] does via
 * [Regex.matchEntire]. [parseAliasToken] is the one caller: it needs the escape-aware end of a
 * quoted alias token starting at a specific index, the same escape handling [COLUMN_REFERENCE]
 * already applies via [COLUMN_REFERENCE_IDENTIFIER_OR_QUOTED] — sharing this one compiled
 * pattern, rather than writing a second, hand-rolled quote scanner, keeps that handling from
 * drifting out of sync between the two call sites.
 */
internal val QUOTED_IDENTIFIER_PATTERN = Regex(QUOTED_IDENTIFIER)

/**
 * Matches either an unquoted [COLUMN_REFERENCE_IDENTIFIER] or a [QUOTED_IDENTIFIER] — the shape
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
 * [isIdentifierChar]) but its first character is narrower (`\w+` doesn't enforce that a digit or
 * `$` may only appear after the first character)) or a double-quoted one ([QUOTED_IDENTIFIER] —
 * `"ux"`, `"My Col"`, `"He""llo"`), matched via [COLUMN_REFERENCE_IDENTIFIER_OR_QUOTED] for each
 * position independently.
 *
 * The leading-character restriction on the unquoted alternative matters in the widening direction
 * specifically: without it, a digit- or `$`-led fragment merely followed by
 * identifier-continuation characters — e.g. `2€`, which PostgreSQL itself rejects outright
 * ("trailing junk after numeric literal", on PostgreSQL 18.4) — would [Regex.matchEntire] as a
 * whole "identifier" once the continuation class widens to admit `€`, handing back a `columnName`
 * PostgreSQL would never resolve to that name. [parseColumnReference] returning `null` for
 * anything that isn't a real identifier is the safe, intended outcome (see [parseSelectItems]'s
 * KDoc on why a lost name degrades safely to `ResultSetMetaData` while a wrong one does not).
 *
 * A matched group's captured text still includes its surrounding quotes (if any) — the whole raw
 * token, exactly as [QUOTED_IDENTIFIER] defines it — since [Regex] group captures always span
 * whatever the sub-pattern matched; [parseColumnReference] turns that raw capture into the
 * logical value [SelectItem.columnName]/[SelectItem.tableName] actually store, via
 * [unescapeQuotedIdentifier].
 */
internal val COLUMN_REFERENCE = Regex(
  """(?:(?<table>$COLUMN_REFERENCE_IDENTIFIER_OR_QUOTED)\.)?(?<column>$COLUMN_REFERENCE_IDENTIFIER_OR_QUOTED)""",
)

/**
 * Converts a raw double-quoted identifier token — including its surrounding quotes, exactly as
 * [QUOTED_IDENTIFIER] matches it — into PostgreSQL's logical identifier value: the surrounding
 * quotes removed, and each doubled `""` escape collapsed to the single literal `"` it represents.
 * On PostgreSQL 18, `ResultSetMetaData.getColumnName` for `SELECT "He""llo" FROM (SELECT 1 AS
 * "He""llo") s` reports `He"llo` (no quotes, escape already collapsed) — exactly what this
 * function produces from the raw token `"He""llo"`.
 *
 * [rawQuotedToken] must be the exact matched text of a [QUOTED_IDENTIFIER] — starting and ending
 * with `"`, with at least those two characters present. Passing anything else is a caller bug,
 * not a value this function attempts to handle gracefully.
 */
internal fun unescapeQuotedIdentifier(rawQuotedToken: String): String =
  rawQuotedToken.substring(1, rawQuotedToken.length - 1).replace("\"\"", "\"")

/** PostgreSQL's `NAMEDATALEN - 1`: the byte length an identifier is truncated to by the server. */
internal const val MAX_IDENTIFIER_LENGTH_BYTES = 63

/**
 * Truncates [identifier] the way PostgreSQL does when it reaches the server
 * (`downcase_truncate_identifier` in `scan.l`): to the longest prefix of at most
 * [MAX_IDENTIFIER_LENGTH_BYTES] UTF-8 bytes, dropping whole characters rather than splitting one.
 * A 32-character name of `é` is 64 bytes and truncates to 31 characters, not to a broken 63rd byte.
 *
 * The server applies this to references as well as declarations, so `SELECT <70-char name>`
 * resolves against a column created with that same name — both sides were already truncated to the
 * same 63 bytes before being compared. Norm has to do the same to any name it reads out of SQL text
 * or build configuration, since everything it reads from the server arrives truncated already.
 *
 * [identifier] must be the logical value, with quotes and any `""` escape already resolved; this
 * only measures bytes and has no opinion on quoting. Truncating a still-quoted name would count the
 * quote characters and can drop the closing one.
 *
 * Not part of [foldIdentifier], despite both being identifier-comparison rules: `parse_ident()`
 * does not truncate, and `JdbcAnalyzerTest`'s `FoldIdentifierParseIdentDifferentialTest` pins
 * [foldIdentifier] against it.
 */
internal fun truncateIdentifier(identifier: String): String {
  var byteLength = 0
  var index = 0
  while (index < identifier.length) {
    val codePoint = identifier.codePointAt(index)
    val codePointByteLength = when {
      codePoint <= 0x7F -> 1
      codePoint <= 0x7FF -> 2
      codePoint <= 0xFFFF -> 3
      else -> 4
    }
    if (byteLength + codePointByteLength > MAX_IDENTIFIER_LENGTH_BYTES) {
      return identifier.substring(0, index)
    }
    byteLength += codePointByteLength
    index += Character.charCount(codePoint)
  }
  return identifier
}
