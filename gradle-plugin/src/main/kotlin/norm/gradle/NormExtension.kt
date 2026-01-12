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
   * Version of [sqlc](https://sqlc.dev) to use.
   */
  public val sqlcVersion: Property<String>

  /**
   * Databases for which to generate source code.
   */
  public val databases: NamedDomainObjectContainer<Database>
}
