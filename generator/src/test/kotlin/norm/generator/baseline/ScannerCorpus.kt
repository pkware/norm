package norm.generator.baseline

import norm.generator.QueryFileParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * One input to the scanner functions the baseline records.
 *
 * @property key where the text comes from, for example `test-scenarios/ctes/queries.sql#getCte`.
 * @property text the input, which may be a whole query file or a single query.
 */
internal data class CorpusEntry(val key: String, val text: String)

/** Test source files the literal corpus entries were taken from. Their names prefix the entry keys. */
internal val LITERAL_SOURCE_FILES = listOf(
  "SqlOutputClauseTest",
  "SqlStarItemTest",
  "SqlPlaceholdersTest",
  "SqlStatementScannerTest",
  "SqlCteClauseTest",
  "QueryFileParserTest",
)

private val SCENARIO_ROOTS = listOf("test-scenarios", "test-scenarios-frameworks")

/** Paths are relative to the working directory of the `generator` module, which Gradle sets for its tests. */
internal object ScannerCorpus {
  private val repositoryRoot: Path = Path("..").toAbsolutePath().normalize()

  /** Every entry, ordered by key. */
  fun load(): List<CorpusEntry> {
    val entries = scenarioEntries() + literalEntries() + issueEntries()
    val duplicate = entries.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }
    check(duplicate == null) { "Corpus key ${duplicate?.key} is used more than once" }
    return entries.sortedBy { it.key }
  }

  /** The scenario directories that hold a `queries.sql`, as `<root>/<scenario>`. */
  fun scenarioDirectories(): List<Path> = SCENARIO_ROOTS.flatMap { root ->
    repositoryRoot.resolve(root).listDirectoryEntries()
      .filter { it.isDirectory() && Files.exists(it.resolve("queries.sql")) }
      .sorted()
  }

  fun scenarioKey(directory: Path): String = repositoryRoot.relativize(directory).joinToString("/")

  private fun scenarioEntries(): List<CorpusEntry> = scenarioDirectories().flatMap { directory ->
    val fileKey = "${scenarioKey(directory)}/queries.sql"
    val content = directory.resolve("queries.sql").readText()
    listOf(CorpusEntry(fileKey, content)) +
      QueryFileParser.parse(content, "").map { CorpusEntry("$fileKey#${it.name}", it.sql) }
  }

  private fun literalEntries(): List<CorpusEntry> {
    val resource =
      checkNotNull(ScannerCorpus::class.java.getResourceAsStream(ScannerBaselineFiles.LITERAL_CORPUS_RESOURCE)) {
        "The literal corpus is missing from the test resources."
      }
    val lines = resource.use { String(it.readBytes(), Charsets.UTF_8) }.lines().filter { it.isNotEmpty() }
    return lines.chunked(2).map { (keyLine, textLine) ->
      check(keyLine.startsWith("=== ") && textLine.startsWith("text: ")) { "Malformed literal corpus near $keyLine" }
      CorpusEntry(keyLine.removePrefix("=== "), ScannerBaseline.unescape(textLine.removePrefix("text: ")))
    }
  }

  private fun issueEntries(): List<CorpusEntry> =
    KEYWORD_ISSUE_INPUTS.mapIndexed { index, text -> CorpusEntry("issue-331#${index + 1}", text) } +
      QUERY_BODY_ISSUE_INPUTS.mapIndexed { index, text -> CorpusEntry("issue-419#${index + 1}", text) } +
      GLUED_ISSUE_INPUTS.mapIndexed { index, text -> CorpusEntry("issue-glued#${index + 1}", text) } +
      CorpusEntry("issue-459", BLOCK_COMMENT_ISSUE_INPUT)
}
