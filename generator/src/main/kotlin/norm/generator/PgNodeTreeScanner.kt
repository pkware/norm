package norm.generator

import java.util.concurrent.ConcurrentHashMap

/** A field's value as read by [PgNodeTreeScanner.fieldAtDepthOne]. */
internal sealed interface FieldValue {

  /** A `{...}` node, braces included. */
  data class Block(val content: String) : FieldValue

  /** The interior of a `(...)` list, parentheses excluded; `""` for `()`. */
  data class ListContent(val content: String) : FieldValue

  /** An undelimited value such as `true` or `42`. */
  data class Token(val text: String) : FieldValue

  /** The field is missing, is `<>`, or its delimiters never close. */
  data object Absent : FieldValue
}

/**
 * Reads field values and balanced `{...}`/`(...)` blocks out of raw `pg_node_tree` text.
 *
 * **Escaping**: Postgres's node-tree writer (`outToken` in `outfuncs.c`) backslash-escapes any
 * character in a string/identifier value that would otherwise be misread as structural: `{`, `}`,
 * `(`, `)`, whitespace, and the backslash itself. A column alias of `k}x` is written as
 * `:resname k\}x`; a literal backslash in a value is written as `\\`. Every backslash-prefixed pair
 * is exactly two characters — there is no `\n`-for-newline mnemonic; a literal newline is written as
 * a backslash followed by the raw newline byte. Every function here that scans raw text
 * character-by-character for structural `{`, `}`, `(`, or `)` must treat a `\`-prefixed pair as an
 * opaque unit — see [nextUnescapedIndexOf], [findMarkerAtDepthOne], and [extractBalancedDelimiters].
 * Field-name markers (e.g. `:targetList`, `:expr`) are Postgres's own fixed labels, never user
 * data, so they are never escaped.
 */
internal class PgNodeTreeScanner {

  /**
   * Finds [marker] as a field label at brace depth 1 (measured from the first unescaped `{` in
   * [text]) and returns the index just past the marker's trailing space, or `-1` when [marker]
   * does not label a field at that depth before the outermost block closes, or [text] contains no
   * unescaped `{`. [marker] is always a field name followed by exactly one space (e.g.
   * `":args "`) — every caller passes it in that shape.
   *
   * "Depth 1" means directly inside the outermost `{...}` block, excluding any field nested
   * inside a child `{...}` block — this is what prevents matching, for example, a nested
   * `JOINEXPR`'s own `:quals` field when looking for the top-level `:quals` of a `FROMEXPR`.
   *
   * At depth 1, items are walked one at a time: a token, a whole `{...}` block, or a whole
   * `(...)` list — never the interior of a `(...)` list, even though it sits directly inside the
   * outermost block, so a marker-shaped token inside a list is never mistaken for a label. A
   * token is a field label iff it starts with `:` and the item immediately before it is not
   * itself a label; only a label consumes the item that follows it as its value. This is what
   * tells a real label apart from a value token that happens to start with `:` (e.g. a CTE or
   * column literally named `:something`, a quoted identifier preserved verbatim in the tree) or
   * merely end with the marker text (e.g. a value `x:resorigtbl` immediately before the real
   * `:resorigtbl` label).
   *
   * Escaping: see the class-level note on backslash escaping. Every `\`-prefixed pair inside a
   * token is opaque and part of that token — an escaped `\{`/`\}`/`\(`/`\)` inside a quoted
   * identifier (e.g. a column alias containing a literal `}`) is data, not a real delimiter, and
   * must not be read as the start of a block or list, or split the token early.
   */
  internal fun findMarkerAtDepthOne(text: String, marker: String): Int {
    val outerBraceIndex = nextUnescapedIndexOf(text, '{', 0)
    if (outerBraceIndex == -1) return -1
    val label = marker.dropLast(1)

    var index = outerBraceIndex + 1
    var previousItemIsLabel = false
    while (index < text.length) {
      while (index < text.length && text[index].isWhitespace()) index++
      if (index >= text.length) return -1
      when (text[index]) {
        '}' -> return -1
        '{' -> {
          val block = extractBalancedBraces(text, index) ?: return -1
          index += block.length
          previousItemIsLabel = false
        }

        '(' -> {
          val list = extractBalancedDelimiters(text, index, '(', ')', includeDelimiters = true) ?: return -1
          index += list.length
          previousItemIsLabel = false
        }

        else -> {
          val token = scanTokenAt(text, index)
          if (token.isEmpty()) return -1
          val isLabel = token.startsWith(':') && !previousItemIsLabel
          if (isLabel && token == label && index + token.length < text.length && text[index + token.length] == ' ') {
            return index + token.length + 1
          }
          index += token.length
          previousItemIsLabel = isLabel
        }
      }
    }
    return -1
  }

