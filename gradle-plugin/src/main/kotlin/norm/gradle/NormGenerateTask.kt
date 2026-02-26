package norm.gradle

import norm.generator.CrudQuerySynthesizer
import norm.generator.JdbcAnalyzer
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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
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
        // Apply schemas
        logger.lifecycle("Norm: Applying schemas...")
        val combinedSchema = schemas.files
          .sortedBy { it.absolutePath }
          .joinToString(separator = "\n\n") { it.readText() }
        connection.createStatement().use { it.execute(combinedSchema) }
        logger.lifecycle("Norm: Schemas applied successfully")

        // Build catalog from database metadata
        val analyzer = JdbcAnalyzer(connection)
        val catalog = analyzer.buildCatalog()

        // Parse and analyze queries
        val parsedQueries = queries.files
          .sortedBy { it.absolutePath }
          .flatMap { file -> QueryFileParser.parse(file.readText()) }

        // Optionally synthesize CRUD queries (user-defined queries take priority over synthetic ones)
        val allParsedQueries = if (database.generateCrud.get()) {
          CrudQuerySynthesizer.synthesizeAndMerge(catalog, parsedQueries)
        } else {
          parsedQueries
        }

        val analyzedQueries = allParsedQueries.map { parsed ->
          analyzer.analyzeQuery(parsed, catalog)
        }

        // Generate code
        val files = generateCode(
          catalog,
          analyzedQueries,
          database.packageName.get(),
          database.frameworks.get(),
          database.frameworkSchemas.get(),
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
