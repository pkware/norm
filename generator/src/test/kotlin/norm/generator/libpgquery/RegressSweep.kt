package norm.generator.libpgquery

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** The tokens next to a `:` that make it a psql variable reference. */
private val NAME_TOKENS = setOf(
  TokenNumber.IDENTIFIER,
  TokenNumber.UNICODE_IDENTIFIER,
  TokenNumber.STRING,
  TokenNumber.UNICODE_STRING,
)

/** Why the sweep does not parse a statement of a regress file. */
internal enum class SweepSkip {
  /** The statement holds a `:` directly next to a name or string, which psql replaces with a variable's value. */
  PSQL_VARIABLE,

  /** The statement holds a `\`, which starts a psql command in the middle of a line. */
  BACKSLASH,
}

/**
 * A statement of a preprocessed regress file.
 *
 * @property ordinal 1-based position in the file's statements, counting each region that the tokenizer rejected.
 * @property text the statement as it appears in the preprocessed file. For a rejected region, the whole region.
 * @property skip why the sweep does not parse it, or `null` when it must parse.
 * @property isTokenizerRejected whether the lexer rejects [text], so that [LibPgQuery.split] had to drop it.
 */
internal data class RegressStatement(
  val ordinal: Int,
  val text: String,
  val skip: SweepSkip?,
  val isTokenizerRejected: Boolean = false,
) {
  /** The first line of the statement that is neither blank nor a `--` comment, without surrounding whitespace. */
  val firstLine: String = firstCodeLine(text)
}

/**
 * The lines of a `COPY ... FROM STDIN` data block that preprocessing dropped.
 *
 * @property firstLine 1-based line of the file where the dropped block starts.
 * @property lastLine 1-based line of the file where the dropped block ends, which is the `\.` line when present.
 * @property copyStatement the first line of the `COPY` statement that the block belongs to.
 */
internal data class CopyDataBlock(val firstLine: Int, val lastLine: Int, val copyStatement: String)

/**
 * What psql skipped in an inactive branch of an `\if` block, which preprocessing dropped: a run of lines that holds
 * SQL, or an `\if` or `\elif` line whose condition is not a boolean literal.
 *
 * @property firstLine 1-based line of the file where it starts.
 * @property lastLine 1-based line of the file where it ends.
 * @property firstCode the first line that is neither blank nor a `--` comment, without surrounding whitespace. For a
 *   condition, the command line.
 */
internal data class InactiveBranch(val firstLine: Int, val lastLine: Int, val firstCode: String)

/**
 * A regress file after preprocessing.
 *
 * @property major PostgreSQL major version the file comes from.
 * @property name file name, for example `select.sql`.
 * @property statements the statements of the preprocessed text.
 * @property copyBlocks the data blocks that preprocessing dropped.
 * @property inactiveBranches the inactive `\if` branches that preprocessing dropped.
 * @property regrownRegions how many `BEGIN ATOMIC` regions were re-split with the grammar.
 */
internal data class RegressFile(
  val major: String,
  val name: String,
  val statements: List<RegressStatement>,
  val copyBlocks: List<CopyDataBlock>,
  val inactiveBranches: List<InactiveBranch>,
  val regrownRegions: Int,
)

/** The vendored `src/test/regress/sql` files of PostgreSQL, prepared the way the sweep reads them. */
internal object PgRegress {
  val majors = listOf("16", "17")

  private val preparedByMajor = ConcurrentHashMap<String, List<RegressFile>>()

  // `Path` ordering ignores case on Windows, which would reorder the skip list.
  fun sqlFiles(major: String): List<Path> =
    directory(major, "sql").listDirectoryEntries("*.sql").sortedBy { it.fileName.toString() }

  /** The prepared files of [major], computed once per run. */
  fun files(major: String): List<RegressFile> =
    preparedByMajor.computeIfAbsent(major) { sqlFiles(major).map { prepare(major, it) } }

  /** The text of `pg-regress/<major>/expected/<name>` with `\r\n` read as `\n`. */
  fun expectedOutput(major: String, name: String): List<String>? =
    directoryOrNull(major, "expected")?.resolve(name)?.takeIf { it.toFile().exists() }
      ?.let { readLines(it) }

  fun readLines(path: Path): List<String> = readText(path).lines()

  fun readText(path: Path): String = String(path.toFile().readBytes(), Charsets.UTF_8).replace("\r\n", "\n")

  private fun directoryOrNull(major: String, child: String): Path? =
    PgRegress::class.java.getResource("/pg-regress/$major/$child")?.let { Path.of(it.toURI()) }

