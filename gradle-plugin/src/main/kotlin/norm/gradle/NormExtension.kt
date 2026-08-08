package norm.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.Property

/**
 * Marker annotation required by Gradle.
 */
@DslMarker
public annotation class NormDsl

/**
 * Configuration options for Norm.
 */
@NormDsl
public interface NormExtension {

  /**
   * Databases for which to generate source code.
   */
  public val databases: NamedDomainObjectContainer<Database>

  /**
   * Whether Norm's generation tasks should run automatically whenever IntelliJ IDEA syncs this project.
   *
   * When `true` (the default), Norm adds each `normGenerate<Name>` task as a dependency of its own
   * `prepareKotlinIdeaImportNorm` task, which IntelliJ runs on every Gradle sync, including the very first
   * project import. This ensures generated sources exist before the IDE reads them.
   *
   * When `false`, that dependency is skipped: `prepareKotlinIdeaImportNorm` is still registered (it is
   * Norm's own task, so registering it unconditionally is harmless), but it depends on nothing, so running
   * it does not run generation. Generation then only happens when a `normGenerate<Name>` task is run
   * explicitly, for example as part of `build`. Set this to `false` if automatic generation on every sync
   * is undesirable — for example, generation starts a PostgreSQL container via Testcontainers, so a sync
   * on a machine where Docker is not running would otherwise fail the sync.
   */
  public val generateOnIdeSync: Property<Boolean>
}
