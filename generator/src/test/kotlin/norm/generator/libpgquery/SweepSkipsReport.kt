package norm.generator.libpgquery

/**
 * Lists what the sweep leaves out of the regress files: the dropped `COPY` data blocks, the dropped lines of inactive
 * `\if` branches and the skipped statements. Each is one tab-separated line, as
 * `<major>/<file> <kind> <position> <first line>`. The kind is `P2` for a data block, `P5` for an inactive branch, `P3`
 * for a statement with a psql variable and `P4` for a statement with a `\`. The position is a line range for `P2`
 * and `P5`, and a statement ordinal otherwise.
 */
internal object SweepSkipsReport {

  fun render(): String = buildString {
    for (major in PgRegress.majors) {
      for (file in PgRegress.files(major)) {
        val name = "$major/${file.name}"
        for (block in file.copyBlocks) {
          appendLine("$name\tP2\t${block.firstLine}-${block.lastLine}\t${singleLine(block.copyStatement)}")
        }
        for (branch in file.inactiveBranches) {
          appendLine("$name\tP5\t${branch.firstLine}-${branch.lastLine}\t${singleLine(branch.firstCode)}")
        }
        for (statement in file.statements) {
          val kind = when (statement.skip) {
            SweepSkip.PSQL_VARIABLE -> "P3"
            SweepSkip.BACKSLASH -> "P4"
            null -> continue
          }
          appendLine("$name\t$kind\t${statement.ordinal}\t${singleLine(statement.firstLine)}")
        }
      }
    }
  }
}

/** Escapes backslashes, tabs and carriage returns so [text] fits one tab-separated field. */
internal fun singleLine(text: String): String = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r")

/** Reverses [singleLine]. */
internal fun unescapeSingleLine(text: String): String = buildString {
  var index = 0
  while (index < text.length) {
    val character = text[index]
    if (character == '\\' && index + 1 < text.length) {
      index++
      append(
        when (val escaped = text[index]) {
          't' -> '\t'
          'r' -> '\r'
          else -> escaped
        },
      )
    } else {
      append(character)
    }
    index++
  }
}
