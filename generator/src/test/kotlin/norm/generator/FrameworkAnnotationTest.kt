package norm.generator

import assertk.Assert
import assertk.assertThat
import assertk.assertions.containsMatch
import assertk.assertions.doesNotContain
import assertk.assertions.support.expected
import assertk.assertions.support.show
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for framework-specific code generation.
 *
 * Verifies that:
 * - DI annotations (`@Singleton`, `@Component`, `@Requires`) are correctly added to `PostgresQueries`
 * - Entity classes have **no** ORM annotations regardless of framework configuration
 */
class FrameworkAnnotationTest {

  companion object {
    private const val TEST_PACKAGE = "test.example"

    /**
     * Creates a simple table with an id column and additional columns.
     */
    private fun createTable(tableName: String, vararg additionalColumns: Column): Table = Table(
      rel = Identifier(name = tableName),
      columns = listOf(
        Column(
          name = "id",
          notNull = true,
          isPrimaryKey = true,
          type = Identifier(name = "serial"),
          table = Identifier(name = tableName),
        ),
      ) + additionalColumns.toList(),
    )

    /**
     * Creates a catalog with the given tables in the public schema.
     */
    private fun createCatalog(vararg tables: Table): Catalog = Catalog(
      defaultSchema = "public",
      schemas = listOf(
        Schema(
          name = "public",
          tables = tables.toList(),
        ),
      ),
    )

    /**
     * Creates a simple query that selects from a table.
     * Note: This creates a parameterless query for simplicity in tests.
     */
    private fun createQuery(queryName: String, tableName: String, columns: List<Column>): Query = Query(
      name = queryName,
      cmd = ":many",
      text = "SELECT * FROM $tableName",
      columns = columns,
    )

    /**
     * Generates code with the given frameworks and returns the content of `PostgresQueries.kt`.
     */
    private fun generatePostgresQueriesCode(frameworks: Set<Framework>): String {
      val table = createTable("author")
      val catalog = createCatalog(table)
      val query = createQuery(
        "listAuthors",
        "author",
        listOf(
          Column(
            name = "id",
            notNull = true,
            type = Identifier(name = "serial"),
            table = Identifier(name = "author"),
          ),
        ),
      )
      val files = generateCode(catalog, listOf(query), TEST_PACKAGE, frameworks)
      val postgresQueriesFile = files.first { it.name.endsWith("PostgresQueries.kt") }
      return postgresQueriesFile.contents
    }

    /**
     * Generates an entity class with the given frameworks and returns its code as a string.
     */
    private fun generateEntityCode(frameworks: Set<Framework>): String {
      val table = createTable(
        "user_account",
        Column(
          name = "username",
          notNull = true,
          type = Identifier(name = "text"),
          table = Identifier(name = "user_account"),
        ),
      )
      val catalog = createCatalog(table)
      val query = createQuery(
        "listUsers",
        "user_account",
        table.columns,
      )
      val files = generateCode(catalog, listOf(query), TEST_PACKAGE, frameworks)
      return files.map { it.contents }.joinToString("\n")
    }

    /**
     * Generates code with the given frameworks and returns the content of the `Queries` interface file.
     */
    private fun generateQueriesInterfaceCode(frameworks: Set<Framework>): String {
      val table = createTable("author")
      val catalog = createCatalog(table)
      val query = createQuery(
        "listAuthors",
        "author",
        listOf(
          Column(
            name = "id",
            notNull = true,
            type = Identifier(name = "serial"),
            table = Identifier(name = "author"),
          ),
        ),
      )
      val files = generateCode(catalog, listOf(query), TEST_PACKAGE, frameworks)
      val queriesInterfaceFile = files.first { it.name.endsWith("/Queries.kt") }
      return queriesInterfaceFile.contents
    }

    private fun typeSpecToString(typeSpec: com.squareup.kotlinpoet.TypeSpec): String {
      val fileSpec = FileSpec.builder(TEST_PACKAGE, "${typeSpec.name}.kt")
        .addType(typeSpec)
        .build()
      return buildString { fileSpec.writeTo(this) }
    }
  }

  @Nested
  inner class MicronautDependencyInjection {

    @Test
    fun `MICRONAUT_DATA adds @Singleton to PostgresQueries`() {
      val code = generatePostgresQueriesCode(setOf(Framework.MICRONAUT_DATA))
      assertThat(code).containsMatch(Regex("""@Singleton"""))
    }

    @Test
    fun `MICRONAUT_DATA adds @Requires(missingBeans) to PostgresQueries`() {
      val code = generatePostgresQueriesCode(setOf(Framework.MICRONAUT_DATA))
      assertThat(code).containsMatch(Regex("""@Requires\(missingBeans\s*=\s*\[Queries::class]"""))
    }

    @Test
    fun `MICRONAUT_DATA does not make PostgresQueries extend RealTransactable`() {
      val code = generatePostgresQueriesCode(setOf(Framework.MICRONAUT_DATA))
      assertThat(code).doesNotContain("RealTransactable")
    }
  }

