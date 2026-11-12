package norm.generator

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
