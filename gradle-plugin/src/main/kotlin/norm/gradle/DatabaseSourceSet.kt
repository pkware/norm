package norm.gradle

import org.gradle.api.Named
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input

/**
 * Queries whose generated code is compiled only by the Kotlin source set of the same [name], for example `test`.
 *
 * Declare one under [Database.sourceSets].
 */
public abstract class DatabaseSourceSet(private val name: String) : Named {

  /**
   * Paths to files or directories containing the queries for which to generate code.
   *
   * Paths follow the same rules as [Database.queries].
   */
  @get:Input
  public abstract val queries: ListProperty<String>

  /**
   * Returns the name of the Kotlin source set that compiles the generated code.
   */
  @Input
  override fun getName(): String = name
}