  /**
   * Returns the index of the first occurrence of [target] in [text] at or after [fromIndex] that
   * is NOT escaped by a preceding backslash, or `-1` if none exists.
   *
   * See the class-level note on backslash escaping. A backslash in `pg_node_tree` output always
   * introduces a one-character escape, so this treats every `\`-prefixed pair as an opaque,
   * two-character unit when scanning — an escaped `target` (e.g. a literal `{` inside a quoted
   * identifier, written as `\{`) is never mistaken for a real, structural occurrence.
   */
  private fun nextUnescapedIndexOf(text: String, target: Char, fromIndex: Int): Int {
    var index = fromIndex
    while (index < text.length) {
      val character = text[index]
      if (character == '\\' && index + 1 < text.length) {
        index += 2
        continue
      }
      if (character == target) return index
      index++
    }
    return -1
  }

  /**
   * Reads [fieldName] (e.g. `":args"`) as a field of [text]'s outermost node, ignoring same-named
   * fields of nested nodes. For `SELECT EXISTS (SELECT v FROM u) = ANY (SELECT b FROM x) FROM t`,
   * the outer `SUBLINK`'s `:subselect` is the query over `x`, even though the inner `EXISTS`
   * sublink's `:subselect` over `u` is written first.
   *
   * @return the value's shape, whatever it is; [FieldValue.Absent] if the field is missing, is
   *   `<>`, or has unbalanced delimiters
   */
  internal fun fieldAtDepthOne(text: String, fieldName: String): FieldValue {
    // findMarkerAtDepthOne returns the index just past the label's single trailing space: the value's first character.
    val markerEnd = findMarkerAtDepthOne(text, "$fieldName ")
    if (markerEnd == -1 || markerEnd >= text.length) return FieldValue.Absent
    return when (text[markerEnd]) {
      '{' -> extractBalancedBraces(text, markerEnd)?.let(FieldValue::Block) ?: FieldValue.Absent
      '(' -> extractBalancedParentheses(text, markerEnd)?.let(FieldValue::ListContent) ?: FieldValue.Absent
      else ->
        if (text.startsWith("<>", markerEnd)) {
          FieldValue.Absent
        } else {
          FieldValue.Token(scanTokenAt(text, markerEnd))
        }
    }
  }

  /** Returns the token at [startIndex], ending at unescaped whitespace, a delimiter, or the end of [text]. */
  private fun scanTokenAt(text: String, startIndex: Int): String {
    var index = startIndex
    while (index < text.length) {
      val character = text[index]
      if (character == '\\' && index + 1 < text.length) {
        index += 2
        continue
      }
      if (character.isWhitespace() || character == '{' || character == '}' || character == '(' || character == ')') {
        break
      }
      index++
    }
    return text.substring(startIndex, index)
  }

  /** @return the `{...}` value of [fieldName] (see [fieldAtDepthOne]), or `null` if the value is not a node */
  internal fun blockAtDepthOne(text: String, fieldName: String): String? =
    (fieldAtDepthOne(text, fieldName) as? FieldValue.Block)?.content

  /**
   * @return the interior of [fieldName]'s `(...)` value (see [fieldAtDepthOne]), or `null` if the
   *   value is not a list or the list is blank
   */
  internal fun listAtDepthOne(text: String, fieldName: String): String? =
    (fieldAtDepthOne(text, fieldName) as? FieldValue.ListContent)?.content?.takeUnless { it.isBlank() }

