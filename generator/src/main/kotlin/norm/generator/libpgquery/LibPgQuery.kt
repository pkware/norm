package norm.generator.libpgquery

import norm.generator.JsonValue
import java.io.InputStream

/**
 * Opens the classpath resource [name], which starts with `/`.
 *
 * @throws IllegalStateException if the resource is missing.
 */
internal fun openResource(name: String): InputStream =
  checkNotNull(LibPgQuery::class.java.getResourceAsStream(name)) { "${name.removePrefix("/")} is missing" }

/**
 * PostgreSQL's parser and lexer, compiled from libpg_query to WebAssembly and run on Chicory.
 *
 * Offsets in scan and split results are `String` indexes. A single wasm instance serves every call under a lock, and
 * is replaced after a call throws. A text that PostgreSQL rejects is a [LibPgQueryResult.Rejected] and leaves the
 * instance in use.
 */
internal object LibPgQuery {
  private val instance = RecoverableInstance(::newInstance)

  /**
   * JVM stack size of the thread that runs a wasm call. The interpreter alone needs 960 kB for the deepest input that
   * PostgreSQL's depth guard accepts, and this is four times the 1 MB step that passed.
   */
  private const val WASM_STACK_BYTES = 4L * 1024 * 1024

  /**
   * Parses [sql] with PostgreSQL's grammar and returns libpg_query's JSON parse tree. The tree keeps UTF-8 byte
   * offsets, and [ParseResult] maps them to `String` indexes of [sql].
   *
   * The result is [LibPgQueryResult.Rejected] when the grammar rejects [sql].
   *
   * @throws IllegalArgumentException if [sql] contains a NUL character.
   */
  fun parse(sql: String): LibPgQueryResult<ParseResult> {
    val input = Utf8Input(sql)
    return invoke(LibPgQueryEntry.Parse, input).map { text ->
      val tree = JsonValue.parse(text)
      check(tree is JsonValue.JsonObject) { "libpg_query returned a parse tree that is not a JSON object" }
      ParseResult(tree, input)
    }
  }

  /**
   * Splits [sql] into PostgreSQL lexer tokens. Whitespace is not a token and comments are.
   *
   * The result is [LibPgQueryResult.Rejected] when the lexer rejects [sql], for example on an unterminated quoted
   * identifier.
   *
   * @throws IllegalArgumentException if [sql] contains a NUL character.
   */
  fun scan(sql: String): LibPgQueryResult<List<ScanToken>> {
    val input = Utf8Input(sql)
    return invoke(LibPgQueryEntry.Scan, input).map { decodeScanResult(it, input) }
  }

  /**
   * Splits [sql] into statements at top-level `;`, using only the lexer, so text that does not parse is accepted.
   * Text holding no keyword, such as `;` or `1;`, yields no statement.
   *
   * The result is [LibPgQueryResult.Rejected] when the lexer rejects [sql], for example on an unterminated quoted
   * identifier.
   *
   * @throws IllegalArgumentException if [sql] contains a NUL character.
   */
  fun split(sql: String): LibPgQueryResult<List<SplitStatement>> = split(LibPgQueryEntry.Split, sql)

  /**
   * Splits [sql] into statements with PostgreSQL's grammar. Unlike [split], it keeps a `BEGIN ATOMIC` function body
   * with its `;` as one statement.
   *
   * The result is [LibPgQueryResult.Rejected] when the grammar rejects [sql].
   *
   * @throws IllegalArgumentException if [sql] contains a NUL character.
   */
  fun splitWithParser(sql: String): LibPgQueryResult<List<SplitStatement>> = split(LibPgQueryEntry.SplitWithParser, sql)

  private fun split(entry: LibPgQueryEntry.SplitResult, sql: String): LibPgQueryResult<List<SplitStatement>> {
    val input = Utf8Input(sql)
    return invoke(entry, input).map { pairs ->
      (pairs.indices step 2).map { index ->
        SplitStatement(
          input.indexOfByteOffset(pairs[index]),
          input.indexOfByteOffset(pairs[index] + pairs[index + 1]),
        )
      }
    }
  }

  private inline fun <T : Any, R : Any> LibPgQueryResult<T>.map(transform: (T) -> R): LibPgQueryResult<R> =
    when (this) {
      is LibPgQueryResult.Accepted -> LibPgQueryResult.Accepted(transform(value))
      is LibPgQueryResult.Rejected -> this
    }

  /**
   * Calls [entry] on [input] and returns what the entry reads from its result struct, or the error it reports.
   *
   * The call runs on a thread with [WASM_STACK_BYTES] of stack. PostgreSQL's depth guard counts wasm stack, and the
   * JVM stack each wasm frame uses varies with the JIT tier, so the caller's stack size cannot be relied on.
   *
   * Wasm memory is released only when the instance stays in use, which is on every return.
   */
  private fun <T : Any> invoke(entry: LibPgQueryEntry<T>, input: Utf8Input): LibPgQueryResult<T> =
    onWasmStack { invokeOnWasmStack(entry, input) }

  private fun <T : Any> invokeOnWasmStack(entry: LibPgQueryEntry<T>, input: Utf8Input): LibPgQueryResult<T> =
    instance.withInstance { running ->
      val inputPointer = running.allocate(input.bytes.size + 1L)
      running.memory().write(inputPointer, input.bytes)
      running.memory().writeByte(inputPointer + input.bytes.size, 0)
      val result = running.allocate(entry.resultBytes.toLong())
      running.call(entry.function, result.toLong(), inputPointer.toLong())

      val error = entry.readError(running, result, input)
      // Calling release or free on a trapped instance raises a new error that hides the trap.
      val value = if (error == null) entry.read(running, result) else null
      running.call(entry.release, result.toLong())
      running.call("free", result.toLong())
      running.call("free", inputPointer.toLong())
      error ?: LibPgQueryResult.Accepted(checkNotNull(value))
    }

  /**
   * Runs [block] on a new thread with [WASM_STACK_BYTES] of stack.
   *
   * @throws Throwable whatever [block] throws.
   */
  @Suppress("TooGenericExceptionCaught")
  private fun <T> onWasmStack(block: () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    val thread = Thread(
      null,
      {
        try {
          value = block()
        } catch (throwable: Throwable) {
          failure = throwable
        }
      },
      "libpg-query-wasm",
      WASM_STACK_BYTES,
    )
    thread.start()
    var wasInterrupted = false
    while (true) {
      try {
        thread.join()
        break
      } catch (_: InterruptedException) {
        wasInterrupted = true
      }
    }
    if (wasInterrupted) Thread.currentThread().interrupt()
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
  }
}
