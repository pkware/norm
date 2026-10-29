package norm.generator

import norm.generator.libpgquery.LibPgQuery
import norm.generator.libpgquery.LibPgQueryException
import norm.generator.libpgquery.TokenNumber

/**
 * Replaces each separator between the quoted segments of a string literal with one space. PostgreSQL's lexer reads
 * `'a' -- note`, a line break and `'b'` as a single token.
 *
 * A separator is the whitespace and `--` comments between a closing quote and the next segment. A segment boundary
 * is a split point at which the lexer reads each side as a single token.
 *
 * @param literal the text of one lexer token.
 * @return [literal] with every separator replaced by a space, or [literal] itself when it has no separator.
 */
internal fun joinStringContinuation(literal: String): String {
  val split = findContinuationSplit(literal) ?: return literal
  return literal.substring(0, split.segmentEnd) + " " +
    joinStringContinuation(literal.substring(split.nextSegmentStart))
}

private class ContinuationSplit(val segmentEnd: Int, val nextSegmentStart: Int)

private fun findContinuationSplit(literal: String): ContinuationSplit? {
  if (!literal.endsWith('\'') || literal.none { it == '\n' || it == '\r' }) return null
  for (segmentEnd in 1 until literal.length) {
    if (literal[segmentEnd - 1] != '\'' || !isSingleToken(literal.substring(0, segmentEnd))) continue
    for (nextSegmentStart in segmentEnd + 1 until literal.length) {
      val endsSeparator = literal[nextSegmentStart - 1].isWhitespace() && !literal[nextSegmentStart].isWhitespace()
      if (endsSeparator &&
        isOnlyComments(literal.substring(segmentEnd, nextSegmentStart)) &&
        isSingleToken(literal.substring(nextSegmentStart))
      ) {
        return ContinuationSplit(segmentEnd, nextSegmentStart)
      }
    }
  }
  return null
}

private fun isSingleToken(text: String): Boolean {
  val tokens = scanOrNull(text) ?: return false
  return tokens.size == 1 && tokens.single().start == 0 && tokens.single().end == text.length
}

private fun isOnlyComments(text: String): Boolean = scanOrNull(text)?.all {
  it.token == TokenNumber.LINE_COMMENT || it.token == TokenNumber.BLOCK_COMMENT
} ?: false

private fun scanOrNull(text: String) = try {
  LibPgQuery.scan(text)
} catch (_: LibPgQueryException) {
  null
}
