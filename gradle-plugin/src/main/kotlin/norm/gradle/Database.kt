package norm.gradle

import org.gradle.api.Named
import org.gradle.api.provider.Property

public interface Database : Named {
  public val packageName: Property<String>
  public val sqlcConfigurationFile: Property<String>
}
