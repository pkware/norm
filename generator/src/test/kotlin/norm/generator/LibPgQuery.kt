package norm.generator

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.ImportMemory
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream

/** A syntax error reported by libpg_query, with the 1-based character [cursorPosition] it points at. */
internal class LibPgQueryError(val sqlMessage: String, val cursorPosition: Int) : RuntimeException(sqlMessage)

/** One token from `pg_query_scan`, covering `[start, end)` of the scanned text. */
internal data class ScanToken(val start: Int, val end: Int, val token: Int, val keywordKind: Int)

/**
 * Calls libpg_query compiled to WebAssembly (`libpg_query.wasm`, see the migration recommendation) on Chicory.
 *
 * One [Instance] has one linear memory, so every call runs under [lock]. The instance is discarded after a trap.
 */
internal object LibPgQuery {
  private const val SHARED_MEMORY_INITIAL_PAGES = 32
  private const val SHARED_MEMORY_MAXIMUM_PAGES = 65536
  private const val PARSE_RESULT_BYTES = 12
  private const val SCAN_RESULT_BYTES = 16

  private val lock = Any()
  private val module: WasmModule by lazy {
    LibPgQuery::class.java.getResourceAsStream("/libpg_query.wasm")!!.use { Parser.parse(it) }
  }
  private var instance: Instance? = null

  private fun newInstance(): Instance {
    val memory = ByteBufferMemory(MemoryLimits(SHARED_MEMORY_INITIAL_PAGES, SHARED_MEMORY_MAXIMUM_PAGES, true))
    val wasi = WasiPreview1.builder()
      .withOptions(
        WasiOptions.builder()
          .withStdout(ByteArrayOutputStream())
          .withStderr(ByteArrayOutputStream())
          .build(),
      )
      .build()
    val imports = ImportValues.builder()
      .addFunction(*wasi.toHostFunctions())
      .addMemory(ImportMemory("env", "memory", memory))
      .build()
    val created = Instance.builder(module).withImportValues(imports).withStart(false).build()
    created.export("_initialize").apply()
    created.export("pg_query_init").apply()
    return created
  }

  private fun <T> withInstance(block: (Instance) -> T): T = synchronized(lock) {
    val current = instance ?: newInstance().also { instance = it }
    try {
      block(current)
    } catch (error: LibPgQueryError) {
      throw error
    } catch (error: RuntimeException) {
      instance = null
      throw error
    }
  }

  private fun Instance.call(name: String, vararg arguments: Long): Long =
    export(name).apply(*arguments)?.firstOrNull() ?: 0L

  private fun Instance.allocateCString(text: String): Int {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val pointer = call("malloc", bytes.size + 1L).toInt()
    memory().write(pointer, bytes)
    memory().writeByte(pointer + bytes.size, 0)
    return pointer
  }

  private fun Instance.readCString(pointer: Int): String {
    if (pointer == 0) return ""
    var end = pointer
    while (memory().read(end) != 0.toByte()) end++
    return String(memory().readBytes(pointer, end - pointer), Charsets.UTF_8)
  }

  private fun Instance.readError(errorPointer: Int): LibPgQueryError {
    val message = readCString(memory().readInt(errorPointer))
    val cursorPosition = memory().readInt(errorPointer + 16)
    return LibPgQueryError(message, cursorPosition)
  }

  /**
   * Parses [sql] and returns libpg_query's JSON parse tree.
   *
   * @throws LibPgQueryError if PostgreSQL's grammar rejects [sql].
   */
  fun parseJson(sql: String): JsonObject = JsonParser.parseString(parseJsonText(sql)).asJsonObject

  fun parseJsonText(sql: String): String = withInstance { running ->
    val input = running.allocateCString(sql)
    val result = running.call("malloc", PARSE_RESULT_BYTES.toLong()).toInt()
    try {
      running.call("pg_query_parse", result.toLong(), input.toLong())
      try {
        val errorPointer = running.memory().readInt(result + 8)
        if (errorPointer != 0) throw running.readError(errorPointer)
        running.readCString(running.memory().readInt(result))
      } finally {
        running.call("pg_query_free_parse_result", result.toLong())
      }
    } finally {
      running.call("free", result.toLong())
      running.call("free", input.toLong())
    }
  }

  /**
   * Scans [sql] with PostgreSQL's lexer and returns every token with its byte offsets.
   *
   * @throws LibPgQueryError if the lexer rejects [sql] (for example an unterminated quoted identifier).
   */
  fun scan(sql: String): List<ScanToken> = withInstance { running ->
    val input = running.allocateCString(sql)
    val result = running.call("malloc", SCAN_RESULT_BYTES.toLong()).toInt()
    try {
      running.call("pg_query_scan", result.toLong(), input.toLong())
      try {
        val errorPointer = running.memory().readInt(result + 12)
        if (errorPointer != 0) throw running.readError(errorPointer)
        val length = running.memory().readInt(result)
        val data = running.memory().readInt(result + 4)
        decodeScanResult(running.memory().readBytes(data, length))
      } finally {
        running.call("pg_query_free_scan_result", result.toLong())
      }
    } finally {
      running.call("free", result.toLong())
      running.call("free", input.toLong())
    }
  }

  private fun decodeScanResult(bytes: ByteArray): List<ScanToken> {
    val tokens = mutableListOf<ScanToken>()
    val reader = ProtobufReader(bytes)
    while (reader.hasRemaining()) {
      val tag = reader.readVarint().toInt()
      if (tag == (2 shl 3 or 2)) {
        tokens += decodeScanToken(reader.readLengthDelimited())
      } else {
        reader.skip(tag and 7)
      }
    }
    return tokens
  }

  private fun decodeScanToken(bytes: ByteArray): ScanToken {
    val reader = ProtobufReader(bytes)
    var start = 0
    var end = 0
    var token = 0
    var keywordKind = 0
    while (reader.hasRemaining()) {
      val tag = reader.readVarint().toInt()
      when (tag shr 3) {
        1 -> start = reader.readVarint().toInt()
        2 -> end = reader.readVarint().toInt()
        4 -> token = reader.readVarint().toInt()
        5 -> keywordKind = reader.readVarint().toInt()
        else -> reader.skip(tag and 7)
      }
    }
    return ScanToken(start, end, token, keywordKind)
  }

  private class ProtobufReader(private val bytes: ByteArray) {
    private var position = 0

    fun hasRemaining() = position < bytes.size

    fun readVarint(): Long {
      var result = 0L
      var shift = 0
      while (true) {
        val next = bytes[position++].toInt()
        result = result or ((next and 0x7f).toLong() shl shift)
        if (next and 0x80 == 0) return result
        shift += 7
      }
    }

    fun readLengthDelimited(): ByteArray {
      val length = readVarint().toInt()
      return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun skip(wireType: Int) {
      when (wireType) {
        0 -> readVarint()
        1 -> position += 8
        2 -> readLengthDelimited()
        5 -> position += 4
        else -> error("Unsupported protobuf wire type $wireType")
      }
    }
  }
}
