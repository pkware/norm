package norm.gradle

import norm.generator.Catalog
import norm.generator.GeneratedFile
import norm.generator.JdbcAnalyzer
import norm.generator.Query
import norm.generator.generateSourceSetCode
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import javax.inject.Inject

/**
 * Generates the code for the queries of one [DatabaseSourceSet].
 *
 * The task still analyzes the database's main queries, so the generated code reuses the types main generates.
 */
@CacheableTask
internal abstract class NormGenerateSourceSetTask @Inject constructor(
  database: Database,
  @get:Nested val sourceSet: DatabaseSourceSet,
) : NormGenerateTask(database) {

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.ABSOLUTE)
  abstract val sourceSetQueries: ConfigurableFileCollection

  init {
    description = "Generates Kotlin code for the '${sourceSet.name}' source set from SQL using JDBC analysis."
    sourceSetQueries.from(expandDeclaredPaths(sourceSet.queries))
    generatedSources.set(
      project.layout.buildDirectory.dir("${NormPlugin.NORM_GENERATED_CODE}-${sourceSet.name}/${database.name}"),
    )
  }

  override fun render(analyzer: JdbcAnalyzer, catalog: Catalog, analyzedQueries: List<Query>): List<GeneratedFile> =
    generateSourceSetCode(
      catalog,
      analyzedQueries,
      analyzeQueries(analyzer, parseQueryFiles(sourceSetQueries), catalog),
      sourceSet.name,
      database.packageName.get(),
      database.frameworks.get(),
      analyzer.fetchReservedWords(),
      database.typeMappings.get(),
    )
}
