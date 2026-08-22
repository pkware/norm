package norm.gradle

import java.io.File
import java.math.BigInteger

/**
 * Result of ordering the SQL files declared across all `schemas` entries of a database.
 *
 * @property toApply Files to replay against the analysis database, in the order they should be applied.
 * @property skippedUndoMigrations Flyway undo migrations (`U<version>__<description>.sql`) found in a
 * directory entry. These are never applied — Flyway itself never runs them during `migrate`, so replaying
 * them would corrupt the schema used for analysis. Empty when no entry contains an undo migration.
 */
internal data class SchemaFileOrder(val toApply: List<File>, val skippedUndoMigrations: List<File>)

/**
 * One `schemas`-declared source of SQL files, in the order [orderMigrationFiles] should consider it.
 *
 * Only the files inside a [Directory] are checked against the Flyway migration naming conventions
 * (versioned, repeatable, undo) described on [orderMigrationFiles]. A [SingleFile] entry is always applied
 * at exactly the position it was declared, even if its name happens to match one of those conventions:
 * Flyway itself only ever reorders the contents of a location (directory), never a standalone file.
 */
internal sealed interface SchemaSource {
  data class Directory(val files: List<File>) : SchemaSource

  data class SingleFile(val file: File) : SchemaSource
}

private val versionedMigrationPattern = Regex("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$")
private val undoMigrationPattern = Regex("^U([0-9]+(?:[._][0-9]+)*)__.+\\.sql$")
private val repeatableMigrationPattern = Regex("^R__.+\\.sql$")

/**
 * Returns `true` if [file]'s name matches the Flyway undo migration naming convention
 * (`U<version>__<description>.sql`).
 *
 * Exposed so that the configuration-time input declaration (`NormGenerateTask.resolveSqlInputs`) can
 * exclude undo migrations from `@InputFiles` tracking using the exact same predicate that
 * [orderMigrationFiles] uses to exclude them from the replayed file list. Keeping both call sites on one
 * predicate guarantees a file's presence as a tracked input always matches whether it is actually
 * replayed, so editing only an undo migration does not needlessly invalidate the task's up-to-date check.
 */
internal fun isUndoMigration(file: File): Boolean = undoMigrationPattern.matches(file.name)

/**
 * Orders the SQL files declared across every `schemas` entry, globally, the way Flyway orders migrations
 * merged from multiple `locations`: every versioned migration is sorted into one global sequence by
 * version, and every repeatable migration is applied after all versioned migrations, regardless of which
 * `schemas` entry either came from.
 *
 * The `V`/`U`/`R` prefixes and the `__` separator are Flyway's *defaults*; Norm does not read a Flyway
 * configuration file and has no way to know if a project has reconfigured them, so a project using
 * non-default Flyway naming will not get matching behavior here.
 *
 * This is a minimal permutation of each directory's lexical-by-filename order, not a full re-sort: every
 * file that is not a Flyway-versioned migration keeps the exact position it would have in the concatenation
 * of (a) each [SchemaSource.SingleFile] at its declared position and (b) each [SchemaSource.Directory]'s
 * files sorted lexically by filename, in `schemaSources` order. Only the slots occupied by versioned
 * migrations are reordered among themselves, by numeric version, globally across every directory. This
 * avoids reordering a versioned migration relative to a plain file it depends on (e.g. a `base.sql` that a
 * later `V1__add_column.sql` alters) purely because that plain file's name happens to sort after some other
 * version number — declared entry order, and lexical order within a directory, still governs plain files.
 *
 * Rules:
 * 1. Files matching the Flyway undo migration naming convention (`U<version>__<description>.sql`) are
 *    excluded from [SchemaFileOrder.toApply] and returned in [SchemaFileOrder.skippedUndoMigrations]
 *    instead, since Flyway never applies these during a normal `migrate`.
 * 2. Files matching the Flyway repeatable migration naming convention (`R__<description>.sql`) are
 *    removed from the main sequence and appended once at the very end, sorted lexically by filename among
 *    themselves, since Flyway always applies repeatable migrations after every versioned migration in
 *    every location.
 * 3. The remaining files are sorted lexically by filename within each [SchemaSource.Directory], then
 *    concatenated across `schemaSources` in declared order. Within that sequence, the slots holding a
 *    Flyway-versioned migration (`V<version>__<description>.sql`, where `<version>` is one or more digit
 *    groups separated by `.` or `_`) are identified across every directory, and just those files are
 *    re-sorted ascending by numeric version — comparing version parts pairwise, padding the shorter
 *    version with trailing zeros so `V1` and `V1_0` compare as the same version (matching Flyway, which
 *    treats them as one version) — then written back into those same slots, in order. Every other file
 *    (plain files such as `base.sql`, any `V...` file whose version fails to parse, and every
 *    [SchemaSource.SingleFile]) keeps its position in the sequence unchanged.
 * 4. Two versioned migrations that compare as the same version — whether because they parse to the exact
 *    same value, or because padding makes them equal (`V1` and `V1_0`) — are a real Flyway error: Flyway
 *    refuses to apply two migrations claiming the same version. Silently picking one over the other would
 *    replay a schema Flyway itself would never produce, so [orderMigrationFiles] throws
 *    [IllegalStateException] naming both files instead.
 *
 * The `V`/`U`/`R` prefixes are matched case-sensitively, matching Flyway's default configuration; a
 * lowercase `v1__x.sql` or `u1__x.sql` is treated as a plain file.
 *
 * @throws IllegalStateException if two versioned migrations compare as the same version.
 */
