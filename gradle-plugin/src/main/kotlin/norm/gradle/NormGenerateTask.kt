package norm.gradle

import norm.generator.CrudQuerySynthesizer
import norm.generator.JdbcAnalyzer
import norm.generator.ParsedQuery
import norm.generator.QueryFileParser
import norm.generator.generateCode
import okio.buffer
import okio.sink
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.postgresql.util.PSQLException
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.utility.DockerImageName
import plugin.Catalog
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import javax.inject.Inject

/**
 * Generates Kotlin code from SQL schema and query files using direct JDBC analysis.
 *
 * This task:
 * 1. Starts a PostgreSQL container via Testcontainers
 * 2. Applies schema SQL files to create the database structure
 * 3. Uses JDBC metadata APIs to introspect the schema and analyze queries
 * 4. Generates Kotlin code via the Norm generator
 * 5. Stops the container
 *
 * Docker must be installed and running.
 */
@CacheableTask
internal abstract class NormGenerateTask @Inject constructor(@get:Nested val database: Database) : DefaultTask() {

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val schemas: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val queries: ConfigurableFileCollection

  /**
   * PostgreSQL version for the database container.
   */
  @get:Input
  abstract val postgresVersion: Property<String>

  @get:OutputDirectory
  abstract val generatedSources: DirectoryProperty

  init {
    group = NormPlugin.NORM_GROUP
    description = "Generates Kotlin code from SQL using JDBC analysis."
    schemas.from(database.schemas.map { it.map(project.projectDir::resolve) })
    queries.from(database.queries.map { it.map(project.projectDir::resolve) })
    generatedSources.set(project.layout.buildDirectory.dir(NormPlugin.NORM_GENERATED_CODE))
  }

  @TaskAction
  fun generate() {
    val container = createAndStartContainer()
    try {
      val jdbcUrl = container.jdbcUrl
      val username = container.username
      val password = container.password

      DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        applySchemas(connection)

        // Build catalog from database metadata
        val analyzer = JdbcAnalyzer(connection)
        val catalog = analyzer.buildCatalog()

        // Parse and optionally synthesize CRUD queries (user-defined queries take priority over synthetic ones)
        val parsedQueries = parseQueryFiles()
        val allParsedQueries = if (database.generateCrud.get()) {
          val quoteIdentifier = analyzer.buildIdentifierQuoter()
          CrudQuerySynthesizer.synthesizeAndMerge(catalog, parsedQueries, quoteIdentifier)
        } else {
          parsedQueries
        }

        val analyzedQueries = analyzeQueries(analyzer, allParsedQueries, catalog)

        // Generate code
        val files = generateCode(
          catalog,
          analyzedQueries,
          database.packageName.get(),
          database.frameworks.get(),
        )

        // Write generated files
        val directory = generatedSources.get().asFile
        val packagePath = database.packageName.get().replace('.', '/')
        val packageDirectory = directory.resolve(packagePath)

        // Clean package directory to remove stale generated files
        if (packageDirectory.exists()) {
          packageDirectory.deleteRecursively()
        }

        for (fileContent in files) {
          val file = directory.resolve(fileContent.name)
          file.parentFile.mkdirs()
          file.sink().buffer().use { it.write(fileContent.contents) }
        }

        logger.lifecycle("Norm: Generated ${files.size} files")
      }
    } finally {
      logger.lifecycle("Norm: Stopping PostgreSQL container...")
      try {
        container.stop()
      } catch (expected: Exception) {
        logger.warn("Norm: Failed to stop container", expected)
      }
    }
  }

  private fun applySchemas(connection: Connection) {
    logger.lifecycle("Norm: Applying schemas...")
    schemas.files.sortedBy { it.absolutePath }.forEach { file ->
      val content = file.readText()
      try {
        connection.createStatement().use { it.execute(content) }
      } catch (e: SQLException) {
        val locationInfo = (e as? PSQLException)?.serverErrorMessage?.position
          ?.takeIf { it > 0 }
          ?.let { position -> sqlPositionToLineColumn(content, position) }
          ?.let { (line, column) -> " at line $line, column $column" }
          ?: ""
        throw IllegalStateException(
          "Norm: Failed to apply schema '${file.absolutePath}'$locationInfo: ${e.message}",
          e,
        )
      }
    }
    logger.lifecycle("Norm: Schemas applied successfully")
  }

  private fun parseQueryFiles(): List<ParsedQuery> = queries.files
    .sortedBy { it.absolutePath }
    .flatMap { file ->
      try {
        QueryFileParser.parse(file.readText(), file.absolutePath)
      } catch (e: IllegalArgumentException) {
        throw IllegalStateException(
          "Norm: Failed to parse query file '${file.absolutePath}': ${e.message}",
          e,
        )
      }
    }

  private fun analyzeQueries(analyzer: JdbcAnalyzer, parsedQueries: List<ParsedQuery>, catalog: Catalog) =
    parsedQueries.map { parsed ->
      try {
        analyzer.analyzeQuery(parsed, catalog)
      } catch (e: SQLException) {
        throw IllegalStateException(
          "Norm: Failed to analyze query '${parsed.name}'${parsed.sourceLabel()}: ${e.message}",
          e,
        )
      }
    }

  /**
   * Formats the source file and line as a parenthesized label for error messages, e.g.
   * ` (queries.sql:5)` or ` (<synthesized CRUD for table 'author'>)`. Returns an empty
   * string when [ParsedQuery.sourceFile] is empty.
   */
  private fun ParsedQuery.sourceLabel(): String {
    if (sourceFile.isEmpty()) return ""
    val lineSegment = if (sourceLine > 0) ":$sourceLine" else ""
    return " ($sourceFile$lineSegment)"
  }

  /**
   * Converts a 1-based character position in a SQL string to a (line, column) pair.
   *
   * PostgreSQL reports errors using a character position ([org.postgresql.util.ServerErrorMessage.position])
   * rather than a line number. This function converts that position to a human-readable line and column
   * so that errors can be traced back to the correct location in the source file.
   *
   * @param sql The full SQL string that was submitted to PostgreSQL.
   * @param position The 1-based character offset reported by PostgreSQL.
   * @return A pair of (1-based line number, 1-based column number).
   */
  private fun sqlPositionToLineColumn(sql: String, position: Int): Pair<Int, Int> {
    val textBefore = sql.take((position - 1).coerceAtLeast(0))
    val line = textBefore.count { it == '\n' } + 1
    val column = position - textBefore.lastIndexOf('\n') - 1
    return line to column
  }

  private fun createAndStartContainer(): PostgreSQLContainer<*> {
    val version = postgresVersion.get()
    logger.lifecycle("Norm: Starting PostgreSQL $version container...")

    val imageName = DockerImageName.parse("postgres:$version")
      .asCompatibleSubstituteFor("postgres")

    return PostgreSQLContainer(imageName).apply {
      withDatabaseName(database.name)
      withUsername("norm_user")
      withPassword("norm_password")

      waitingFor(
        WaitAllStrategy()
          .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
          .withStrategy(Wait.forListeningPort())
          .withStartupTimeout(java.time.Duration.ofSeconds(60)),
      )

      start()
      logger.lifecycle("Norm: PostgreSQL container ready at $host:$firstMappedPort")
    }
  }
}
