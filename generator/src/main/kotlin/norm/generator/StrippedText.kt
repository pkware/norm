package norm.generator

/**
 * Removes every comment and all whitespace from [text], keeping every other character — including
 * the full contents of a string literal, quoted identifier, or dollar-quoted string — verbatim and
 * in relative order. A comment is removed outright, wherever it sits, including one between two
 * otherwise-adjacent tokens (`tgt./*c*/ *`), not merely one that leads or trails the whole string.
 *
 * Returns a [StrippedText], not a plain `String`, because deleting a separator PostgreSQL itself
 * lexed on can fuse two characters that were never adjacent into a token that never existed:
 * `1 - -1` (two independently-lexed `-` tokens) strips to `1--1`, which a naive re-lex of the
 * output `String` alone would read as a `--` line comment. [StrippedText] carries each stripped
 * character's original offset so a later adjacency decision can be gated on whether two characters
 * were really adjacent in the source text.
 */
internal fun stripCommentsAndWhitespace(text: String): StrippedText {
  val builder = StringBuilder(text.length)
  val originalOffsets = ArrayList<Int>(text.length)
  var i = 0
  while (i < text.length) {
    when {
      text[i].isWhitespace() -> i++
      text[i] == '-' && i + 1 < text.length && text[i + 1] == '-' -> i = skipLineComment(text, i)
      text[i] == '/' && i + 1 < text.length && text[i + 1] == '*' -> i = skipBlockComment(text, i)
      else -> {
        val afterToken = skipLexicalToken(text, i)
        if (afterToken != i) {
          builder.append(text, i, afterToken)
          for (originalIndex in i until afterToken) originalOffsets.add(originalIndex)
          i = afterToken
        } else {
          builder.append(text[i])
          originalOffsets.add(i)
          i++
        }
      }
    }
  }
  return StrippedText(builder.toString(), originalOffsets.toIntArray())
}

/**
 * The output of [stripCommentsAndWhitespace]: the stripped characters, plus each one's original
 * offset in the pre-stripping text, so [wereAdjacent] can answer whether two stripped characters
 * that now sit next to each other in [text] were genuinely adjacent before stripping, or had a
 * whitespace/comment separator between them.
 *
 * The raw stripped `String` is kept private: every lexer function stripped-path code needs
 * ([skipLexicalToken], [findMatchingCloseParenthesis]) is exposed as a member here that threads
 * `this` as the [OriginalAdjacency], so stripped-path code cannot bypass the adjacency gate.
 * [asPlainString] is the one escape hatch, for a caller that does no lexing and so has no
 * adjacency decision to make.
 */
internal class StrippedText(private val text: String, private val originalOffsets: IntArray) : OriginalAdjacency {

  val length: Int get() = text.length

  operator fun get(index: Int): Char = text[index]

  override fun wereAdjacent(leftIndex: Int): Boolean = leftIndex >= 0 &&
    leftIndex + 1 < originalOffsets.size &&
    originalOffsets[leftIndex + 1] == originalOffsets[leftIndex] + 1

  fun skipLexicalToken(position: Int): Int = norm.generator.skipLexicalToken(text, position, this)

  fun findMatchingCloseParenthesis(openParenthesisIndex: Int): Int =
    norm.generator.findMatchingCloseParenthesis(text, openParenthesisIndex, this)

  /**
   * The escape hatch out of this class's lexer entry points, back to a plain `String`. Safe only
   * for a caller that does no lexing and so has no adjacency decision to gate.
   */
  fun asPlainString(): String = text
}