internal fun orderMigrationFiles(schemaSources: List<SchemaSource>): SchemaFileOrder {
  val skippedUndoMigrations = mutableListOf<File>()
  val repeatableMigrations = mutableListOf<File>()
  val mainList = mutableListOf<MainListEntry>()

  for (source in schemaSources) {
    when (source) {
      is SchemaSource.SingleFile -> mainList.add(MainListEntry(source.file, eligibleForVersionMatch = false))
      is SchemaSource.Directory ->
        for (file in source.files.sortedBy { it.name }) {
          when {
            isUndoMigration(file) -> skippedUndoMigrations.add(file)
            repeatableMigrationPattern.matches(file.name) -> repeatableMigrations.add(file)
            else -> mainList.add(MainListEntry(file, eligibleForVersionMatch = true))
          }
        }
    }
  }

  val versionedSlotIndices = mutableListOf<Int>()
  val versionedFilesByVersion = mutableListOf<Pair<List<BigInteger>, File>>()
  mainList.forEachIndexed { index, entry ->
    if (!entry.eligibleForVersionMatch) return@forEachIndexed
    val version = versionedMigrationPattern.matchEntire(entry.file.name)
      ?.groupValues
      ?.get(1)
      ?.let(::parseVersion)
    if (version != null) {
      versionedSlotIndices.add(index)
      versionedFilesByVersion.add(version to entry.file)
    }
  }

  val filesSortedByVersion = versionedFilesByVersion.sortedWith(compareBy(VersionComparator) { it.first })
  failOnDuplicateVersions(filesSortedByVersion)

  versionedSlotIndices.forEachIndexed { position, slotIndex ->
    mainList[slotIndex] = MainListEntry(filesSortedByVersion[position].second, eligibleForVersionMatch = true)
  }

  val orderedRepeatableMigrations = repeatableMigrations.sortedBy { it.name }

  return SchemaFileOrder(mainList.map { it.file } + orderedRepeatableMigrations, skippedUndoMigrations)
}

/**
 * One file inside [orderMigrationFiles]'s working sequence.
 *
 * @property eligibleForVersionMatch Whether this file should be checked against
 * [versionedMigrationPattern]. `true` for every file expanded from a [SchemaSource.Directory]; `false` for
 * a [SchemaSource.SingleFile], which is never reordered regardless of its name.
 */
private data class MainListEntry(val file: File, val eligibleForVersionMatch: Boolean)

/**
 * Throws [IllegalStateException] naming both files if any two entries of [sortedByVersion] — sorted
 * ascending by [VersionComparator] — compare as the same version.
 */
private fun failOnDuplicateVersions(sortedByVersion: List<Pair<List<BigInteger>, File>>) {
  for (index in 1 until sortedByVersion.size) {
    val (previousVersion, previousFile) = sortedByVersion[index - 1]
    val (version, file) = sortedByVersion[index]
    if (VersionComparator.compare(previousVersion, version) == 0) {
      throw IllegalStateException(
        "Norm: Found more than one schema file with version ${version.joinToString(".")}: " +
          "'${previousFile.path}' and '${file.path}'. Flyway would reject this as a duplicate migration " +
          "version; rename one of the files to a distinct version.",
      )
    }
  }
}

/**
 * Parses a Flyway migration version string (the digit groups captured by [versionedMigrationPattern] or
 * [undoMigrationPattern], e.g. `"1_2"` or `"1.2"`) into its numeric parts.
 *
 * Both patterns require every part to be one or more digits, so every substring produced by splitting on
 * `.` or `_` is guaranteed to parse as a [BigInteger]; this function never fails.
 */
private fun parseVersion(version: String): List<BigInteger> = version.split('.', '_').map { it.toBigInteger() }

/**
 * Compares two Flyway migration versions part by part, treating a missing trailing part as `0` — so `V1`
 * and `V1_0` compare as equal, matching Flyway, which treats them as the same version.
 */
private object VersionComparator : Comparator<List<BigInteger>> {
  override fun compare(left: List<BigInteger>, right: List<BigInteger>): Int {
    val length = maxOf(left.size, right.size)
    for (index in 0 until length) {
      val leftPart = left.getOrElse(index) { BigInteger.ZERO }
      val rightPart = right.getOrElse(index) { BigInteger.ZERO }
      val comparison = leftPart.compareTo(rightPart)
      if (comparison != 0) return comparison
    }
    return 0
  }
}
