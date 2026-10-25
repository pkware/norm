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
 * All offsets are `String` indexes. A single wasm instance serves every call under a lock, and is replaced after a
 * call fails for a reason other than a [LibPgQueryException].
 */
internal object LibPgQuery {
  private val parseTreeOffsets: ParseTreeOffsets by lazy {
    openResource("/libpg_query_location_fields.txt").use { stream ->
      ParseTreeOffsets(stream.bufferedReader().readLines().filter { it.isNotBlank() }.toSet())
    }
  }

  private val instance = RecoverableInstance(::newInstance)

  /**
   * Parses [sql] with PostgreSQL's grammar and returns libpg_query's JSON parse tree.
   *
   * Location fields hold `String` indexes into [sql].
   *
   * @throws LibPgQueryException if the grammar rejects [sql].
   * @throws IllegalArgumentException if [sql] contains a NUL character.
   */
  fun parse(sql: String): JsonValue.JsonObject {
    val input = Utf8Input(sql)
    val text = invoke(LibPgQueryEntry.PARSE, input)
    val tree = JsonValue.parse(text)
    check(tree is JsonValue.JsonObject) { "libpg_query returned a parse tree that is not a JSON object" }
    return parseTreeOffsets.convert(tree, input)
  }

  /**
   * Splits [sql] into PostgreSQL lexer tokens. Whitespace is not a token and comments are.
   *
   * @throws LibPgQueryException if the lexer rejects [sql], for example on an unterminated quoted identifier.
   * @throws IllegalArgumentException if [sql] contains a NUL character.
   */
  fun scan(sql: String): List<ScanToken> {
    val input = Utf8Input(sql)
    val encoded = invoke(LibPgQueryEntry.SCAN, input)
    return decodeScanResult(encoded, input)
  }

  /**
   * Splits [sql] into statements at top-level `;`, using only the lexer, so text that does not parse is accepted.
   * Text holding no keyword, such as `;` or `1;`, yields no statement.
   *
   * @throws LibPgQueryException if the lexer rejects [sql], for example on an unterminated quoted identifier.
   * @throws IllegalArgumentException if [sql] contains a NUL character.
   */
  fun split(sql: String): List<SplitStatement> = split(LibPgQueryEntry.SPLIT, sql)

  /**
   * Splits [sql] into statements with PostgreSQL's grammar. Unlike [split], it keeps a `BEGIN ATOMIC` function body
   * with its `;` as one statement.
   *
   * @throws LibPgQueryException if the grammar rejects [sql].
   * @throws IllegalArgumentException if [sql] contains a NUL character.
   */
  fun splitWithParser(sql: String): List<SplitStatement> = split(LibPgQueryEntry.SplitWithParser, sql)

  private fun split(entry: LibPgQueryEntry.Split, sql: String): List<SplitStatement> {
    val input = Utf8Input(sql)
    val pairs = invoke(entry, input)
    return (pairs.indices step 2).map { index ->
      SplitStatement(
        input.indexOfByteOffset(pairs[index]),
        input.indexOfByteOffset(pairs[index] + pairs[index + 1]),
      )
    }
  }

  /**
   * Calls [entry] on [input] and returns what the entry reads from its result struct.
   *
   * Wasm memory is released only when the instance stays in use, which is on success and on a [LibPgQueryException].
   *
   * @throws LibPgQueryException if libpg_query reports an error.
   */
  private fun <T : Any> invoke(entry: LibPgQueryEntry<T>, input: Utf8Input): T = instance.withInstance { running ->
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
    if (error != null) throw error
    checkNotNull(value)
  }
}
