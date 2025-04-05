package norm.generator

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
import java.util.stream.Stream
import kotlin.io.path.*

class GenerateCodeTest {

  /**
   * Generate `schema.json` files for scenarios by running the Gradle plugin integration tests.
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

    assertWithMessage("Directory $scenarioDirectory does not have a schema.json").that(request).isNotNull()
    val result = generateCode(request.catalog!!, request.queries, "example")
    val createdFiles = result.associate { spec ->
      Pair(spec.name, spec.contents.utf8())
    }.toMutableMap()

    for ((fileName, content) in expectedFiles.entries) {
      assertThat(createdFiles).containsKey(fileName)
      val createdFileContent = createdFiles.remove(fileName)
      assertWithMessage(
        "Content for ${scenarioDirectory.resolve(fileName).toAbsolutePath()}",
      ).that(createdFileContent).isEqualTo(content)
    }

    assertWithMessage("More files were created than expected").that(createdFiles).isEmpty()
  }

  companion object {
    @JvmStatic
    fun scenarios(): Stream<Path> = Files.list(Path("../test-scenarios").toAbsolutePath())

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
