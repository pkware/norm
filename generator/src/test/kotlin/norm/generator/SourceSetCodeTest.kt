package norm.generator

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.hasClass
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SourceSetCodeTest {

  private val authorTable =
    Table(Identifier(name = "author"), listOf(column("id", "author", "int4"), column("name", "author")))

  private val bookTable =
    Table(Identifier(name = "book"), listOf(column("id", "book", "int4"), column("title", "book")))

  private val moodEnum = Enum("mood", listOf("sad", "happy"))

  private val catalog = Catalog(listOf(Schema("public", listOf(authorTable, bookTable), listOf(moodEnum))))

  private fun column(name: String, table: String? = null, type: String = "text") = Column(
    name = name,
    notNull = true,
    type = Identifier(name = type),
    table = table?.let { Identifier(name = it) },
  )

  private fun starQuery(queryName: String, table: Table) =
    Query(name = queryName, cmd = Command.MANY, text = "SELECT * FROM ${table.rel.name}", columns = table.columns)

  private fun generate(
    mainQueries: List<Query>,
    sourceSetQueries: List<Query>,
    frameworks: Set<Framework> = emptySet(),
  ): List<GeneratedFile> =
    generateSourceSetCode(catalog, mainQueries, sourceSetQueries, "test", "example", frameworks, emptySet())

  private fun List<GeneratedFile>.byType(typeName: String): GeneratedFile = single { it.name == "example/$typeName.kt" }

  @Nested
  inner class Naming {

    @Test
    fun `generates TestQueries and PostgresTestQueries`() {
      val files = generate(emptyList(), listOf(starQuery("listBooks", bookTable)))

      assertThat(files.map { it.name }).containsExactlyInAnyOrder(
        "example/TestQueries.kt",
        "example/PostgresTestQueries.kt",
        "example/Book.kt",
      )
      assertThat(files.byType("PostgresTestQueries").contents).all {
        contains("public class PostgresTestQueries(")
        contains("TestQueries {")
        contains("connectionProvider: ConnectionProvider,")
      }
    }

    @Test
    fun `capitalizes the source set name`() {
      val files = generateSourceSetCode(
        catalog,
        emptyList(),
        emptyList(),
        "integrationTest",
        "example",
        emptySet(),
        emptySet(),
      )

      assertThat(files.map { it.name }).containsExactlyInAnyOrder(
        "example/IntegrationTestQueries.kt",
        "example/PostgresIntegrationTestQueries.kt",
      )
    }
  }

  @Nested
  inner class Frameworks {

    @Test
    fun `Micronaut skips the implementation when a TestQueries bean exists`() {
      val files = generate(emptyList(), listOf(starQuery("listBooks", bookTable)), setOf(Framework.MICRONAUT_DATA))

      assertThat(files.byType("PostgresTestQueries").contents)
        .contains("missingBeans = [TestQueries::class]")
    }

    @Test
    fun `generates no connection provider`() {
      val frameworks = setOf(Framework.MICRONAUT_DATA, Framework.SPRING_DATA, Framework.MICRONAUT)
      val files = generate(emptyList(), emptyList(), frameworks)

      assertThat(files.map { it.name }).containsExactlyInAnyOrder(
        "example/TestQueries.kt",
        "example/PostgresTestQueries.kt",
      )
    }
  }

  @Nested
  inner class SharedTypes {

    @Test
    fun `omits a table type that main generates identically`() {
      val sourceSetQueries = listOf(starQuery("listAuthorsForTest", authorTable))

      val withoutMain = generate(emptyList(), sourceSetQueries)
      val withMain = generate(listOf(starQuery("listAuthors", authorTable)), sourceSetQueries)

      assertThat(withoutMain.map { it.name }).contains("example/Author.kt")
      assertThat(withMain.map { it.name }).doesNotContain("example/Author.kt")
    }

    @Test
    fun `emits a table type that only the source set queries`() {
      val files = generate(listOf(starQuery("listAuthors", authorTable)), listOf(starQuery("listBooks", bookTable)))

      assertThat(files.map { it.name }).contains("example/Book.kt")
    }

    @Test
    fun `emits an enum and its adapter that only the source set queries`() {
      val moodQuery = Query(
        name = "currentMood",
        cmd = Command.ONE,
        text = "SELECT mood FROM person",
        columns = listOf(column("mood", type = "mood")),
      )

      val files = generate(listOf(starQuery("listAuthors", authorTable)), listOf(moodQuery))

      assertThat(files.map { it.name }).containsExactlyInAnyOrder(
        "example/TestQueries.kt",
        "example/PostgresTestQueries.kt",
        "example/Mood.kt",
        "example/MoodAdapter.kt",
      )
    }

    @Test
    fun `omits an enum and its adapter that main generates identically`() {
      val moodQuery = Query(
        name = "currentMood",
        cmd = Command.ONE,
        text = "SELECT mood FROM person",
        columns = listOf(column("mood", type = "mood")),
      )

      val files = generate(listOf(moodQuery), listOf(moodQuery.copy(name = "currentMoodForTest")))

      assertThat(files.map { it.name }).containsExactlyInAnyOrder(
        "example/TestQueries.kt",
        "example/PostgresTestQueries.kt",
      )
      assertThat(
        files.byType("PostgresTestQueries").contents,
      ).contains("moodAdapter: ColumnAdapter<Mood, String> = MoodAdapter()")
    }

    @Test
    fun `fails naming the type when main generates a different type at the same path`() {
      val mainRow = Query(
        name = "getRow",
        cmd = Command.ONE,
        text = "SELECT 1 AS id, 'x' AS name",
        columns = listOf(column("id", type = "int4"), column("name")),
      )
      val sourceSetRow = mainRow.copy(columns = listOf(column("id", type = "int4"), column("label")))

      assertFailure { generate(listOf(mainRow), listOf(sourceSetRow)) }.also {
        it.hasClass(IllegalStateException::class)
        it.messageContains("type 'GetRow'")
        it.messageContains("test queries")
      }
    }

    @Test
    fun `leaves the main output unchanged`() {
      val mainQueries = listOf(starQuery("listAuthors", authorTable))
      val before = generateCode(catalog, mainQueries, "example", emptySet(), emptySet())

      generate(mainQueries, listOf(starQuery("listBooks", bookTable)))

      assertThat(generateCode(catalog, mainQueries, "example", emptySet(), emptySet())).isEqualTo(before)
    }
  }
}
