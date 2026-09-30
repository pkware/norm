package norm.gradle

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SchemaSourceResolutionTest {

  @TempDir
  private lateinit var projectDirectory: Path

  private fun createDirectory(name: String): File = Files.createDirectories(projectDirectory.resolve(name)).toFile()

  private fun createFile(parent: File, name: String): File = parent.resolve(name).apply { writeText("SELECT 1;") }

  @Nested
  inner class Directories {

    @Test
    fun `directory becomes a Directory source of its direct children`() {
      val migrations = createDirectory("migrations")
      val first = createFile(migrations, "V1__a.sql")
      val second = createFile(migrations, "V2__b.sql")

      val sources = resolveSchemaSources(projectDirectory.toFile(), listOf("migrations"), setOf(first, second))

      assertThat(sources).containsExactly(SchemaSource.Directory(listOf(first, second)))
    }

    @Test
    fun `files from other directories and nested directories are excluded`() {
      val migrations = createDirectory("migrations")
      val other = createDirectory("other")
      val nested = createDirectory("migrations/nested")
      val direct = createFile(migrations, "V1__a.sql")
      val otherFile = createFile(other, "V2__b.sql")
      val nestedFile = createFile(nested, "V3__c.sql")

      val sources = resolveSchemaSources(
        projectDirectory.toFile(),
        listOf("migrations"),
        setOf(direct, otherFile, nestedFile),
      )

      assertThat(sources).containsExactly(SchemaSource.Directory(listOf(direct)))
    }

    @Test
    fun `directory without matching files becomes an empty Directory source`() {
      createDirectory("migrations")

      val sources = resolveSchemaSources(projectDirectory.toFile(), listOf("migrations"), emptySet())

      assertThat(sources).containsExactly(SchemaSource.Directory(emptyList()))
    }

    @Test
    fun `two declared directories keep their declared order`() {
      val first = createDirectory("first")
      val second = createDirectory("second")
      val firstFile = createFile(first, "V1__a.sql")
      val secondFile = createFile(second, "V2__b.sql")

      val sources = resolveSchemaSources(
        projectDirectory.toFile(),
        listOf("second", "first"),
        setOf(firstFile, secondFile),
      )

      assertThat(sources).containsExactly(
        SchemaSource.Directory(listOf(secondFile)),
        SchemaSource.Directory(listOf(firstFile)),
      )
    }
  }

  @Nested
  inner class SingleFiles {

    @Test
    fun `file path becomes a SingleFile source regardless of the SQL files`() {
      val base = createFile(projectDirectory.toFile(), "base.sql")

      val sources = resolveSchemaSources(projectDirectory.toFile(), listOf("base.sql"), emptySet())

      assertThat(sources).containsExactly(SchemaSource.SingleFile(base))
    }

    @Test
    fun `file matching a migration name stays a SingleFile source`() {
      val versioned = createFile(projectDirectory.toFile(), "V1__a.sql")

      val sources = resolveSchemaSources(projectDirectory.toFile(), listOf("V1__a.sql"), setOf(versioned))

      assertThat(sources).containsExactly(SchemaSource.SingleFile(versioned))
    }

    @Test
    fun `path that does not exist becomes a SingleFile source`() {
      val sources = resolveSchemaSources(projectDirectory.toFile(), listOf("missing.sql"), emptySet())

      assertThat(sources).containsExactly(SchemaSource.SingleFile(projectDirectory.resolve("missing.sql").toFile()))
    }

    @Test
    fun `no declared paths produce no sources`() {
      assertThat(resolveSchemaSources(projectDirectory.toFile(), emptyList(), emptySet())).isEmpty()
    }
  }

  @Nested
  inner class PathResolution {

    @Test
    fun `relative path resolves against the project directory`() {
      assertThat(resolveDeclaredPath(projectDirectory.toFile(), "db/schema.sql"))
        .isEqualTo(projectDirectory.resolve("db/schema.sql").toFile())
    }

    @Test
    fun `parent segments are normalized`() {
      assertThat(resolveDeclaredPath(projectDirectory.toFile(), "../shared/./schema.sql"))
        .isEqualTo(projectDirectory.parent.resolve("shared/schema.sql").toFile())
    }

    @Test
    fun `absolute path is returned as declared`() {
      val absolute = projectDirectory.resolve("elsewhere/schema.sql")

      assertThat(resolveDeclaredPath(File("/unrelated"), absolute.toString())).isEqualTo(absolute.toFile())
    }

    @Test
    fun `directory declared with parent segments is matched against its files`() {
      val migrations = createDirectory("migrations")
      val file = createFile(migrations, "V1__a.sql")

      val declaredPath = "../${projectDirectory.fileName}/migrations"

      val sources = resolveSchemaSources(projectDirectory.toFile(), listOf(declaredPath), setOf(file))

      assertThat(sources).containsExactly(SchemaSource.Directory(listOf(file)))
    }
  }
}