  @Nested
  inner class MicronautDiOnly {

    @Test
    fun `MICRONAUT adds @Singleton to PostgresQueries`() {
      val code = generatePostgresQueriesCode(setOf(Framework.MICRONAUT))
      assertThat(code).containsMatch(Regex("""@Singleton"""))
    }

    @Test
    fun `MICRONAUT adds @Requires(missingBeans) to PostgresQueries`() {
      val code = generatePostgresQueriesCode(setOf(Framework.MICRONAUT))
      assertThat(code).containsMatch(Regex("""@Requires\(missingBeans\s*=\s*\[Queries::class]"""))
    }

    @Test
    fun `MICRONAUT makes PostgresQueries extend RealTransactable`() {
      val code = generatePostgresQueriesCode(setOf(Framework.MICRONAUT))
      assertThat(code).containsMatch(Regex("RealTransactable"))
    }

    @Test
    fun `MICRONAUT emits no Micronaut Data references`() {
      val allCode = generateEntityCode(setOf(Framework.MICRONAUT))
      assertThat(allCode).doesNotContain("MicronautConnectionProvider")
      assertThat(allCode).doesNotContain("ConnectionOperations")
      assertThat(allCode).doesNotContain("io.micronaut.data")
    }
  }

  @Nested
  inner class SpringDependencyInjection {

    @Test
    fun `SPRING_DATA adds @Component to PostgresQueries`() {
      val code = generatePostgresQueriesCode(setOf(Framework.SPRING_DATA))
      assertThat(code).containsMatch(Regex("""@Component"""))
    }
  }

  @Nested
  inner class NoFramework {

    @Test
    fun `empty frameworks adds no DI annotations`() {
      val code = generatePostgresQueriesCode(emptySet())
      assertThat(code).doesNotContain("@Singleton")
      assertThat(code).doesNotContain("@Component")
      assertThat(code).doesNotContain("@Requires")
    }
  }

  @Nested
  inner class NoEntityAnnotations {

    @Test
    fun `MICRONAUT_DATA does not add entity annotations to generated classes`() {
      val allCode = generateEntityCode(setOf(Framework.MICRONAUT_DATA))
      assertThat(allCode).doesNotContain("MappedEntity")
      assertThat(allCode).doesNotContain("MappedProperty")
      assertThat(allCode).doesNotContain("@field:Id")
    }

    @Test
    fun `SPRING_DATA does not add entity annotations to generated classes`() {
      val allCode = generateEntityCode(setOf(Framework.SPRING_DATA))
      assertThat(allCode).doesNotContain("@Table")
      assertThat(allCode).doesNotContain("@Column")
      assertThat(allCode).doesNotContain("org.springframework.data.annotation.Id")
    }

    @Test
    fun `table projection properties use database column names`() {
      val table = createTable(
        "user_account",
        Column(
          name = "first_name",
          notNull = true,
          type = Identifier(name = "text"),
          table = Identifier(name = "user_account"),
        ),
      )
      val catalog = createCatalog(table)
      val repository = TypeRepository(TEST_PACKAGE, catalog)

      repository.getTypeProjectionForTable(table)
      val generatedType = repository.requiredTypes.first()
      val generatedCode = typeSpecToString(generatedType)

      // Properties should use original snake_case column names, not camelCase
      assertThat(generatedCode).containsMatch(Regex("""val first_name:"""))
      assertThat(generatedCode).doesNotContainMatch(Regex("""val firstName:"""))
    }
  }

  @Nested
  inner class TransactableSupertype {

    @Test
    fun `no framework makes Queries interface extend Transactable`() {
      val code = generateQueriesInterfaceCode(emptySet())
      assertThat(code).containsMatch(Regex("interface Queries : Transactable"))
    }

    @Test
    fun `MICRONAUT_DATA does not make Queries interface extend Transactable`() {
      val code = generateQueriesInterfaceCode(setOf(Framework.MICRONAUT_DATA))
      assertThat(code).doesNotContain("Transactable")
    }

    @Test
    fun `SPRING_DATA does not make Queries interface extend Transactable`() {
      val code = generateQueriesInterfaceCode(setOf(Framework.SPRING_DATA))
      assertThat(code).doesNotContain("Transactable")
    }

    @Test
    fun `MICRONAUT makes Queries interface extend Transactable`() {
      val code = generateQueriesInterfaceCode(setOf(Framework.MICRONAUT))
      assertThat(code).containsMatch(Regex("interface Queries : Transactable"))
    }
  }
}

/** Asserts the [CharSequence] does not contain a match for the given [Regex]. */
fun Assert<CharSequence>.doesNotContainMatch(expected: Regex) = given { actual ->
  if (!expected.containsMatchIn(actual)) return@given
  expected("to not contain match for:${show(expected)}")
}
