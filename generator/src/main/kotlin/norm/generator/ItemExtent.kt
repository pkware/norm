package norm.generator

import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.OutputStatement
import norm.generator.libpgquery.ResultTarget
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber
import norm.generator.libpgquery.isKeyword

/** The keyword that may precede an item's alias. */
internal const val AS_KEYWORD = "AS"

/** The literal that stands in for an item's expression while the end of the expression is searched. */
private const val PROBE_CONSTANT = "1"

/**
 * Finds the last token of the expression of an item that is not the last. The item ends at the token before the
 * comma, so its alias is the token at its end.
 *
 * @return the index in [tokens] of the last token of the expression, or `null` when the tokens around the comma do not
 *   fit the item.
 */
internal fun extentBeforeNextItem(
  sql: String,
  tokens: List<ScanToken>,
  target: ResultTarget,
  firstToken: Int,
  nextFirstToken: Int,
): Int? {
  val commaIndex = nextFirstToken - 1
  if (commaIndex - 1 < firstToken || tokens[commaIndex].token != TokenNumber.COMMA) return null
  val lastToken = commaIndex - 1
  if (target.alias == null) return lastToken

  val aliasFirstToken = lastToken
  val hasAsBeforeAlias = aliasFirstToken - 1 > firstToken && isKeyword(sql, tokens[aliasFirstToken - 1], AS_KEYWORD)
  val expressionLastToken = if (hasAsBeforeAlias) aliasFirstToken - 2 else aliasFirstToken - 1
  return expressionLastToken.takeIf { it >= firstToken }
}

/**
 * Finds the last token of the expression of the final item.
 *
 * Each token end at the item's own nesting depth, from the token of its largest location on, is a candidate. The text
 * from the item's start to the candidate is replaced by [PROBE_CONSTANT] between spaces. The candidate is the end when
 * that text parses, the item that starts at the constant has it as its value, and the tree otherwise equals the original
 * apart from locations.
 *
 * A candidate inside the expression, such as before `without time zone`, gives text that does not parse or gives the
 * item another value. A candidate after the expression replaces tokens that the tree records, such as the alias or
 * the `FROM` keyword, so the trees differ.
 *
 * @return the index in [ParsedSql.significantTokens] of the last token of the expression, or `null` when no candidate
 *   qualifies.
 */
internal fun extentOfFinalItem(parsed: ParsedSql, statement: OutputStatement, firstToken: Int): Int? {
  val tree = parsed.tree ?: return null
  val rewritten = parsed.parserForm
  val tokens = parsed.significantTokens
  val start = tokens[firstToken].start
  val probeLocation = start + 1
  val searchStart = searchStartIndex(tokens, statement.targets.last(), firstToken)
  val expectedTree = tree.withoutItemValueAndLocations(start)
  var depth = 0
  for (index in firstToken until tokens.size) {
    depth += nestingChange(tokens[index])
    if (depth < 0) return null
    if (depth != 0 || index < searchStart) continue
    val end = tokens[index].end
    val probe = rewritten.substring(0, start) + " $PROBE_CONSTANT " + rewritten.substring(end)
    val probeTree = LibPgQuery.parse(probe).valueOrNull() ?: continue
    val isConstant = probeTree.resultTargetAt(probeLocation)?.hasConstantValueAt(probeLocation) == true
    if (isConstant && probeTree.withoutItemValueAndLocations(probeLocation) == expectedTree) {
      return index
    }
  }
  return null
}

private fun nestingChange(token: ScanToken): Int = when (token.token) {
  TokenNumber.OPEN_PARENTHESIS, TokenNumber.OPEN_BRACKET -> 1
  TokenNumber.CLOSE_PARENTHESIS, TokenNumber.CLOSE_BRACKET -> -1
  else -> 0
}

/** The item cannot end before the token of the largest location inside it, so the search starts there. */
private fun searchStartIndex(tokens: List<ScanToken>, target: ResultTarget, firstToken: Int): Int {
  val location = target.largestLocation
  val index = tokens.binarySearchBy(location, fromIndex = firstToken) { it.start }
  return if (index >= 0) index else firstToken
}