  /**
   * Like [listAtDepthOne], but returns `""` rather than `null` for a blank list.
   *
   * @return the interior of [fieldName]'s `(...)` value, or `null` if the value is not a list
   */
  internal fun rawListAtDepthOne(text: String, fieldName: String): String? =
    (fieldAtDepthOne(text, fieldName) as? FieldValue.ListContent)?.content

  /**
   * @return [fieldName]'s `true`/`false` value (see [fieldAtDepthOne]), or `null` if the value is
   *   anything else
   */
  internal fun boolAtDepthOne(text: String, fieldName: String): Boolean? =
    when ((fieldAtDepthOne(text, fieldName) as? FieldValue.Token)?.text) {
      "true" -> true
      "false" -> false
      else -> null
    }

  /**
   * Extracts an integer field value from a node block.
   *
   * @param text the full node block text
   * @param fieldName the field name including the leading colon, e.g. `":varno"`
   * @return the integer value, or `null` if the field is absent or unparseable
   */
  internal fun extractIntField(text: String, fieldName: String): Int? =
    intFieldPatterns.getOrPut(fieldName) { Regex("""$fieldName (-?\d+)""") }
      .find(text)?.groupValues?.get(1)?.toIntOrNull()

  /**
   * Extracts a boolean field value from a node block.
   *
   * @param text the full node block text
   * @param fieldName the field name including the leading colon, e.g. `":constisnull"`
   * @return the boolean value, or `null` if the field is absent
   */
  internal fun extractBoolField(text: String, fieldName: String): Boolean? =
    boolFieldPatterns.getOrPut(fieldName) { Regex("""$fieldName (true|false)""") }
      .find(text)?.groupValues?.get(1)?.let { it == "true" }

  /**
   * Extracts a string field value from a node block, with backslash-escaping removed (see the
   * class-level note on backslash escaping) — e.g. a `:resname` of `k\}x` is returned as `k}x`.
   *
   * The capture group matches a run of either a plain non-whitespace character or a backslash
   * followed by any character (`\\[\s\S]`, tried first at each position) — not a bare `\S+`, which
   * would stop at an escaped whitespace byte (e.g. `:ctename My\ Cte` for a CTE named `"My Cte"`)
   * and truncate the match before [unescapeToken] ever runs. `[\s\S]`, not `.`, also lets the
   * escaped-pair alternative consume an escaped newline without `Regex`'s DOTALL mode.
   *
   * @param text the full node block text
   * @param fieldName the field name including the leading colon, e.g. `":ctename"`
   * @return the unescaped string value, or `null` if the field is absent
   */
  internal fun extractStringField(text: String, fieldName: String): String? =
    stringFieldPatterns.getOrPut(fieldName) { Regex("""$fieldName ((?:\\[\s\S]|\S)+)""") }
      .find(text)?.groupValues?.get(1)?.let(::unescapeToken)

  /**
   * Removes `pg_node_tree` backslash-escaping from an already-captured raw token: each
   * `\`-prefixed pair collapses to just the escaped character (see the class-level note on
   * backslash escaping).
   */
  private fun unescapeToken(token: String): String {
    if ('\\' !in token) return token
    val result = StringBuilder(token.length)
    var index = 0
    while (index < token.length) {
      val character = token[index]
      if (character == '\\' && index + 1 < token.length) {
        result.append(token[index + 1])
        index += 2
      } else {
        result.append(character)
        index++
      }
    }
    return result.toString()
  }

