package norm.gradle

import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

/**
 * Database for which to generate source code.
 */
public abstract class Database(private val name: String) : Named {

  /**
   * Paths to files containing the SQL schema.
   *
   * Relative paths will be resolved against the project directory.
   */
  @get:Input
  public abstract val schemas: ListProperty<String>

  /**
   * Paths to files containing the queries for which to generate code.
   *
   * Relative paths will be resolved against the project directory.
   */
  @get:Input
  public abstract val queries: ListProperty<String>

  /**
   * Name of the package in which code should be generated.
   */
  @get:Input
  public abstract val packageName: Property<String>

  /**
   * Whether to use a database for enhanced query analysis.
   *
   * When `true` (the default), Norm starts a PostgreSQL container (via Testcontainers),
   * applies the schema files, and provides a database connection to sqlc for enhanced
   * type resolution. This enables sqlc to properly handle Postgres domains, enums, and
   * extensions that require catalog introspection.
   *
   * When `false`, Norm performs schema-only analysis without a database connection,
   * which is faster but less comprehensive.
   *
   * **Requirements**: Docker must be installed and running when enabled.
   */
  @get:Input
  public abstract val useDatabase: Property<Boolean>

  /**
   * PostgreSQL version to use for the database container.
   *
   * Defaults to `"18"` (latest stable at time of implementation).
   * Only used when [useDatabase] is `true`.
   *
   * Example values: `"16"`, `"15"`, `"14"`, or specific tags like `"16.1-alpine"`.
   * The value is used as the Docker image tag for `postgres:<version>`.
   */
  @get:Input
  public abstract val postgresVersion: Property<String>

  @Input
  override fun getName(): String = name

  internal fun schemaJsonFile(project: Project) = project.layout.buildDirectory.file("tmp/norm/$name/schema.json")
}
