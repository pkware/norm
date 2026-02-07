package norm.gradle

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.utility.DockerImageName
import java.io.File
import javax.inject.Inject

/**
 * Runs sqlc to resolve the Postgres schema.
 *
 * When [useDatabase] is enabled, starts a PostgreSQL container for this task execution,
 * applies schemas, runs sqlc with database connection, then stops the container.
 *
 * See [GenerateYamlTask] for the Gradle task that generates the YAML.
 * See [GenerateSchemasTask] for the Gradle task that generates Kotlin code.
 */
@CacheableTask
internal abstract class RunSqlcTask @Inject constructor(
  database: Database,
  private val execOperations: ExecOperations,
) : DefaultTask() {

  /**
   * YAML configuration file used by `sqlc`.
   */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sqlcConfiguration: RegularFileProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val schemas: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val queries: ConfigurableFileCollection

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sqlc: RegularFileProperty

  /**
   * JSON file that will contain the schema.
   */
  @get:OutputFile
  abstract val schemaJsonFile: RegularFileProperty

  /**
   * Whether to use database for enhanced analysis.
   */
  @get:Input
  abstract val useDatabase: Property<Boolean>

  /**
   * PostgreSQL version for database container.
   * Only used when [useDatabase] is `true`.
   */
  @get:Input
  @get:Optional
  abstract val postgresVersion: Property<String>

  /**
   * Database name for container.
   * Only used when [useDatabase] is `true`.
   */
  @get:Input
  @get:Optional
  abstract val databaseName: Property<String>

  init {
    group = NormPlugin.NORM_GROUP
    description = "Runs sqlc to resolve the Postgres schema."
    schemaJsonFile.set(database.schemaJsonFile(project))
    schemas.from(database.schemas.map { it.map(project.projectDir::resolve) })
    queries.from(database.queries.map { it.map(project.projectDir::resolve) })
    val sqlc = when {
      Os.isFamily(Os.FAMILY_WINDOWS) -> """C:\Program Files\sqlc\sqlc.exe"""
      Os.isFamily(Os.FAMILY_MAC) -> "/opt/homebrew/bin/sqlc"
      Os.isFamily(Os.FAMILY_UNIX) -> "/snap/bin/sqlc"
      else -> error("Operating system isn't supported")
    }
    this.sqlc.set(File(sqlc))
  }

  @TaskAction
  fun invokeSqlc() {
    if (useDatabase.get()) {
      // Create and start container for this task execution
      val container = createAndStartContainer()
      try {
        // Apply schemas to fresh database
        applySchemas(container)

        // Get URI for sqlc
        val uri = with(container) {
          "postgresql://$username:$password@$host:$firstMappedPort/$databaseName"
        }

        // Update YAML with database URI
        updateYamlWithDatabaseUri(uri)

        // Run sqlc
        runSqlc()
      } finally {
        // Always stop container after task completes
        logger.lifecycle("Norm: Stopping PostgreSQL container...")
        try {
          container.stop()
        } catch (expected: Exception) {
          logger.warn("Norm: Failed to stop container", expected)
        }
      }
    } else {
      // Run sqlc without database
      runSqlc()
    }
  }

  private fun createAndStartContainer(): PostgreSQLContainer<*> {
    val version = postgresVersion.get()
    val dbName = databaseName.get()

    logger.lifecycle("Norm: Starting PostgreSQL $version container...")

    val imageName = DockerImageName.parse("postgres:$version")
      .asCompatibleSubstituteFor("postgres")

    return PostgreSQLContainer(imageName).apply {
      withDatabaseName(dbName)
      withUsername("norm_user")
      withPassword("norm_password")

      // Combined wait strategy (fixed from P0 bug)
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

  private fun applySchemas(container: PostgreSQLContainer<*>) {
    logger.lifecycle("Norm: Applying schemas to database...")

    val combinedSchema = schemas.files
      .sortedBy { it.absolutePath }
      .joinToString(separator = "\n\n") { it.readText() }

    val result = container.execInContainer(
      "psql",
      "-U",
      container.username,
      "-d",
      container.databaseName,
      "-c",
      combinedSchema,
    )

    if (result.exitCode != 0) {
      error("Failed to apply schemas:\n${result.stderr}")
    }

    logger.lifecycle("Norm: Schemas applied successfully")
  }

  private fun updateYamlWithDatabaseUri(uri: String) {
    // Update the YAML file that was generated by GenerateYamlTask
    // to include the database URI (since we now know it)
    val yamlFile = sqlcConfiguration.get().asFile
    val content = yamlFile.readText()

    val updatedContent = if (content.contains("    database:")) {
      // Database section already exists from a previous run - update the URI
      // This handles the case where Gradle cached GenerateYamlTask but re-ran this task
      content.replace(
        Regex("(    database:\\s*\\n\\s*uri:)[^\\n]*"),
        "$1 $uri",
      )
    } else {
      // No database section yet - add it after "engine: postgresql"
      content.replace(
        "engine: postgresql",
        "engine: postgresql\n    database:\n      uri: $uri",
      )
    }

    yamlFile.writeText(updatedContent)
  }

  private fun runSqlc() {
    execOperations.exec {
      commandLine(
        sqlc.get().asFile.absolutePath,
        "generate",
        "--file",
        sqlcConfiguration.get().asFile.absolutePath,
      )
      standardOutput = System.out
      // Pass through environment variables so sqlc can access $HOME and other necessary vars
      environment = System.getenv()
    }.assertNormalExitValue()
  }
}
