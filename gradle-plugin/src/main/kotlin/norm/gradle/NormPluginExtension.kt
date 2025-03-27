package norm.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.Property

public interface NormPluginExtension {
  /**
   * Databases to configure.
   */
  public val databases: NamedDomainObjectContainer<Database>

  /**
   * NORM runtime version.
   */
  public val version: Property<String>
}
