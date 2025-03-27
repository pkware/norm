package norm.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

// TODO Make cacheable
// @CacheableTask
// TODO Should we have a SourceTask?
public abstract class GenerateSchemasTask : Exec() {

  @get:Input
  public abstract val packageName: Property<String>

  @get:Input
  public abstract val schemas: ListProperty<String>

  @get:Input
  public abstract val queries: ListProperty<String>

  @get:OutputDirectory
  public abstract val generatedSources: DirectoryProperty

  @get:OutputFile
  public abstract val configuration: RegularFileProperty

  @TaskAction
  public fun run() {
    // Start a database?
    // Apply migrations
    // Query the schema/resolve queries
    // Generate code

    configuration.set(project.layout.buildDirectory.file("tmp/norm"))
    val configuration = """
      version: '2'
      plugins:
      - name: norm
        process:
          cmd: sqlc-gen-json
      sql:
      - schema: [${schemas.get().joinToString()}]
        queries: [${queries.get().joinToString()}]
        engine: postgresql
        codegen:
        - out: $generatedSources
          plugin: norm
          options:
            indent: "  "
            filename: codegen.json
    """.trimIndent()
    this.configuration.get().asFile.writeText(configuration)
  }
}
