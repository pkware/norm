package norm.generator.libpgquery

private const val BACKSLASH_TOKEN = 92
private const val SEMICOLON_TOKEN = 59
private const val SQL_COMMENT_TOKEN = 275
private const val C_COMMENT_TOKEN = 276

/** The psql commands that send the query buffer to the server, so the text before them is a statement. */
private val QUERY_BUFFER_COMMANDS = setOf("g", "gx", "gset", "gexec", "gdesc")

/** The psql commands that open, switch and close a conditional block. */
private val CONDITIONAL_COMMANDS = setOf("if", "elif", "else", "endif")

/**
 * The lines of a regress file that psql sends to the server, and the `COPY` data it consumes.
 *
 * @property text the lines that remain, each ending in `\n`.
 * @property copyBlocks the `COPY ... FROM STDIN` data blocks that were dropped.
 * @property inactiveBranches the `\if` and `\elif` lines with a non-literal condition, and the runs of lines in
 *   inactive branches that hold SQL.
 */
internal data class PsqlLines(
  val text: String,
  val copyBlocks: List<CopyDataBlock>,
  val inactiveBranches: List<InactiveBranch>,
) {

  companion object {

    /**
     * Drops the lines psql would not send to the server: those whose first non-blank character is `\`, and the data
     * of each `COPY ... FROM STDIN` through its `\.` line. PostgreSQL's parser decides which statements are such a
     * `COPY`. Besides an SQL statement, a `\copy` line and each part of a statement that `\;` chains together
     * can be one, and a part consumes one data block in order.
     *
     * A line that starts with one of [QUERY_BUFFER_COMMANDS] becomes a line holding only `;`, because psql sends the
     * text before it as a statement. The lines of an inactive `\if` branch are dropped, because psql never sends them.
     * An `\if` or `\elif` whose condition psql evaluates and that is not a boolean literal makes its branch
     * inactive, and is logged on its own line.
     *
     */
    fun of(lines: List<String>): PsqlLines = Reader(lines).read()
  }

  private class Reader(private val lines: List<String>) {
    private val kept = StringBuilder()
    private val pending = StringBuilder()
    private val blocks = mutableListOf<CopyDataBlock>()
    private val inactiveBranches = mutableListOf<InactiveBranch>()
    private val conditionals = PsqlConditionals()
    private val inactiveRun = mutableListOf<Int>()
    private var index = 0

    fun read(): PsqlLines {
      while (index < lines.size) {
        val line = lines[index++]
        copiesOf(line).forEach(::dropDataBlock)
      }
      endInactiveRun()
      return PsqlLines(kept.toString(), blocks, inactiveBranches)
    }

    /** The first lines of the `COPY ... FROM STDIN` statements that [line] completes. */
    private fun copiesOf(line: String): List<String> {
      val command = line.trim()
      if (command.startsWith('\\') && commandWord(command) in CONDITIONAL_COMMANDS) {
        endInactiveRun()
        val word = commandWord(command)
        if (conditionals.apply(word, command.drop(1 + word.length))) {
          inactiveBranches += InactiveBranch(index, index, command)
        }
        return emptyList()
      }
      if (!conditionals.isActive) {
        inactiveRun += index
        return emptyList()
      }
      endInactiveRun()
      if (command.startsWith('\\')) {
        if (commandWord(command) in QUERY_BUFFER_COMMANDS) return append(";")
        return if (copyFromStandardInputCount(command.drop(1)) > 0) listOf(command) else emptyList()
      }
      return append(line)
    }

    /** Logs the lines of the inactive branch that just ended, when they hold SQL. */
    private fun endInactiveRun() {
      if (inactiveRun.isEmpty()) return
      val runLines = inactiveRun.map { lines[it - 1] }
      val hasSql = runLines.any { text ->
        val trimmed = text.trim()
        trimmed.isNotEmpty() && !trimmed.startsWith("--") && !trimmed.startsWith('\\')
      }
      if (hasSql) {
        inactiveBranches +=
          InactiveBranch(inactiveRun.first(), inactiveRun.last(), firstCodeLine(runLines.joinToString("\n")))
      }
      inactiveRun.clear()
    }

    /** Adds [line] to the text that remains and returns the `COPY ... FROM STDIN` statements it completes. */
    private fun append(line: String): List<String> {
      kept.append(line).append('\n')
      pending.append(line).append('\n')
      return if (';' in line) closedCopies() else emptyList()
    }

    /** The letters after the `\\` that starts [command]. */
    private fun commandWord(command: String): String = command.drop(1).takeWhile { it.isLetter() }

    private fun dropDataBlock(copyStatement: String) {
      val firstDataLine = index
      while (index < lines.size && lines[index].trim() != "\\.") index++
      index = minOf(index + 1, lines.size)
      blocks += CopyDataBlock(firstDataLine + 1, index, copyStatement)
    }

    /** Empties [pending] when its last statement is closed, and returns the `COPY ... FROM STDIN` parts in it. */
    private fun closedCopies(): List<String> {
      val text = pending.toString()
      val last = terminationOf(text) ?: return emptyList()
      pending.setLength(0)
      if (last.isEmpty()) return emptyList()
      val parts = if ("\\;" in text) chainedParts(text) else listOf(last)
      return parts.flatMap { part ->
        List(copyFromStandardInputCount(part)) { part.trim().lineSequence().first().trim() }
      }
    }

    /**
     * The last statement of [text] when [text] ends with its `;` and only comments follow, an empty string when
     * [text] holds no statement or the lexer rejects it, or `null` while the last statement is still open.
     */
    private fun terminationOf(text: String): String? {
      val statements = try {
        LibPgQuery.split(text)
      } catch (_: LibPgQueryException) {
        return ""
      }
      val last = statements.lastOrNull() ?: return ""
      val isTerminated = last.end < text.length && text[last.end] == ';' && isOnlyComments(text.substring(last.end + 1))
      return if (isTerminated) text.substring(last.start, last.end) else null
    }

    private fun isOnlyComments(text: String): Boolean = try {
      LibPgQuery.scan(text).all { it.token == SQL_COMMENT_TOKEN || it.token == C_COMMENT_TOKEN }
    } catch (_: LibPgQueryException) {
      false
    }

    /** [text] cut at each `\` directly followed by a `;`, without those two tokens. */
    private fun chainedParts(text: String): List<String> {
      val tokens = LibPgQuery.scan(text)
      val parts = mutableListOf<String>()
      var partStart = 0
      for (position in 0 until tokens.size - 1) {
        val backslash = tokens[position]
        val semicolon = tokens[position + 1]
        if (backslash.token == BACKSLASH_TOKEN &&
          semicolon.token == SEMICOLON_TOKEN &&
          semicolon.start == backslash.end
        ) {
          parts += text.substring(partStart, backslash.start)
          partStart = semicolon.end
        }
      }
      return parts + text.substring(partStart)
    }

    /** How many statements of [text] are `COPY ... FROM STDIN`. */
    private fun copyFromStandardInputCount(text: String): Int {
      // A `COPY` statement contains the word, so the parse is only needed when it appears.
      if (!text.contains("copy", ignoreCase = true)) return 0
      val tree = try {
        LibPgQuery.parse(text)
      } catch (_: LibPgQueryException) {
        return 0
      }
      return tree.objectArrayField("stmts").count { statement ->
        val copy = statement.objectField("stmt")?.objectField("CopyStmt")
        copy != null && copy.fields["is_from"] != null && copy.fields["filename"] == null
      }
    }
  }
}
