package norm.generator

import norm.generator.libpgquery.OutputStatement
import norm.generator.libpgquery.ParseResult
import norm.generator.libpgquery.ResultTarget
import norm.generator.libpgquery.ScanToken
import norm.generator.libpgquery.TokenNumber
import norm.generator.libpgquery.isKeyword

/** The literal that stands in for an item's expression while the end of the expression is searched. */
private const val PROBE_CONSTANT = "1"

/**
 * Finds where the expression of the final item ends.
 *
 * Each token end at the item's own nesting depth, from the token of its largest location on, is a candidate. The text
 * from the item's start to the candidate is replaced by [PROBE_CONSTANT] between spaces. The candidate is the end when
 * that text parses, the item that starts at the constant has it as its value, and the tree otherwise equals the original
 * apart from locations.
 *
 * A candidate inside the expression, such as before `without time zone`, gives text that does not parse or gives the
 * item another value. A candidate after the expression replaces tokens that the tree records, such as the alias or
 * the `FROM` keyword, so the trees differ.
 */
internal fun extentOfFinalItem(
  sql: String,
  rewritten: String,
  tokens: List<ScanToken>,
  tree: ParseResult,
  statement: OutputStatement,
  firstToken: Int,
): ItemExtent? {
  val start = tokens[firstToken].start
  val searchStart = searchStartIndex(tokens, statement.targets.last(), firstToken)
  val expectedTree = tree.withoutItemValueAndLocations(start)
  var depth = 0
  for (index in firstToken until tokens.size) {
    depth += nestingChange(tokens[index])
    if (depth < 0) return null
    if (depth != 0 || index < searchStart) continue
    val end = tokens[index].end
    val probe = rewritten.substring(0, start) + " $PROBE_CONSTANT " + rewritten.substring(end)
    val probeTree = parseOrNull(probe) ?: continue
    val isConstant = probeTree.resultTargetAt(start + 1)?.hasConstantValueAt(start + 1) == true
    if (isConstant && probeTree.withoutItemValueAndLocations(start + 1) == expectedTree) {
      return finalItemExtent(sql, tokens, statement.targets.last(), firstToken, index)
    }
  }
  return null
}

/** The alias follows the expression, after `AS` when that is written. */
private fun finalItemExtent(
  sql: String,
  tokens: List<ScanToken>,
  target: ResultTarget,
  firstToken: Int,
  expressionLastToken: Int,
): ItemExtent? {
  if (target.alias == null) return ItemExtent(firstToken, expressionLastToken, false)

  val afterExpression = expressionLastToken + 1
  if (afterExpression >= tokens.size) return null
  val isAliasExplicit = isKeyword(sql, tokens[afterExpression], AS_KEYWORD)
  val aliasFirstToken = if (isAliasExplicit) afterExpression + 1 else afterExpression
  if (aliasFirstToken >= tokens.size) return null
  return ItemExtent(firstToken, expressionLastToken, isAliasExplicit)
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
