package norm.gradle

import com.pkware.norm.generator.generateCode
import com.pkware.norm.gradle.NormPlugin
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.wire.WireJsonAdapterFactory
import okio.buffer
import okio.sink
import okio.source
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import plugin.GenerateRequest
import javax.inject.Inject

/**
 * Generates Kotlin code from SQL sources.
 */
@CacheableTask
internal abstract class GenerateSchemasTask @Inject constructor(
  @get:Nested val database: Database,
) : DefaultTask() {

  /**
   * JSON file that contains the schema.
   */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val schemaJsonFile: RegularFileProperty

  @get:OutputDirectory
  val generatedSources = database.generatedPackageDirectory(project)

  init {
    group = NormPlugin.NORM_GROUP
    description = "Generates Kotlin code from SQL."
  }

  @TaskAction
  @OptIn(ExperimentalStdlibApi::class)
  fun run() {
    val moshi = Moshi.Builder()
      .add(WireJsonAdapterFactory())
      .build()
    val requestJsonAdapter = moshi.adapter<GenerateRequest>()

    val request = schemaJsonFile.get().asFile.source().buffer().use(requestJsonAdapter::fromJson)!!
    val catalog = request.catalog!!

    val files = generateCode(catalog, request.queries, database.packageName.get())
    val directory = generatedSources.get().asFile
    for (fileContent in files) {
      val file = directory.resolve(fileContent.name)
      file.parentFile.mkdirs()
      file.sink().buffer().use { it.write(fileContent.contents) }
    }
  }
}
