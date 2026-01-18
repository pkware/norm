package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.wire.WireJsonAdapterFactory
import okio.buffer
import okio.source
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import plugin.GenerateRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

class GenerateCodeTest {

  /**
   * Generate `schema.json` files for scenarios by running `./gradlew :gradle-plugin:generateGoldenFiles`.
   */
  @ParameterizedTest
  @MethodSource("scenarios")
  fun `generated code is correct`(scenarioDirectory: Path) {
    val expectedFiles = mutableMapOf<String, String>()
    val request = readGenerateRequestFromFile(scenarioDirectory.resolve("schema.json"))
    Files.walk(scenarioDirectory).use { files ->
      files.forEach { file ->
        if (file.toString().endsWith(".kt")) {
          expectedFiles[file.relativeTo(scenarioDirectory).pathString] = file.readText()
        }
      }
    }

    assertThat(request, "Directory $scenarioDirectory does not have a schema.json").isNotNull()
    val result = generateCode(request.catalog!!, request.queries, "example")
    val createdFiles = result.associate { spec ->
      Pair(spec.name, spec.contents.utf8())
    }.toMutableMap()

    for ((fileName, content) in expectedFiles.entries) {
      assertThat(fileName in createdFiles, "Expected file $fileName to be generated").isTrue()
      val createdFileContent = createdFiles.remove(fileName)
      assertThat(
        createdFileContent,
        "Content for ${scenarioDirectory.resolve(fileName).toAbsolutePath()}",
      ).isEqualTo(content)
    }

    assertThat(createdFiles, "More files were created than expected").isEmpty()
  }

  companion object {
    @JvmStatic
    fun scenarios() = listOf(
      Path("../test-scenarios-basic"),
      Path("../test-scenarios-complex"),
    )
      .flatMap { it.toAbsolutePath().listDirectoryEntries() }
      .filter(Files::isDirectory)

    @OptIn(ExperimentalStdlibApi::class)
    fun readGenerateRequestFromFile(path: Path): GenerateRequest {
      val moshi = Moshi.Builder()
        .addLast(WireJsonAdapterFactory())
        .build()
      val adapter = moshi.adapter<GenerateRequest>()
      return path.source().buffer().use(adapter::fromJson)!!
    }
  }
}
