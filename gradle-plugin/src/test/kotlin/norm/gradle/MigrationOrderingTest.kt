package norm.gradle

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

class MigrationOrderingTest {

  private fun file(name: String) = File(name)

  private fun orderedNames(files: List<File>) = files.map { it.name }

  /** Orders [files] as if they were the sole contents of a single declared `schemas` directory entry. */
  private fun orderFiles(files: List<File>) = orderMigrationFiles(listOf(SchemaSource.Directory(files)))

  @Nested
  inner class VersionedMigrations {

    @Test
    fun `V2 sorts before V10`() {
      val files = listOf(file("V10__add_email.sql"), file("V2__create_author.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V2__create_author.sql", "V10__add_email.sql")
    }

    @Test
    fun `full sequence sorts by numeric version`() {
      val files = listOf(
        file("V1_10__x.sql"),
        file("V10__x.sql"),
        file("V1__x.sql"),
        file("V2__x.sql"),
        file("V1_2__x.sql"),
        file("V1_1__x.sql"),
      )

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly(
        "V1__x.sql",
        "V1_1__x.sql",
        "V1_2__x.sql",
        "V1_10__x.sql",
        "V2__x.sql",
        "V10__x.sql",
      )
    }

    @Test
    fun `shorter version prefix sorts before longer version with same prefix`() {
      val files = listOf(file("V1_1__x.sql"), file("V1__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V1__x.sql", "V1_1__x.sql")
    }

    @Test
    fun `dot and underscore separators are equivalent when comparing distinct versions`() {
      val files = listOf(file("V1.10__x.sql"), file("V1_2__x.sql"), file("V1__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V1__x.sql", "V1_2__x.sql", "V1.10__x.sql")
    }

    @Test
    fun `timestamp style versions do not overflow and sort after single-digit versions`() {
      val files = listOf(file("V20260814120000__x.sql"), file("V9__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V9__x.sql", "V20260814120000__x.sql")
    }
  }

  @Nested
  inner class DuplicateVersions {

    @Test
    fun `V1 and V1_0 are treated as the same version and rejected as a duplicate`() {
      val files = listOf(file("V1_0__b.sql"), file("V1__a.sql"))

      val exception = assertFailure { orderFiles(files) }
      exception.isInstanceOf(IllegalStateException::class)
      exception.messageContains("V1__a.sql")
      exception.messageContains("V1_0__b.sql")
    }

    @Test
    fun `two files declaring the exact same version fail the build naming both files`() {
      val files = listOf(file("V1__b.sql"), file("V1__a.sql"))

      val exception = assertFailure { orderFiles(files) }
      exception.isInstanceOf(IllegalStateException::class)
      exception.messageContains("V1__a.sql")
      exception.messageContains("V1__b.sql")
    }

    @Test
    fun `dot and underscore separators that parse identically are rejected as a duplicate`() {
      val files = listOf(file("V1.2__x.sql"), file("V1_2__y.sql"))

      val exception = assertFailure { orderFiles(files) }
      exception.isInstanceOf(IllegalStateException::class)
      exception.messageContains("V1.2__x.sql")
      exception.messageContains("V1_2__y.sql")
    }
  }

  @Nested
  inner class UnversionedFiles {

    @Test
    fun `lowercase v prefix is treated as unversioned`() {
      val files = listOf(file("v1__x.sql"), file("V1__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V1__x.sql", "v1__x.sql")
    }

    @Test
    fun `missing description after the version separator is treated as unversioned`() {
      val files = listOf(file("V__nodesc.sql"), file("V1__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V1__x.sql", "V__nodesc.sql")
    }

    @Test
    fun `unparseable version is treated as unversioned`() {
      val files = listOf(file("Vx__bad.sql"), file("V1__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V1__x.sql", "Vx__bad.sql")
    }
  }

  @Nested
  inner class MinimalPermutation {

    @Test
    fun `V10 and V2 swap into version order`() {
      val files = listOf(file("V10__x.sql"), file("V2__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V2__x.sql", "V10__x.sql")
    }

    @Test
    fun `a plain file that a later migration depends on keeps its lexical position`() {
      // Regression case: the old two-bucket rule moved every versioned file ahead of every plain file,
      // which reordered "Base.sql" (creating `author`) after "V1__add_email.sql" (altering `author`)
      // whenever "Base.sql" happened to sort lexically before all versioned files.
      val files = listOf(file("Base.sql"), file("V1__x.sql"), file("V10__x.sql"), file("V2__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly(
        "Base.sql",
        "V1__x.sql",
        "V2__x.sql",
        "V10__x.sql",
      )
    }

    @Test
    fun `a plain file that lexically sorts after every versioned file keeps its position`() {
      val files = listOf(file("V1__x.sql"), file("V10__x.sql"), file("V2__x.sql"), file("views.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly(
        "V1__x.sql",
        "V2__x.sql",
        "V10__x.sql",
        "views.sql",
      )
    }

    @Test
    fun `a lowercase plain file sorts after versioned migrations and is applied last`() {
      // "base.sql" is lowercase, so its first character ('b', 0x62) sorts after the uppercase
      // "V" (0x56) of every versioned migration. The minimal-permutation rule preserves a plain
      // file's lexical position rather than moving it earlier, so this file runs last, not first.
      val files = listOf(file("base.sql"), file("V1__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V1__x.sql", "base.sql")
    }

    @Test
    fun `plain files interleaved between versioned files each keep their own position`() {
      // "V1_setup.sql" lacks the required "__" separator, so it is unversioned, but it still sorts
      // lexically between "V10__x.sql" and "V2__x.sql".
      val files = listOf(file("A.sql"), file("V10__x.sql"), file("V1_setup.sql"), file("V2__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly(
        "A.sql",
        "V2__x.sql",
        "V1_setup.sql",
        "V10__x.sql",
      )
    }
  }

  @Nested
  inner class RepeatableMigrations {

    @Test
    fun `a repeatable migration is applied after versioned migrations`() {
      val files = listOf(file("R__view.sql"), file("V1__x.sql"), file("V2__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V1__x.sql", "V2__x.sql", "R__view.sql")
    }

    @Test
    fun `multiple repeatable migrations sort lexically among themselves`() {
      val files = listOf(file("R__zz.sql"), file("R__aa.sql"), file("V1__x.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V1__x.sql", "R__aa.sql", "R__zz.sql")
    }
  }

  @Nested
  inner class UndoMigrations {

    @Test
    fun `undo migrations are excluded from toApply and returned separately`() {
      val files = listOf(file("V1__create_author.sql"), file("U1__undo.sql"))

      val result = orderFiles(files)

      assertThat(orderedNames(result.toApply)).containsExactly("V1__create_author.sql")
      assertThat(orderedNames(result.skippedUndoMigrations)).containsExactly("U1__undo.sql")
    }

    @Test
    fun `no undo migrations produces an empty skipped list`() {
      val files = listOf(file("V1__create_author.sql"))

      val result = orderFiles(files)

      assertThat(result.skippedUndoMigrations).isEmpty()
    }
  }

  @Nested
  inner class CrossEntryOrdering {

    @Test
    fun `versioned migrations from different directory entries sort globally by version`() {
      val firstDirectory = SchemaSource.Directory(listOf(file("V10__add_email.sql")))
      val secondDirectory = SchemaSource.Directory(listOf(file("V2__create_author.sql")))

      val result = orderMigrationFiles(listOf(firstDirectory, secondDirectory))

      assertThat(orderedNames(result.toApply)).containsExactly("V2__create_author.sql", "V10__add_email.sql")
    }

    @Test
    fun `a repeatable migration in an earlier directory entry still runs after a later entry's versioned migration`() {
      // Regression case: per-entry ordering appended a directory's own repeatable migrations right after
      // that directory's own versioned migrations, before the next declared `schemas` entry was even
      // considered — so a repeatable migration in the first of two directories ran before a versioned
      // migration declared in the second, contradicting Flyway, which always applies every repeatable
      // migration after every versioned migration, across every location.
      val firstDirectory = SchemaSource.Directory(listOf(file("R__author_view.sql")))
      val secondDirectory = SchemaSource.Directory(listOf(file("V1__create_author.sql")))

      val result = orderMigrationFiles(listOf(firstDirectory, secondDirectory))

      assertThat(orderedNames(result.toApply)).containsExactly("V1__create_author.sql", "R__author_view.sql")
    }

    @Test
    fun `a single declared file keeps its declared position and is never pattern-matched`() {
      // A standalone file named like a versioned migration is not a Flyway "location", so it must not be
      // pooled into the global version sort with directory-sourced migrations.
      val standaloneFile = SchemaSource.SingleFile(file("V1__looks_versioned.sql"))
      val directory = SchemaSource.Directory(listOf(file("V2__create_author.sql")))

      val result = orderMigrationFiles(listOf(standaloneFile, directory))

      assertThat(orderedNames(result.toApply)).containsExactly("V1__looks_versioned.sql", "V2__create_author.sql")
    }

    @Test
    fun `undo migrations are pooled and skipped across every directory entry`() {
      val firstDirectory = SchemaSource.Directory(listOf(file("U1__undo.sql")))
      val secondDirectory = SchemaSource.Directory(listOf(file("V1__create_author.sql")))

      val result = orderMigrationFiles(listOf(firstDirectory, secondDirectory))

      assertThat(orderedNames(result.toApply)).containsExactly("V1__create_author.sql")
      assertThat(orderedNames(result.skippedUndoMigrations)).containsExactly("U1__undo.sql")
    }

    @Test
    fun `duplicate versions are detected across directory entries, not just within one`() {
      val firstDirectory = SchemaSource.Directory(listOf(file("V1__a.sql")))
      val secondDirectory = SchemaSource.Directory(listOf(file("V1_0__b.sql")))

      val exception = assertFailure { orderMigrationFiles(listOf(firstDirectory, secondDirectory)) }
      exception.isInstanceOf(IllegalStateException::class)
      exception.messageContains("V1__a.sql")
      exception.messageContains("V1_0__b.sql")
    }
  }
}
