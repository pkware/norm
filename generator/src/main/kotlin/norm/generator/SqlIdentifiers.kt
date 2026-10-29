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
 * Converts a raw identifier token, exactly as written in the source SQL, to PostgreSQL's logical
 * identifier value: [foldIdentifier] resolves its quoting (unquoted folds via [foldAsciiCase];
 * quoted unescapes via [unescapeQuotedIdentifier]), then [truncateIdentifier] applies the server's
 * byte-length limit.
 *
 * The result is safe to compare against a name the server already reports (a catalog column name,
 * a `ResultSetMetaData` label, a node tree's `:resname`) — never safe to splice back into SQL as an
 * identifier, since a quoted result may need requoting and an unquoted one has already lost the
 * quoting decision.
 */
internal fun logicalIdentifier(rawToken: String): String = truncateIdentifier(foldIdentifier(rawToken))

/**
 * Folds only the ASCII letters `A`-`Z` to lowercase, leaving every other character untouched —
 * never through Kotlin's `String.lowercase()`, which applies full Unicode case mapping instead.
 *
 * PostgreSQL's own case-folding for an unquoted identifier folds only plain ASCII `A`-`Z`, never a
 * non-ASCII letter, even one with an obvious upper/lower pairing: on PostgreSQL 18.4, with a column
 * named `"ü"` (quoted, lowercase), the bare, unquoted reference `SELECT Ü FROM t` fails outright
 * (`column "Ü" does not exist`).
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
 * Reads the identifier token starting exactly at [position] in [sql] — a double-quoted identifier
 * (via [findDoubleQuotedIdentifierEnd]) or an unquoted one ([isIdentifierStartChar] followed by a
 * run of [isIdentifierChar] characters) — with no leading whitespace skip of its own.
 *
 * @param adjacency See [OriginalAdjacency]'s KDoc. Gates both the quoted branch's `""` escape (via
 *   [findDoubleQuotedIdentifierEnd]) and every character the unquoted branch's run extends through,
 *   so a caller scanning a [StrippedText] never fuses two identifiers that were only made to look
 *   adjacent by stripping out the whitespace or comment between them.
 * @return The end-exclusive index of the token, or `null` when [position] is at or past
 *   `sql.length`, the character at [position] can neither open a quoted identifier nor start an
 *   unquoted one, or it opens a quoted identifier that is never closed.
 */
internal fun readIdentifierToken(sql: String, position: Int, adjacency: OriginalAdjacency = ALL_ADJACENT): Int? {
  if (position >= sql.length) return null
  if (sql[position] == '"') return findDoubleQuotedIdentifierEnd(sql, position, adjacency)
  if (!isIdentifierStartChar(sql[position])) return null
  var end = position + 1
  while (end < sql.length && isIdentifierChar(sql[end]) && adjacency.wereAdjacent(end - 1)) end++
  return end
}

/**
 * Converts a raw double-quoted identifier token — including its surrounding quotes — into
 * PostgreSQL's logical identifier value: the surrounding quotes removed, and each doubled `""`
 * escape collapsed to the single literal `"` it represents.
 * On PostgreSQL 18, `ResultSetMetaData.getColumnName` for `SELECT "He""llo" FROM (SELECT 1 AS
 * "He""llo") s` reports `He"llo` (no quotes, escape already collapsed) — exactly what this
 * function produces from the raw token `"He""llo"`.
 *
 * [rawQuotedToken] must be a double-quoted identifier exactly as written — starting and ending
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
 * [MAX_IDENTIFIER_LENGTH_BYTES] UTF-8 bytes, dropping whole characters.
 * A 32-character name of `é` is 64 bytes and truncates to 31 characters, not to a broken 63rd byte.
 *
 * [identifier] must be the logical value, with quotes and any `""` escape already resolved; this
 * only measures bytes and has no opinion on quoting. Truncating a still-quoted name would count
 * the quote characters and can drop the closing one.
 *
 * Not part of [foldIdentifier]: `parse_ident()` does not truncate. See [logicalIdentifier] for
 * a raw token's fold-then-truncate combination.
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
