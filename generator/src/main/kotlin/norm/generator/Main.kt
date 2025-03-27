package norm.generator

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.wire.WireJsonAdapterFactory
import okio.buffer
import okio.source
import plugin.GenerateRequest
import java.nio.file.Path
import kotlin.io.path.Path

fun main(args: Array<String>) {
  val generateRequest = if (args.isEmpty()) {
    readGenerateRequestFromSystemInput()
  } else {
    readGenerateRequestFromFile(Path(args[0]))
  }

  val result = Program(generateRequest).execute()
  for (file in result.files) {
    file.writeTo(Path("output", file.relativePath))
  }
}

fun readGenerateRequestFromSystemInput(): GenerateRequest = GenerateRequest.ADAPTER.decode(System.`in`)

@OptIn(ExperimentalStdlibApi::class)
fun readGenerateRequestFromFile(path: Path): GenerateRequest {
  val moshi = Moshi.Builder()
    .addLast(WireJsonAdapterFactory())
    .build()
  val adapter = moshi.adapter<GenerateRequest>()
  return path.source().buffer().use(adapter::fromJson)!!
}
