package norm.generator

/**
 * A parsed CTE definition from a `WITH` clause.
 *
 * @property name The CTE name, with surrounding double quotes stripped (if any). Safe for DISPLAY,
 *   and for a quote-INSENSITIVE comparison where losing the quoted/unquoted distinction is
 *   genuinely harmless. NOT safe for constructing SQL to send to PostgreSQL, and NOT safe for any
 *   comparison that resolves what the name actually ADDRESSES — quoting changes case-folding
 *   (`"MyCte"` is distinct from `MyCte`, which folds to `mycte`), so both building a `FROM <name>`
 *   reference from this stripped form, and comparing it against another identifier to decide
 *   whether they denote the same relation, can silently pick the wrong (or a nonexistent) one —
 *   see [resolveNodeTreeProvenanceExpression]'s own use of [rawName] instead, for exactly this
 *   reason. Use [rawName] for both constructing SQL and any identifier-resolution comparison.
 * @property rawName The CTE name exactly as written in the original SQL, including surrounding
 *   double quotes if the user quoted it. Safe to splice verbatim into a `FROM <rawName>` probe.
 * @property bodyOpenParenthesis Index of `(` that opens the CTE body in the original SQL.
 * @property bodyCloseParenthesis Index of `)` that closes the CTE body.
 * @property hasColumnList Whether the CTE was declared with an explicit column list
 *   (`name(col1, col2) AS (...)`). The list renames/repositions the body's own output names — but
 *   [resolveNodeTreeProvenanceExpression] never consults this flag: it cross-validates against the
 *   CTE BODY's own `:resname`s (read from the node tree, via [PgNodeTreeParser.parseTargetList]),
 *   which an explicit column list never changes, so a renamed CTE still resolves correctly. Kept
 *   for callers that need to know a column list was present, not because expression resolution
 *   depends on it.
 */
internal data class CteDefinition(
  val name: String,
  val rawName: String,
  val bodyOpenParenthesis: Int,
  val bodyCloseParenthesis: Int,
  val hasColumnList: Boolean = false,
)

/**
 * Result of parsing a SQL `WITH` clause.
 *
 * @property definitions The CTEs in declaration order.
 * @property mainQueryStart Index in the original SQL where the main query (after all CTEs) begins.
 * @property isRecursive Whether the clause is `WITH RECURSIVE`. This determines name visibility:
 *   under `WITH RECURSIVE`, every CTE's body can see every other CTE in the clause (including
 *   ones declared later); under a plain `WITH`, a CTE's body can only see CTEs declared before
 *   it (and, per SQL semantics, an outer name with the same name as a later CTE — a later CTE
 *   never shadows for an earlier body).
 */
internal data class ParsedCteClause(
  val definitions: List<CteDefinition>,
  val mainQueryStart: Int,
  val isRecursive: Boolean,
)

/**
 * Parses CTE definitions from a SQL `WITH` clause.
 *
 * Extracts each CTE's name and the character indices of its body parentheses so callers
 * can replace CTE bodies by simple string splicing. Handles `WITH RECURSIVE`, optional
 * column lists (`name(col1, col2) AS (...)`), and `[NOT] MATERIALIZED` hints.
 *
 * Skips single-line (`--`) and block (`/* */`) comments between CTE definitions.
 *
 * @return The parsed CTE clause, or `null` if the SQL does not start with `WITH`
 *   (after leading whitespace and comments).
 */
internal fun parseCteClause(sql: String): ParsedCteClause? {
  val withIndex = findTopLevelKeyword(sql, "WITH")
  if (withIndex < 0) return null

  // Make sure WITH is before any DML/SELECT keyword (it's the leading WITH, not WITH inside a subquery)
  for (keyword in listOf("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE")) {
    val keywordIndex = findTopLevelKeyword(sql, keyword)
    if (keywordIndex in 0 until withIndex) return null
  }

  var position = withIndex + 4
  position = skipWhitespaceAndComments(sql, position)

  // Skip optional RECURSIVE keyword (word-boundary check avoids matching CTE names like "recursive_cte")
  val positionAfterRecursive = skipOptionalKeyword(sql, position, "RECURSIVE")
  val isRecursive = positionAfterRecursive != position
  position = positionAfterRecursive

  val definitions = mutableListOf<CteDefinition>()

  while (position < sql.length) {
    val parsed = parseSingleCteDefinition(sql, position) ?: break
    definitions.add(parsed.first)
    position = skipWhitespaceAndComments(sql, parsed.second)
    if (position >= sql.length || sql[position] != ',') break
    position++ // skip the comma
  }

  return if (definitions.isEmpty()) {
    null
  } else {
    ParsedCteClause(definitions, position, isRecursive)
  }
}

/**
 * Parses a single CTE definition starting at [position].
 *
 * Expected syntax: `name [(col_list)] AS [NOT MATERIALIZED | MATERIALIZED] (body)`
 *
 * @return A pair of the parsed [CteDefinition] and the position after the closing `)`,
 *   or `null` if parsing fails at any step.
 */
private fun parseSingleCteDefinition(sql: String, startPosition: Int): Pair<CteDefinition, Int>? {
  var position = skipWhitespaceAndComments(sql, startPosition)

  // Read CTE name: an unquoted identifier (isIdentifierStartChar, then a run of isIdentifierChar
  // characters), or a double-quoted one. The FIRST character is gated by isIdentifierStartChar,
  // not isIdentifierChar — a leading digit or "$" is not a legal identifier start (see
  // isIdentifierStartChar's KDoc). The quoted branch uses QUOTED_IDENTIFIER_PATTERN, the SAME
  // ""-escape-aware matcher parseAliasToken uses, rather than stopping at the FIRST '"' — a naive
  // scan truncates a name like `"He""llo"` to `"He"` at the escaped quote's first half (#238).
  val nameStart = position
  if (position < sql.length && sql[position] == '"') {
    val match = QUOTED_IDENTIFIER_PATTERN.matchAt(sql, position)
    position = if (match != null) match.range.last + 1 else position + 1
  } else if (position < sql.length && isIdentifierStartChar(sql[position])) {
    position++
    while (position < sql.length && isIdentifierChar(sql[position])) position++
  }
  if (position == nameStart) return null
  val rawName = sql.substring(nameStart, position)
  val name = if (isQuotedIdentifier(rawName)) unescapeQuotedIdentifier(rawName) else rawName

  position = skipWhitespaceAndComments(sql, position)

  // Skip optional column list: name(col1, col2)
  var hasColumnList = false
  if (position < sql.length && sql[position] == '(') {
    hasColumnList = true
    val closeParenthesis = findMatchingCloseParenthesis(sql, position)
    if (closeParenthesis < 0) return null
    position = closeParenthesis + 1
    position = skipWhitespaceAndComments(sql, position)
  }

  // Expect AS
  if (!sql.regionMatches(position, "AS", 0, 2, ignoreCase = true)) return null
  position += 2
  position = skipWhitespaceAndComments(sql, position)

  // Skip optional NOT MATERIALIZED or MATERIALIZED
  position = skipOptionalKeyword(sql, position, "NOT")
  position = skipOptionalKeyword(sql, position, "MATERIALIZED")

  // Expect ( — start of CTE body
  if (position >= sql.length || sql[position] != '(') return null
  val bodyOpen = position
  val bodyClose = findMatchingCloseParenthesis(sql, position)
  if (bodyClose < 0) return null

  return CteDefinition(name, rawName, bodyOpen, bodyClose, hasColumnList) to (bodyClose + 1)
}
