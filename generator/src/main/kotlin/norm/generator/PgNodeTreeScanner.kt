package norm.generator

import java.util.concurrent.ConcurrentHashMap

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
 * Field-name markers (e.g. `:targetList (`, `:expr {`) are Postgres's own fixed labels, never user
 * data, so they are never escaped, and the plain substring searches for them elsewhere in this class
 * ([extractArgListSection] and similar) are safe as long as they hand off to an escape-aware scan
 * for everything past the marker. [extractFieldExpression] is the exception: it searches via the
 * escape-aware and depth-one-aware [findMarkerAtDepthOne] rather than a plain `indexOf` — see its
 * own KDoc for why depth-one-awareness is needed there specifically.
 */
internal class PgNodeTreeScanner {

  /**
   * Finds [marker] at brace depth 1 (measured from the first unescaped `{` in [text]) and returns
   * the index just past the marker's last character, or `-1` when [marker] does not appear at
   * that depth before the outermost block closes, or [text] contains no unescaped `{`.
   *
   * "Depth 1" means directly inside the outermost `{...}` block, excluding any field nested
   * inside a child `{...}` block — this is what prevents matching, for example, a nested
   * `JOINEXPR`'s own `:quals` field when looking for the top-level `:quals` of a `FROMEXPR`.
   *
   * Escaping: see the class-level note on backslash escaping. Every `\`-prefixed pair is skipped
   * as an opaque, non-structural unit — an escaped `\{`/`\}` inside a quoted identifier (e.g. a
   * column alias containing a literal `}`) is data, not a real brace, and must not perturb
   * [braceDepth] or terminate the scan early.
   */
  internal fun findMarkerAtDepthOne(text: String, marker: String): Int {
    val outerBraceIndex = nextUnescapedIndexOf(text, '{', 0)
    if (outerBraceIndex == -1) return -1

    var braceDepth = 0
    var index = outerBraceIndex
    var markerMatchIndex = 0
    while (index < text.length) {
      val character = text[index]
      if (character == '\\' && index + 1 < text.length) {
        index += 2
        markerMatchIndex = 0
        continue
      }
      when {
        character == '{' -> {
          braceDepth++
          markerMatchIndex = 0
        }
        character == '}' -> {
          braceDepth--
          markerMatchIndex = 0
          if (braceDepth == 0) break
        }
        braceDepth == 1 ->
          if (character == marker[markerMatchIndex]) {
            markerMatchIndex++
            if (markerMatchIndex == marker.length) return index + 1
          } else {
            markerMatchIndex = if (character == marker[0]) 1 else 0
          }
        else -> markerMatchIndex = 0
      }
      index++
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
   * Extracts the content of the `(...)` list after [fieldName] — a direct field of the node [text]
   * itself represents, at brace depth 1 — without parsing it.
   *
   * Depth-one-awareness (via [findMarkerAtDepthOne]) matters here too: when [text]'s own [fieldName]
   * value is empty (`fieldName <>`, no `(` at all) but [text] also contains a deeper node carrying
   * its own `fieldName (` — a `JSONCONSTRUCTOREXPR` with an empty `:args <>` whose `:func` holds an
   * `AGGREF` with a real `:args (...)`, exactly how `JSON_OBJECTAGG`/`JSON_ARRAYAGG` are shaped — a
   * plain `text.indexOf("$fieldName (")` would find that unrelated nested list and silently
   * attribute the inner node's arguments to the outer one. See [extractFieldExpression] for why
   * scanning for literal marker text is nonetheless safe against a string value that resembles a
   * field marker.
   *
   * @return `null` if the field is absent at depth 1, or its value is `<>` (empty/absent in
   *   `pg_node_tree`), or the value at that position is not actually a `(...)` list.
   */
  internal fun extractArgListSection(text: String, fieldName: String): String? {
    val markerEnd = findMarkerAtDepthOne(text, "$fieldName ")
    if (markerEnd == -1 || markerEnd >= text.length || text[markerEnd] != '(') return null
    val content = extractBalancedParentheses(text, markerEnd)
    return if (content.isNullOrBlank()) null else content
  }

  /**
   * Extracts a named `{...}` expression block from a field like `:fieldName {NODETYPE ...}`, at
   * brace depth 1 of [text] — i.e. [fieldName] must be a direct field of the node [text] itself
   * represents, not a same-named field belonging to some node NESTED inside one of [text]'s own
   * field values.
   *
   * Depth-one-awareness matters here: [fieldName] is often a field whose own value is a full
   * expression subtree that can legally contain another node of the same outer type carrying the
   * same field name — e.g. a `SUBLINK`'s `:testexpr` field can itself contain a nested `SUBLINK`
   * with its own `:testexpr`/`:subselect`, a `CASEWHEN`'s `:expr` condition can contain a nested
   * `CASEEXPR` with its own `:result`/`:defresult`, and a `JSONEXPR`'s `:on_empty`/`:on_error`
   * behavior can nest another `JSONEXPR`. Postgres serializes a node depth-first, so a same-named
   * field belonging to a nested node is written inside the outer field's own value — textually
   * earlier than the outer node's own later field of that name, whenever the outer field being
   * searched for comes before the nested one in that node's field order. On PostgreSQL 17 and 18,
   * `SUBLINK`'s `:testexpr` field precedes its `:subselect` field, so for `SELECT EXISTS (SELECT v
   * FROM u) = ANY (SELECT b FROM x) FROM t`, the outer `ANY_SUBLINK`'s `:testexpr` (an `OPEXPR`
   * whose first argument is the nested `EXISTS` sublink, itself a genuine `{SUBLINK ... :subselect
   * {QUERY ... u ...} ...}` block) textually precedes the outer `ANY_SUBLINK`'s own `:subselect
   * {QUERY ... x ...}` — a naive first-match `text.indexOf(":subselect {")` scan over the outer
   * `ANY_SUBLINK`'s full text would return the inner `EXISTS` sublink's `u`-block, not the outer
   * sublink's own `x`-block, silently proving the wrong subquery's column nullable or not.
   * [findMarkerAtDepthOne] (reused here, the same helper used elsewhere for a `:jointree`/`:quals`
   * extraction) only matches [fieldName] directly inside [text]'s own outermost `{...}` block, so a
   * nested node's same-named field can never shadow it.
   *
   * [findMarkerAtDepthOne] resets its match state on any `{`, so the marker searched for must be
   * `"$fieldName "` (trailing space, no brace) rather than `"$fieldName {"` — the value's opening
   * brace is located separately, immediately after the marker.
   *
   * Returns `null` if the field is absent, or its value is not a brace block (e.g. `:defresult <>`).
   */
  internal fun extractFieldExpression(text: String, fieldName: String): String? {
    val markerEnd = findMarkerAtDepthOne(text, "$fieldName ")
    if (markerEnd == -1 || markerEnd >= text.length || text[markerEnd] != '{') return null
    return extractBalancedBraces(text, markerEnd)
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
   * Extracts a boolean field's value, scoped to [text]'s OWN outermost `{...}` block (brace depth
   * 1) — the depth-one-aware counterpart to [extractBoolField], required whenever [fieldName] could
   * also appear, deeper in [text], on a NESTED node of the same type (the concrete case is a CTE
   * body that declares its own nested `WITH` clause).
   *
   * @return `true`/`false` read directly after [fieldName]'s marker, or `null` if [fieldName] is
   *   absent at depth 1 or its value is neither literal token
   */
  internal fun extractBoolFieldAtDepthOne(text: String, fieldName: String): Boolean? {
    val markerEnd = findMarkerAtDepthOne(text, "$fieldName ")
    if (markerEnd == -1) return null
    return when {
      text.startsWith("true", markerEnd) -> true
      text.startsWith("false", markerEnd) -> false
      else -> null
    }
  }

  /**
   * Finds [marker] at the outermost QUERY level (brace depth 1) and extracts the
   * balanced-parenthesis content that follows the trailing `(` of the marker.
   *
   * @param marker a field marker ending in `(`, e.g. `":targetList ("`
   * @return the content inside the outer parentheses, or `null` if not found or unbalanced
   */
  internal fun extractOuterSectionContent(text: String, marker: String): String? {
    check(marker.endsWith("(")) { "marker must end with '(': $marker" }
    val markerEnd = findMarkerAtDepthOne(text, marker)
    if (markerEnd == -1) return null
    val openParenthesisIndex = markerEnd - 1
    return extractBalancedParentheses(text, openParenthesisIndex)
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
