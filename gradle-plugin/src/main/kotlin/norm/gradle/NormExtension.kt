package norm.gradle

import org.gradle.api.NamedDomainObjectContainer

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
}
