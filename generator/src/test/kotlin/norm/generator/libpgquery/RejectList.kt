package norm.generator.libpgquery

/**
 * A regress statement that PostgreSQL rejects with an error, so the bundled module is right to reject it too.
 *
 * @property major PostgreSQL major version of the regress file.
 * @property file regress file name, for example `select.sql`.
 * @property ordinal [RegressStatement.ordinal] of the statement.
 * @property firstLine [RegressStatement.firstLine] of the statement, which anchors the entry in the output file and
 *   detects a shifted ordinal.
 * @property outputFile name of the expected-output file, for example `select.out`.
 * @property outputLine 1-based line of [outputFile] that holds the error the server reported. See [isJustified].
 */
internal data class RejectEntry(
  val major: String,
  val file: String,
  val ordinal: Int,
  val firstLine: String,
  val outputFile: String,
  val outputLine: Int,
) {
  val statementKey: String get() = "$major/$file#$ordinal"

  /**
   * Whether [outputLines], the lines of [outputFile], justify the entry: the cited line starts with `ERROR:`, and
   * [firstLine], which is not a `--` comment, occurs in a line before it. Between that line and the cited one there
   * is no other `ERROR:` line and no line that holds [nextFirstLine].
   *
   * @param nextFirstLine the first line of the next statement of the file, or `null` for the last statement.
   */
  fun isJustified(outputLines: List<String>, nextFirstLine: String?): Boolean {
    val cited = outputLines.getOrNull(outputLine - 1) ?: return false
    val anchor = unescapeSingleLine(firstLine)
    if (!cited.startsWith("ERROR:") || anchor.startsWith("--")) return false
    val next = nextFirstLine?.takeIf { it.isNotEmpty() }
    for (index in outputLine - 2 downTo 0) {
      if (outputLines[index].startsWith("ERROR:")) return false
      if (outputLines[index].contains(anchor)) return true
      if (next != null && outputLines[index].contains(next)) return false
    }
    return false
  }
}

/**
 * Reads `pg-regress/reject-list.txt`. Each line that does not start with `#` holds four tab-separated fields:
 * `<major>/<file>`, the statement ordinal, the first line of the statement as [singleLine] writes it, and
 * `expected/<file>.out:<line>`.
 */
internal object RejectList {
  private const val FIELD_COUNT = 4

  fun load(): List<RejectEntry> {
    val stream = checkNotNull(RejectList::class.java.getResourceAsStream("/pg-regress/reject-list.txt")) {
      "pg-regress/reject-list.txt is missing"
    }
    val text = stream.use { String(it.readBytes(), Charsets.UTF_8) }.replace("\r\n", "\n")
    return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map(::parseLine)
  }

  private fun parseLine(line: String): RejectEntry {
    val fields = line.split('\t')
    require(fields.size == FIELD_COUNT) { "Reject list line has ${fields.size} fields instead of $FIELD_COUNT: $line" }
    val (major, file) = fields[0].split('/', limit = 2)
    val (outputPath, outputLine) = fields[3].substringAfter("expected/").split(':', limit = 2)
    return RejectEntry(major, file, fields[1].toInt(), fields[2], outputPath, outputLine.toInt())
  }
}
