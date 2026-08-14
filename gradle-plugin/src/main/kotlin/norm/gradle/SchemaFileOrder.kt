package norm.gradle

import java.io.File
import java.math.BigInteger

/**
 * Result of ordering the SQL files of a single schema directory entry.
 *
 * @property toApply Files to replay against the analysis database, in the order they should be applied.
 * @property skippedUndoMigrations Flyway undo migrations (`U<version>__<description>.sql`) found in the
 * directory. These are never applied — Flyway itself never runs them during `migrate`, so replaying them
 * would corrupt the schema used for analysis. Empty when the directory contains no undo migrations.
 */
internal data class SchemaFileOrder(val toApply: List<File>, val skippedUndoMigrations: List<File>)

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
 * Orders the SQL files of a single schema directory entry to match how Flyway would apply them.
 *
 * This is a minimal permutation of the lexical-by-filename order, not a full re-sort: every file that
 * is not a Flyway-versioned migration keeps the exact position it would have in a plain lexical sort of
 * the directory. Only the slots occupied by versioned migrations are reordered among themselves, by
 * numeric version. This avoids reordering a versioned migration relative to a plain file it depends on
 * (e.g. a `base.sql` that a later `V1__add_column.sql` alters) purely because that plain file's name
 * happens to sort after some other version number.
 *
 * Rules:
 * 1. Files matching the Flyway undo migration naming convention (`U<version>__<description>.sql`) are
 *    excluded from [SchemaFileOrder.toApply] and returned in [SchemaFileOrder.skippedUndoMigrations]
 *    instead, since Flyway never applies these during a normal `migrate`.
 * 2. Files matching the Flyway repeatable migration naming convention (`R__<description>.sql`) are
 *    removed from the main list and appended at the end, sorted lexically by filename among themselves,
 *    since Flyway always applies repeatable migrations after all versioned ones.
 * 3. The remaining files are sorted lexically by filename. Within that order, the slots holding a
 *    Flyway-versioned migration (`V<version>__<description>.sql`, where `<version>` is one or more digit
 *    groups separated by `.` or `_`) are identified, and just those files are re-sorted ascending by
 *    numeric version — element-wise, so `V2__x.sql` sorts before `V10__x.sql`, and a version that is a
 *    prefix of another sorts first (`V1__x.sql` before `V1_1__x.sql`); equal versions tie-break lexically
 *    by filename — then written back into those same slots. Every other file (plain files such as
 *    `base.sql`, and any `V...` file whose version fails to parse) keeps its lexical position unchanged.
 *
 * The `V`/`U`/`R` prefixes are matched case-sensitively, matching Flyway's default configuration; a
 * lowercase `v1__x.sql` or `u1__x.sql` is treated as a plain file.
 */
internal fun orderMigrationFiles(files: List<File>): SchemaFileOrder {
  val skippedUndoMigrations = mutableListOf<File>()
  val repeatableMigrations = mutableListOf<File>()
  val mainList = mutableListOf<File>()

  for (file in files) {
    when {
      isUndoMigration(file) -> skippedUndoMigrations.add(file)
      repeatableMigrationPattern.matches(file.name) -> repeatableMigrations.add(file)
      else -> mainList.add(file)
    }
  }

  mainList.sortBy { it.name }

  val versionedSlotIndices = mutableListOf<Int>()
  val versionedFilesByVersion = mutableListOf<Pair<List<BigInteger>, File>>()
  mainList.forEachIndexed { index, file ->
    val version = versionedMigrationPattern.matchEntire(file.name)
      ?.groupValues
      ?.get(1)
      ?.let(::parseVersion)
    if (version != null) {
      versionedSlotIndices.add(index)
      versionedFilesByVersion.add(version to file)
    }
  }

  val versionOnly: Comparator<Pair<List<BigInteger>, File>> = Comparator { left, right ->
    VersionComparator.compare(left.first, right.first)
  }
  val filesSortedByVersion = versionedFilesByVersion
    .sortedWith(versionOnly.thenBy { it.second.name })
    .map { it.second }

  versionedSlotIndices.forEachIndexed { position, slotIndex ->
    mainList[slotIndex] = filesSortedByVersion[position]
  }

  val orderedRepeatableMigrations = repeatableMigrations.sortedBy { it.name }

  return SchemaFileOrder(mainList + orderedRepeatableMigrations, skippedUndoMigrations)
}

private fun parseVersion(version: String): List<BigInteger>? =
  version.split('.', '_').map { part -> part.toBigIntegerOrNull() ?: return null }

private object VersionComparator : Comparator<List<BigInteger>> {
  override fun compare(left: List<BigInteger>, right: List<BigInteger>): Int {
    val length = minOf(left.size, right.size)
    for (index in 0 until length) {
      val comparison = left[index].compareTo(right[index])
      if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
  }
}