  /**
   * Extracts a PostgreSQL bitmapset field value into a [Set] of integers.
   *
   * PostgreSQL encodes bitmapsets as `(b)` for empty, or `(b N ...)` where N is one or more
   * space-separated integers. The `b` is a base marker and is not included in the result.
   *
   * @param text the full node block text
   * @param fieldName the field name including the leading colon, e.g. `":varnullingrels"`
   * @return the set of integer members, or [emptySet] if the field is absent or the set is empty
   */
  internal fun extractBitmapset(text: String, fieldName: String): Set<Int> {
    val fieldIndex = text.indexOf(fieldName)
    if (fieldIndex == -1) return emptySet()
    val content = bitmapsetPattern.find(text, fieldIndex + fieldName.length)?.groupValues?.get(1) ?: return emptySet()
    return content.trim().split(whitespace).mapNotNull { it.toIntOrNull() }.toSet()
  }

  /**
   * Extracts the content inside balanced parentheses starting at [startIndex].
   *
   * @param text the full text to parse
   * @param startIndex the index of the opening `(`
   * @return the content between the outer `(` and its matching `)`, or `null` if unbalanced
   */
  private fun extractBalancedParentheses(text: String, startIndex: Int): String? =
    extractBalancedDelimiters(text, startIndex, open = '(', close = ')', includeDelimiters = false)

  /**
   * Extracts balanced brace content starting at the `{` at [startIndex].
   *
   * @param text the full text to parse
   * @param startIndex the index of the opening `{`
   * @return the full `{...}` text including the outer braces, or `null` if unbalanced or
   *   [startIndex] does not point to a `{`
   */
  internal fun extractBalancedBraces(text: String, startIndex: Int): String? =
    extractBalancedDelimiters(text, startIndex, open = '{', close = '}', includeDelimiters = true)

  /**
   * Extracts content inside balanced delimiters starting at [startIndex].
   *
   * See the class-level note on backslash escaping: a `\`-prefixed pair (e.g. `\{`, `\}`, `\\`, or
   * an escaped space) is copied into [content] verbatim and never counted toward [depth] or
   * matched against [open]/[close], even when the escaped character is itself one of them.
   *
   * @param includeDelimiters when `true`, the outer delimiter pair is included in the result;
   *   when `false`, only the content between the delimiters is returned
   * @return the extracted content, or `null` if [startIndex] does not point to [open] or the
   *   delimiters are unbalanced
   */
  private fun extractBalancedDelimiters(
    text: String,
    startIndex: Int,
    open: Char,
    close: Char,
    includeDelimiters: Boolean,
  ): String? {
    if (startIndex >= text.length || text[startIndex] != open) return null
    var depth = 0
    val content = StringBuilder()
    var index = startIndex
    while (index < text.length) {
      val character = text[index]
      if (character == '\\' && index + 1 < text.length) {
        content.append(character).append(text[index + 1])
        index += 2
        continue
      }
      when (character) {
        open -> {
          depth++
          if (includeDelimiters || depth > 1) content.append(character)
        }
        close -> {
          depth--
          if (depth == 0) {
            if (includeDelimiters) content.append(character)
            return content.toString()
          }
          content.append(character)
        }
        else -> content.append(character)
      }
      index++
    }
    return null
  }

  /**
   * Splits parenthesized node-tree list content into its top-level `{...}` blocks, respecting
   * nested braces. Returns ALL brace blocks regardless of node type.
   *
   * Uses [nextUnescapedIndexOf] (not a plain `indexOf`) so a literal `{` escaped inside a quoted
   * identifier between blocks — see the class-level note on backslash escaping — is not mistaken
   * for the start of the next block.
   */
  internal fun splitBraceBlocks(content: String): List<String> {
    val entries = mutableListOf<String>()
    var index = 0
    while (index < content.length) {
      val braceIndex = nextUnescapedIndexOf(content, '{', index)
      if (braceIndex == -1) break
      val entry = extractBalancedBraces(content, braceIndex) ?: break
      entries.add(entry)
      index = braceIndex + entry.length
    }
    return entries
  }

  companion object {
    internal val whitespace = Regex("""\s+""")
    private val bitmapsetPattern = Regex("""\(([^)]*)\)""")
    private val intFieldPatterns = ConcurrentHashMap<String, Regex>()
    private val boolFieldPatterns = ConcurrentHashMap<String, Regex>()
    private val stringFieldPatterns = ConcurrentHashMap<String, Regex>()
  }
}
