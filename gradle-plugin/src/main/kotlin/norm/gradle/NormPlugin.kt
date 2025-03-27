package norm.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

public class NormPlugin : Plugin<Project> {
  override fun apply(target: Project): Unit = target.run {
    val norm = extensions.getByType<NormPluginExtension>()
    // FIXME Put the real version
    norm.version.convention("+")
    dependencies.addProvider("implementation", norm.version.map { "com.pkware.norm:runtime:$it" })
  }
}
