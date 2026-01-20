package norm.generator

import assertk.Assert
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAtLeast
import assertk.assertions.containsMatch
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.support.expected
import assertk.assertions.support.show
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import plugin.Catalog
import plugin.Column
import plugin.Identifier
import plugin.Query
import plugin.Schema
import plugin.Table

/**
 * Tests for framework-specific annotation generation in [TypeRepository].
 *
 * These tests verify that the correct annotations are added to generated data classes
 * based on the [Framework] configuration.
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
          not_null = true,
          type = Identifier(name = "serial"),
          table = Identifier(name = tableName),
        ),
      ) + additionalColumns.toList(),
    )

    /**
     * Creates a catalog with the given tables in the public schema.
     */
    private fun createCatalog(vararg tables: Table): Catalog = Catalog(
      default_schema = "public",
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
     * Converts a TypeSpec to a string representation for assertion.
     */
    private fun typeSpecToString(typeSpec: com.squareup.kotlinpoet.TypeSpec): String {
      val fileSpec = FileSpec.builder(TEST_PACKAGE, "${typeSpec.name}.kt")
        .addType(typeSpec)
        .build()
      return buildString { fileSpec.writeTo(this) }
    }
  }

  @Nested
  inner class MicronautDataJdbc {

    @Test
    fun `MICRONAUT_DATA_JDBC adds @MappedEntity with correct package`() {
      val table = createTable("user_account")
      val catalog = createCatalog(table)
      val repository = TypeRepository(
        packageName = TEST_PACKAGE,
        catalog = catalog,
        frameworks = setOf(Framework.MICRONAUT_DATA_JDBC),
      )

      repository.getTypeProjectionForTable(table)
      val generatedType = repository.requiredTypes.first()
      val generatedCode = typeSpecToString(generatedType)

      // KotlinPoet escapes keywords like "data" and "annotation" with backticks
      assertThat(generatedCode).contains("io.micronaut.`data`.`annotation`.MappedEntity")
      assertThat(generatedCode).contains("""@MappedEntity("user_account")""")
    }

    @Test
    fun `MICRONAUT_DATA_JDBC adds @Id from correct package`() {
      val table = createTable("user_account")
      val catalog = createCatalog(table)
      val repository = TypeRepository(
        packageName = TEST_PACKAGE,
        catalog = catalog,
        frameworks = setOf(Framework.MICRONAUT_DATA_JDBC),
      )

      repository.getTypeProjectionForTable(table)
      val generatedType = repository.requiredTypes.first()
      val generatedCode = typeSpecToString(generatedType)

      // KotlinPoet escapes keywords like "data" and "annotation" with backticks
      assertThat(generatedCode).contains("io.micronaut.`data`.`annotation`.Id")
      // Verify @Id annotation is present on the id property
      assertThat(generatedCode).contains("@field:Id")
      // Use a regex pattern that handles different whitespace
      assertThat(generatedCode).containsMatch(Regex("""@field:Id\s+public val id:"""))
    }
  }

  @Nested
  inner class SpringDataJdbc {

    @Test
    fun `SPRING_DATA_JDBC adds @Table with correct package`() {
      val table = createTable("user_account")
      val catalog = createCatalog(table)
      val repository = TypeRepository(
        packageName = TEST_PACKAGE,
        catalog = catalog,
        frameworks = setOf(Framework.SPRING_DATA_JDBC),
      )

      repository.getTypeProjectionForTable(table)
      val generatedType = repository.requiredTypes.first()
      val generatedCode = typeSpecToString(generatedType)

      // KotlinPoet escapes keywords like "data" with backticks
      assertThat(generatedCode).contains("org.springframework.`data`.relational.core.mapping.Table")
      assertThat(generatedCode).contains("""@Table("user_account")""")
    }

    @Test
    fun `SPRING_DATA_JDBC adds @Id from correct package`() {
      val table = createTable("user_account")
      val catalog = createCatalog(table)
      val repository = TypeRepository(
        packageName = TEST_PACKAGE,
        catalog = catalog,
        frameworks = setOf(Framework.SPRING_DATA_JDBC),
      )

      repository.getTypeProjectionForTable(table)
      val generatedType = repository.requiredTypes.first()
      val generatedCode = typeSpecToString(generatedType)

      // KotlinPoet escapes keywords like "data" and "annotation" with backticks
      assertThat(generatedCode).contains("org.springframework.`data`.`annotation`.Id")
      // Verify @Id annotation is present on the id property
      assertThat(generatedCode).contains("@Id")
      // Use a regex pattern that handles different whitespace
      assertThat(generatedCode).containsMatch(Regex("""@Id\s+public val id:"""))
    }
  }

  @Nested
  inner class BothFrameworks {

    @Test
    fun `Both frameworks together add both annotations`() {
      val table = createTable("user_account")
      val catalog = createCatalog(table)
      val repository = TypeRepository(
        packageName = TEST_PACKAGE,
        catalog = catalog,
        frameworks = setOf(Framework.MICRONAUT_DATA_JDBC, Framework.SPRING_DATA_JDBC),
      )

      repository.getTypeProjectionForTable(table)
      val generatedType = repository.requiredTypes.first()
      val generatedCode = typeSpecToString(generatedType)

      // Both class-level annotations should be present (KotlinPoet escapes keywords with backticks)
      assertThat(generatedCode).contains("io.micronaut")
      assertThat(generatedCode).contains("MappedEntity")
      assertThat(generatedCode).contains("org.springframework")
      assertThat(generatedCode).contains("relational.core.mapping")
      assertThat(generatedCode).contains("""@MappedEntity("user_account")""")
      assertThat(generatedCode).contains("""@Table("user_account")""")

      // Both @Id annotations should be imported (with different qualified references due to conflicts)
      // When there's a naming conflict, KotlinPoet imports one and uses fully-qualified for the other
      // Check that both Id annotations are referenced somewhere in the code
      val idAnnotationMatches = Regex("""@.*Id""").findAll(generatedCode).toList()
      assertThat(idAnnotationMatches.size).isEqualTo(2)
    }
  }

  @Nested
  inner class AllTablesFramework {

    @Test
    fun `ALL_TABLES generates models for ALL tables including unused ones`() {
      // Create a table that IS used in queries
      val usedTable = createTable(
        "author",
        Column(
          name = "name",
          not_null = true,
          type = Identifier(name = "text"),
          table = Identifier(name = "author"),
        ),
      )

      // Create an "orphan" table that is NOT referenced in any query
      val orphanTable = createTable(
        "orphan_config",
        Column(
          name = "setting_key",
          not_null = true,
          type = Identifier(name = "text"),
          table = Identifier(name = "orphan_config"),
        ),
      )

      val catalog = createCatalog(usedTable, orphanTable)

      // Create a query that only references the author table
      val query = createQuery(
        "getAuthor",
        "author",
        listOf(
          Column(
            name = "id",
            not_null = true,
            type = Identifier(name = "serial"),
            table = Identifier(name = "author"),
          ),
          Column(
            name = "name",
            not_null = true,
            type = Identifier(name = "text"),
            table = Identifier(name = "author"),
          ),
        ),
      )

      // Generate code with ALL_TABLES framework - should generate models for all tables
      val files = generateCode(
        catalog = catalog,
        queries = listOf(query),
        packageName = TEST_PACKAGE,
        frameworks = setOf(Framework.ALL_TABLES),
        frameworkSchemas = emptySet(),
      )

      val fileNames = files.map { it.name }

      // Both tables should have models generated
      val packagePath = TEST_PACKAGE.replace('.', '/')
      assertThat(fileNames).containsAtLeast(
        "$packagePath/Author.kt",
        "$packagePath/OrphanConfig.kt",
      )
    }

    @Test
    fun `Empty framework set only generates models for tables used in queries`() {
      // Create a table that IS used in queries
      val usedTable = createTable(
        "author",
        Column(
          name = "name",
          not_null = true,
          type = Identifier(name = "text"),
          table = Identifier(name = "author"),
        ),
      )

      // Create an "orphan" table that is NOT referenced in any query
      val orphanTable = createTable(
        "orphan_config",
        Column(
          name = "setting_key",
          not_null = true,
          type = Identifier(name = "text"),
          table = Identifier(name = "orphan_config"),
        ),
      )

      val catalog = createCatalog(usedTable, orphanTable)

      // With empty frameworks, TypeRepository only generates types when explicitly requested
      // (i.e., through query generation or explicit getTypeProjectionForTable calls)
      val repository = TypeRepository(
        packageName = TEST_PACKAGE,
        catalog = catalog,
        frameworks = emptySet(),
      )

      // Only request the author table - simulating what would happen during query processing
      repository.getTypeProjectionForTable(usedTable)

      // Get all generated types
      val generatedTypeNames = repository.requiredTypes.map { it.name }.toSet()

      // Author should be generated (because we explicitly requested it)
      assertThat(generatedTypeNames).contains("Author")

      // OrphanConfig should NOT be generated (we never requested it, and empty frameworks means no ALL_TABLES)
      assertThat(generatedTypeNames).doesNotContain("OrphanConfig")
    }
  }

  @Nested
  inner class IdColumnBehavior {

    @Test
    fun `Non-id columns do not get @Id annotation`() {
      val table = Table(
        rel = Identifier(name = "user_account"),
        columns = listOf(
          Column(
            name = "id",
            not_null = true,
            type = Identifier(name = "serial"),
            table = Identifier(name = "user_account"),
          ),
          Column(
            name = "username",
            not_null = true,
            type = Identifier(name = "text"),
            table = Identifier(name = "user_account"),
          ),
          Column(
            name = "email",
            not_null = true,
            type = Identifier(name = "text"),
            table = Identifier(name = "user_account"),
          ),
        ),
      )
      val catalog = createCatalog(table)
      val repository = TypeRepository(
        packageName = TEST_PACKAGE,
        catalog = catalog,
        frameworks = setOf(Framework.MICRONAUT_DATA_JDBC),
      )

      repository.getTypeProjectionForTable(table)
      val generatedType = repository.requiredTypes.first()
      val generatedCode = typeSpecToString(generatedType)

      // Count occurrences of @Id - should only appear once (on the id property)
      val idAnnotationCount = "@field:Id\\b".toRegex().findAll(generatedCode).count()
      assertThat(idAnnotationCount).isEqualTo(1)

      // Verify @Id is only on the id property, not on username or email
      assertThat(generatedCode).containsMatch(Regex("""Id\s+public val id:"""))
      assertThat(generatedCode).doesNotContainMatch(Regex("""Id\s+public val username:"""))
      assertThat(generatedCode).doesNotContainMatch(Regex("""Id\s+public val email:"""))
    }
  }
}

/** Asserts the [CharSequence] does not contain a match for the given [Regex]. */
fun Assert<CharSequence>.doesNotContainMatch(expected: Regex) = given { actual ->
  if (!expected.containsMatchIn(actual)) return@given
  expected("to not contain match for:${show(expected)}")
}
