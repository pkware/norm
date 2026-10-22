package norm.generator.libpgquery

import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import java.io.OutputStream

private val module: WasmModule by lazy {
  openResource("/libpg_query.wasm").use { Parser.parse(it) }
}

/** Instantiates `libpg_query.wasm` and runs its initializers. WASI output goes nowhere. */
internal fun newInstance(): Instance {
  val wasi = WasiPreview1.builder()
    .withOptions(
      WasiOptions.builder()
        .withStdout(OutputStream.nullOutputStream())
        .withStderr(OutputStream.nullOutputStream())
        .build(),
    )
    .build()
  val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
  val created = Instance.builder(module).withImportValues(imports).withStart(false).build()
  created.call("_initialize")
  created.call("pg_query_init")
  return created
}

/** Calls the exported function [name] and discards any result. */
internal fun Instance.call(name: String, vararg arguments: Long) {
  export(name).apply(*arguments)
}

private fun Instance.callReturning(name: String, vararg arguments: Long): Long =
  checkNotNull(export(name).apply(*arguments)?.firstOrNull()) { "$name returned no value" }

/**
 * Allocates [bytes] bytes in wasm memory with `malloc` and returns the address.
 *
 * @throws IllegalStateException if `malloc` fails.
 */
internal fun Instance.allocate(bytes: Long): Int {
  val pointer = callReturning("malloc", bytes).toInt()
  check(pointer != 0) { "malloc failed to allocate $bytes bytes" }
  return pointer
}
