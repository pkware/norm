package norm.gradle

import norm.generator.Framework
import org.gradle.api.Named
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
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
   * PostgreSQL version to use for the database container.
   *
   * Defaults to `"18"` (latest stable at time of implementation).
   *
   * Example values: `"16"`, `"15"`, `"14"`, or specific tags like `"16.1-alpine"`.
   * The value is used as the Docker image tag for `postgres:<version>`.
   */
  @get:Input
  public abstract val postgresVersion: Property<String>

  /**
   * Frameworks for which to generate entity annotations.
   *
   * When specified, Norm generates framework-specific annotations on entity classes:
   * - [Framework.MICRONAUT_DATA_JDBC]: `@MappedEntity` on classes, `@Id` on primary keys
   * - [Framework.SPRING_DATA_JDBC]: `@Table` on classes, `@Id` on primary keys
   * - [Framework.ALL_TABLES]: Generates entities for all tables (no annotations)
   *
   * When empty (the default), entities are only generated for tables referenced in queries.
   */
  @get:Input
  public abstract val frameworks: SetProperty<Framework>

  /**
   * Whether to generate CRUD methods (insert, find, exists, count, delete) for each table.
   *
   * When `true`, Norm synthesizes repository-style methods for every non-view table in the schema.
   * If a user-written query has the same name as a synthesized one, the user query takes priority.
   *
   * Defaults to `true`.
   */
  @get:Input
  public abstract val generateCrud: Property<Boolean>

  /**
   * Schemas for which to generate framework entities.
   *
   * When empty (the default), entities are generated for all schemas.
   * When specified, only tables from the listed schemas get framework entity generation.
   */
  @get:Input
  public abstract val frameworkSchemas: SetProperty<String>

  /**
   * Returns the name of this database configuration.
   */
  @Input
  override fun getName(): String = name
}
