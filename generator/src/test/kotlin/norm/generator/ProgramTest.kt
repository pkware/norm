package norm.generator

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import plugin.GenerateRequest
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

class ProgramTest {

  @ParameterizedTest
  @MethodSource("scenarios")
  fun a(scenarioDirectory: Path) {
    val expectedFiles = mutableMapOf<String, String>()
    var request: GenerateRequest? = null
    fun loadTestFiles(directory: Path) {
      Files.list(directory).use {
        it.forEach {
          if (it.endsWith("setup.json")) {
            request = readGenerateRequestFromFile(directory.resolve("setup.json"))
          } else if (it.isDirectory()) {
            loadTestFiles(it)
          } else {
            expectedFiles[it.relativeTo(scenarioDirectory).pathString] = it.readText()
          }
        }
      }
    }
    loadTestFiles(scenarioDirectory)

    assertWithMessage("Directory $scenarioDirectory does not have a setup.json").that(request).isNotNull()
    val result = Program(request!!).execute()
    val createdFiles = result.files.associate { spec ->
      val writer = StringWriter()
      spec.writeTo(writer)
      Pair(spec.relativePath, writer.toString())
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
  }
}