  private fun directory(major: String, child: String): Path =
    checkNotNull(directoryOrNull(major, child)) { "pg-regress/$major/$child is missing" }

  fun prepare(major: String, path: Path): RegressFile {
    val psql = PsqlLines.of(readText(path).lines())
    val split = SplitFile.of(psql.text)
    var regrownRegions = 0
    val statements = mutableListOf<RegressStatement>()
    var index = 0
    while (index < split.items.size) {
      val item = split.items[index]
      if (item is SplitItem.Rejected) {
        statements += RegressStatement(statements.size + 1, item.text, null, isTokenizerRejected = true)
        index++
        continue
      }
      val text = split.text.substring((item as SplitItem.Scanned).start, item.end)
      val skip = skipReason(text)
      val regrowth = if (skip == null && isFailingAtomic(text)) regrow(split, index) else null
      if (regrowth == null) {
        statements += RegressStatement(statements.size + 1, text, skip)
        index++
      } else {
        regrownRegions++
        regrowth.texts.forEach { statements += RegressStatement(statements.size + 1, it, skipReason(it)) }
        index = regrowth.nextIndex
      }
    }
    return RegressFile(major, path.name, statements, psql.copyBlocks, psql.inactiveBranches, regrownRegions)
  }

  private fun parses(statement: String): Boolean = try {
    LibPgQuery.parse(statement)
    true
  } catch (_: LibPgQueryException) {
    false
  }

  /** Whether [statement] holds the keyword `ATOMIC` and does not parse, as a function body cut at a `;` does. */
  private fun isFailingAtomic(statement: String): Boolean {
    if (!statement.contains("atomic", ignoreCase = true)) return false
    val hasKeyword = LibPgQuery.scan(statement).any {
      it.keywordKind != KeywordKind.NONE && statement.substring(it.start, it.end).equals("ATOMIC", ignoreCase = true)
    }
    return hasKeyword && !parses(statement)
  }

  /**
   * Grows a region from the scanner statement at [from] one scanner statement at a time until the grammar splitter
   * accepts it and each statement it returns parses.
   *
   * @return the statements of that region, or `null` when no region up to the end of the file qualifies.
   */
  private fun regrow(split: SplitFile, from: Int): Regrowth? {
    val first = split.items[from] as SplitItem.Scanned
    for (last in from + 1 until split.items.size) {
      val end = (split.items[last] as? SplitItem.Scanned)?.end ?: return null
      val region = split.text.substring(first.start, end)
      val parts = try {
        LibPgQuery.splitWithParser(region)
      } catch (_: LibPgQueryException) {
        continue
      }
      val texts = parts.map { region.substring(it.start, it.end) }
      if (texts.all(::parses)) return Regrowth(texts, last + 1)
    }
    return null
  }

  private class Regrowth(val texts: List<String>, val nextIndex: Int)

  private fun skipReason(statement: String): SweepSkip? {
    // Neither token can occur without its character, so most statements skip the scan.
    if (':' !in statement && '\\' !in statement) return null
    val tokens = LibPgQuery.scan(statement)
    if (isPsqlVariablePresent(tokens, statement)) return SweepSkip.PSQL_VARIABLE
    if (tokens.any { it.token == TokenNumber.BACKSLASH }) return SweepSkip.BACKSLASH
    return null
  }

  private fun isPsqlVariablePresent(tokens: List<ScanToken>, statement: String): Boolean =
    tokens.withIndex().any { (index, token) ->
      token.token == TokenNumber.COLON &&
        statement.substring(token.start, token.end) == ":" &&
        (isNameBefore(tokens, index) || isNameAfter(tokens, index))
    }

  private fun isNameBefore(tokens: List<ScanToken>, index: Int): Boolean =
    tokens.getOrNull(index - 1)?.let { it.end == tokens[index].start && isName(it) } == true

  private fun isNameAfter(tokens: List<ScanToken>, index: Int): Boolean =
    tokens.getOrNull(index + 1)?.let { it.start == tokens[index].end && isName(it) } == true

  private fun isName(token: ScanToken): Boolean = token.token in NAME_TOKENS || token.keywordKind != KeywordKind.NONE
}

/**
 * The first line of [text] that is neither blank nor a `--` comment, without surrounding whitespace. Falls back to the
 * first line when every line is blank or a comment.
 */
internal fun firstCodeLine(text: String): String {
  val lines = text.lineSequence().map { it.trim() }
  return lines.firstOrNull { it.isNotEmpty() && !it.startsWith("--") } ?: text.trim().lineSequence().first()
}
