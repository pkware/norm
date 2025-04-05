package norm.gradle

import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

/**
 * Database for which to generate source code.
 */
public abstract class Database(private val name: String) : Named {

  @Input
  override fun getName(): String = name

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

  internal fun schemaJsonFile(project: Project) = project.layout.buildDirectory.file("tmp/norm/$name/schema.json")
}
