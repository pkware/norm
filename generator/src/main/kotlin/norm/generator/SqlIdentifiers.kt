package norm.generator

/**
 * Folds a raw identifier the way PostgreSQL folds an identifier reference for comparison: an
 * unquoted identifier folds via [foldAsciiCase]; a quoted identifier (`"..."`) compares exactly,
 * with the surrounding quotes removed and any doubled `""` escape collapsed to the literal `"` it
 * represents, via [unescapeQuotedIdentifier].
 *
 * [rawIdentifier] must be exactly what was written in the source SQL, quotes and all where
 * present. A logical value with its quotes already removed must use the two-argument overload
 * below instead, passing whether it was quoted explicitly.
 */
internal fun foldIdentifier(rawIdentifier: String): String {
  val trimmed = rawIdentifier.trim()
  return if (isQuotedIdentifier(trimmed)) unescapeQuotedIdentifier(trimmed) else foldAsciiCase(trimmed)
}

/**
 * Folds a logical identifier value (quotes already removed, any doubled `""` escape already
 * collapsed) the way PostgreSQL folds an identifier reference for comparison: a quoted reference
 * ([isQuoted] `true`) compares exactly and is returned unchanged; an unquoted one folds via
 * [foldAsciiCase].
 */
internal fun foldIdentifier(logicalValue: String, isQuoted: Boolean): String =
  if (isQuoted) logicalValue else foldAsciiCase(logicalValue)

/**
 * Folds only the ASCII letters `A`-`Z` to lowercase, leaving every other character untouched —
 * never through Kotlin's `String.lowercase()`, which applies full Unicode case mapping instead.
 *
 * PostgreSQL's own case-folding for an unquoted identifier folds only plain ASCII `A`-`Z`, never a
 * non-ASCII letter, even one with an obvious upper/lower pairing: on PostgreSQL 18.4, with a column
 * named `"ü"` (quoted, lowercase), the bare, unquoted reference `SELECT Ü FROM t` fails outright
 * (`column "Ü" does not exist`) rather than resolving to it.
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
 */
internal fun isQuotedIdentifier(rawIdentifier: String): Boolean {
  val trimmed = rawIdentifier.trim()
  return trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')
}

/**
 * The character class an unquoted PostgreSQL identifier's first character may be: a letter, `_`,
 * or any character whose code is `>= 0x80` — never a digit or `$`, which are legal only after the
 * first character.
 */
internal const val COLUMN_REFERENCE_IDENTIFIER_START = """[\p{L}_\x{80}-\x{10FFFF}]"""

/**
 * The character class an unquoted PostgreSQL identifier's characters after the first may be: a
 * Unicode letter (`\p{L}`), decimal digit (`\p{Nd}`), `_`, `$`, or any character whose code is
 * `>= 0x80`. The `>= 0x80` part is a code-point range, not a per-`Char` one, so a supplementary-
 * plane character written as a surrogate pair in a Kotlin `String` matches as the single code
 * point it represents, not as two separate units.
 */
internal const val COLUMN_REFERENCE_IDENTIFIER_CONTINUATION = """[\p{L}\p{Nd}_$\x{80}-\x{10FFFF}]"""

private const val COLUMN_REFERENCE_IDENTIFIER =
  """$COLUMN_REFERENCE_IDENTIFIER_START$COLUMN_REFERENCE_IDENTIFIER_CONTINUATION*"""

/**
 * Matches a double-quoted PostgreSQL identifier, quotes included: `"` followed by any number of a
 * non-`"` character or a doubled `""` (PostgreSQL's escape for a literal `"` inside the name),
 * followed by the closing `"`. Not unescaped by this pattern itself — that is
 * [unescapeQuotedIdentifier]'s job.
 */
private const val QUOTED_IDENTIFIER = "\"(?:[^\"]|\"\")*\""

/**
 * [QUOTED_IDENTIFIER] compiled once, for callers that need to match a quoted identifier token
 * starting at a known position within a larger string (via [Regex.matchAt]) rather than matching
 * an entire already-isolated string (via [Regex.matchEntire], as [COLUMN_REFERENCE] does).
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
 * [COLUMN_REFERENCE_IDENTIFIER_CONTINUATION] characters) or a double-quoted one
 * ([QUOTED_IDENTIFIER] — `"ux"`, `"My Col"`, `"He""llo"`), matched via
 * [COLUMN_REFERENCE_IDENTIFIER_OR_QUOTED] for each position independently.
 *
 * The leading-character restriction on the unquoted alternative matters in the widening
 * direction: without it, a digit-led fragment such as `2€` — which PostgreSQL itself rejects
 * outright (`trailing junk after numeric literal`, on PostgreSQL 18.4) — would match as a whole
 * identifier once the continuation class widens to admit `€`, handing back a name PostgreSQL
 * would never resolve to. [parseColumnReference] returns `null` for anything that isn't a real
 * identifier.
 *
 * A matched group's captured text still includes its surrounding quotes (if any);
 * [parseColumnReference] turns that raw capture into the logical value via
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
 * A PostgreSQL identifier that never needs double-quoting when written back into SQL: starts with
 * a lowercase letter or underscore, followed by any number of lowercase letters, digits,
 * underscores, or dollar signs. Matching this pattern is necessary but not sufficient —
 * [quoteSqlIdentifierIfNeeded] additionally rejects a reserved word, which this pattern alone
 * cannot rule out (`order` and `user` both match it).
 */
private val SAFE_UNQUOTED_IDENTIFIER = Regex("[a-z_][a-z0-9_\$]*")

/**
 * Double-quotes [identifier] exactly as PostgreSQL requires it to be written back into SQL —
 * doubling any embedded `"` — unless [identifier]'s lowercased form is not one of [reservedWords]
 * and it already matches [SAFE_UNQUOTED_IDENTIFIER] bare.
 *
 * A mixed-case or space-containing name (`"Foo"`, `"My Col"`) rendered bare as `table.Foo` reads
 * back as `column tq.foo does not exist`, since PostgreSQL folds `Foo` to `foo`.
 *
 * A relation or column named after a reserved word (`order`, `user`) rendered bare as `order.id`
 * reads back as `syntax error at or near "."`, since an unquoted `order` parses as the reserved
 * keyword, not a table reference. [reservedWords] should be the connected server's own live
 * keyword set ([JdbcAnalyzer.fetchReservedWords]).
 */
internal fun quoteSqlIdentifierIfNeeded(identifier: String, reservedWords: Set<String>): String =
  if (identifier.lowercase() in reservedWords || !identifier.matches(SAFE_UNQUOTED_IDENTIFIER)) {
    "\"${identifier.replace("\"", "\"\"")}\""
  } else {
    identifier
  }

/**
 * Truncates [identifier] the way PostgreSQL does when it reaches the server
 * (`downcase_truncate_identifier` in `scan.l`): to the longest prefix of at most
 * [MAX_IDENTIFIER_LENGTH_BYTES] UTF-8 bytes, dropping whole characters rather than splitting one.
 * A 32-character name of `é` is 64 bytes and truncates to 31 characters, not to a broken 63rd byte.
 *
 * [identifier] must be the logical value, with quotes and any `""` escape already resolved; this
 * only measures bytes and has no opinion on quoting. Truncating a still-quoted name would count
 * the quote characters and can drop the closing one.
 *
 * Not part of [foldIdentifier]: `parse_ident()` does not truncate.
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
