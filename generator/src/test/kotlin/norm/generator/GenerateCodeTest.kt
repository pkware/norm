package norm.generator

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.pkware.norm.generator.generateCode
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
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

class GenerateCodeTest {

  @ParameterizedTest
  @MethodSource("scenarios")
  fun a(scenarioDirectory: Path) {
    val expectedFiles = mutableMapOf<String, String>()
    var request: GenerateRequest? = null
    fun loadTestFiles(directory: Path) {
      Files.list(directory).use { files ->
        files.forEach { file ->
          if (file.endsWith("setup.json")) {
            request = readGenerateRequestFromFile(directory.resolve("setup.json"))
          } else if (file.isDirectory()) {
            loadTestFiles(file)
          } else {
            expectedFiles[file.relativeTo(scenarioDirectory).pathString] = file.readText()
          }
        }
      }
    }
    loadTestFiles(scenarioDirectory)

    assertWithMessage("Directory $scenarioDirectory does not have a setup.json").that(request).isNotNull()
    val result = generateCode(request?.catalog!!, request!!.queries, "test")
    val createdFiles = result.associate { spec ->
      Pair(spec.name, spec.contents)
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
    fun scenarios(): Stream<Path> = Files.list(Path("src/test/scenarios").toAbsolutePath())

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
