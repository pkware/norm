package norm.generator.libpgquery

/** An element of [SplitFile.items], in file order. */
internal sealed interface SplitItem {
  /** A statement that [LibPgQuery.split] returned, spanning `start` until `end` of [SplitFile.text]. */
  data class Scanned(val start: Int, val end: Int) : SplitItem

  /** A region that the lexer rejected and that was cut out of the text before splitting. */
  data class Rejected(val text: String) : SplitItem
}

/**
 * A file split with [LibPgQuery.split], with each region the lexer rejects cut out.
 *
 * @property text the file without the rejected regions.
 * @property items the scanner statements of [text] and the rejected regions, in file order.
 */
internal class SplitFile(val text: String, val items: List<SplitItem>) {

  companion object {

    /**
     * Splits [text]. When the lexer rejects it, drops the statement around the error position, from the line after
     * the last `;`-ending line before the error through the first `;`-ending line at or after it, and splits the
     * remainder again.
     *
     * @throws LibPgQueryException if the lexer error has no position.
     */
    fun of(text: String): SplitFile {
      var remaining = text
      val rejected = mutableListOf<Pair<Int, String>>()
      while (true) {
        try {
          return SplitFile(remaining, interleave(LibPgQuery.split(remaining), rejected))
        } catch (exception: LibPgQueryException) {
          val position = exception.cursorIndex ?: throw exception
          val lines = remaining.split('\n')
          val errorLine = remaining.substring(0, position).count { it == '\n' }
          val first = (errorLine - 1 downTo 0).firstOrNull { lines[it].endsWithSemicolon() }?.plus(1) ?: 0
          val last = (errorLine until lines.size).firstOrNull { lines[it].endsWithSemicolon() } ?: lines.lastIndex
          val offset = lines.take(first).sumOf { it.length + 1 }
          rejected += offset to lines.subList(first, last + 1).joinToString("\n")
          remaining = (lines.take(first) + lines.drop(last + 1)).joinToString("\n")
        }
      }
    }

    private fun String.endsWithSemicolon(): Boolean = trimEnd().endsWith(';')

    /** A rejected region sits before the first statement that ends at or after the offset where it was cut. */
    private fun interleave(statements: List<SplitStatement>, rejected: List<Pair<Int, String>>): List<SplitItem> {
      val items = mutableListOf<SplitItem>()
      var next = 0
      for (statement in statements) {
        while (next < rejected.size && rejected[next].first <= statement.end) {
          items += SplitItem.Rejected(rejected[next++].second)
        }
        items += SplitItem.Scanned(statement.start, statement.end)
      }
      while (next < rejected.size) items += SplitItem.Rejected(rejected[next++].second)
      return items
    }
  }
}
