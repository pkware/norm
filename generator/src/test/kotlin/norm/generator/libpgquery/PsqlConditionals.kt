package norm.generator.libpgquery

/**
 * Tracks which branch of the psql `\if` blocks is active, the way psql does.
 *
 * psql evaluates a condition only when the enclosing branch is active and no earlier branch of the block was taken. An
 * `\elif` or `\else` outside a block or after the `\else` of its block changes nothing.
 */
internal class PsqlConditionals {
  private class Block(
    val enclosingActive: Boolean,
    var isTaken: Boolean,
    var isActive: Boolean,
    var hasElse: Boolean = false,
  )

  private val blocks = ArrayDeque<Block>()

  /** Whether psql sends the lines at the current position. */
  val isActive: Boolean get() = blocks.lastOrNull()?.isActive ?: true

  /**
   * Applies the command [word], one of `if`, `elif`, `else` and `endif`, with the text [argument] after it. A
   * condition that psql evaluates and that is not a boolean literal makes its branch inactive, as psql does after
   * reporting an invalid value.
   *
   * @return whether the branch became inactive because its condition is not a boolean literal.
   */
  fun apply(word: String, argument: String): Boolean {
    var isLiteral = true
    fun evaluate(): Boolean = parseBoolean(argument.trim()) ?: false.also { isLiteral = false }
    when (word) {
      "if" -> {
        val enclosingActive = isActive
        val value = enclosingActive && evaluate()
        blocks.addLast(Block(enclosingActive, isTaken = value || !enclosingActive, isActive = value))
      }
      "elif" -> blocks.lastOrNull()?.takeIf { !it.hasElse }?.let { block ->
        val value = block.enclosingActive && !block.isTaken && evaluate()
        block.isActive = value
        block.isTaken = block.isTaken || value
      }
      "else" -> blocks.lastOrNull()?.takeIf { !it.hasElse }?.let { block ->
        block.isActive = block.enclosingActive && !block.isTaken
        block.isTaken = true
        block.hasElse = true
      }
      "endif" -> blocks.removeLastOrNull()
    }
    return !isLiteral
  }

  private companion object {
    /** psql's boolean literals, where a case-insensitive prefix of `true`, `false`, `yes`, `no`, `on` or `off` counts. */
    fun parseBoolean(text: String): Boolean? {
      val lower = text.lowercase()
      return when {
        lower.isEmpty() -> null
        lower == "1" -> true
        lower == "0" -> false
        "true".startsWith(lower) || "yes".startsWith(lower) -> true
        "false".startsWith(lower) || "no".startsWith(lower) -> false
        lower.length >= 2 && "on".startsWith(lower) -> true
        lower.length >= 2 && "off".startsWith(lower) -> false
        else -> null
      }
    }
  }
}
