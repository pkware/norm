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
   * Paths to files or directories containing the SQL schema.
   *
   * Entries are applied in the order declared. When an entry points to a directory, all
   * `*.sql` files directly inside it are included (non-recursive) and ordered as follows: files
   * are first sorted lexicographically by filename; files matching the Flyway versioned migration
   * convention (`V<version>__<description>.sql`) are then reordered among themselves — each
   * staying in one of the positions a versioned file already occupied — by ascending numeric
   * version. Flyway repeatable migrations (`R__<description>.sql`) are always applied last,
   * sorted lexically among themselves. Flyway undo migrations (`U<version>__<description>.sql`)
   * are skipped entirely, matching Flyway's own behavior during `migrate`.
   *
   * A file that is not itself a versioned migration keeps its lexicographic position — it is
   * *not* moved earlier. That means it only runs before the versioned migrations in the directory
   * if its name sorts before `V` (e.g. `Base.sql`, `A.sql`, `00_setup.sql`). A lowercase name such
   * as `base.sql` sorts *after* every `V...` file and is therefore applied after all of them, which
   * can break a migration that depends on it. To guarantee that shared setup runs before a directory of
   * migrations regardless of its filename, declare it as its own, earlier entry in [schemas]
   * instead of placing it inside the migrations directory — entries are always applied in the
   * order declared here.
   *
   * Relative paths are resolved against the project directory.
   */
  @get:Input
  public abstract val schemas: ListProperty<String>

  /**
   * Paths to files or directories containing the queries for which to generate code.
   *
   * When a path points to a directory, all `*.sql` files directly inside it are included
   * (non-recursive). Files are parsed in lexicographic order by absolute path.
   *
   * Relative paths are resolved against the project directory.
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
   * Defaults to `"18-alpine"`.
   *
   * Example values: `"17", "16.2"`.
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
