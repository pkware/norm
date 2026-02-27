package norm.gradle

import norm.generator.Framework
import norm.generator.TypeMapping
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
   * Frameworks for which to generate DI annotations and connection providers.
   *
   * When specified, Norm generates:
   * - **DI registration:** `PostgresQueries` is annotated with `@Singleton` (Micronaut) or `@Component` (Spring),
   *   so it auto-registers in the DI container.
   * - **ConnectionProvider:** A framework-specific `ConnectionProvider` implementation is generated
   *   (`MicronautConnectionProvider` or `SpringConnectionProvider`) that bridges the framework's connection
   *   management to Norm, enabling `@Transactional` support.
   * - **Override escape hatch (Micronaut):** Generated beans use `@Requires(missingBeans = [...])`,
   *   so providing your own `Queries` or `ConnectionProvider` bean disables the generated one.
   *
   * When empty (the default), no DI annotations or connection providers are generated.
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
   * User-configured type mappings that override Norm's default type resolution.
   *
   * Type-level overrides apply to all columns of that Postgres type and suppress auto-generation
   * of the matching enum or domain adapter. Column-level overrides apply to a single column and
   * do NOT suppress auto-generation — other columns of the same type may still need it.
   *
   * Use the [typeMappings] builder for a concise DSL:
   * ```kotlin
   * typeMappings {
   *   type("jsonb") mapTo "com.example.JsonData" using "com.example.JsonDataAdapter"
   *   column("users", "metadata") mapTo "com.example.Metadata" using "com.example.MetadataAdapter"
   * }
   * ```
   */
  @get:Input
  public abstract val typeMappings: ListProperty<TypeMapping>

  /**
   * Configures user-defined type mappings via DSL.
   *
   * @see typeMappings
   */
  public fun typeMappings(action: TypeMappingDsl.() -> Unit) {
    val dsl = TypeMappingDsl()
    action(dsl)
    typeMappings.addAll(dsl.build())
  }

  /**
   * Returns the name of this database configuration.
   */
  @Input
  override fun getName(): String = name
}
