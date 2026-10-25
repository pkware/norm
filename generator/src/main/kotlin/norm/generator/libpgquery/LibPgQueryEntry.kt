package norm.generator.libpgquery

import com.dylibso.chicory.runtime.Instance

/**
 * Describes a libpg_query entry point by the layout of the result struct it fills in.
 *
 * The offsets mirror the structs in the `pg_query.h` of the pinned libpg_query commit, laid out for wasm32, where
 * pointers, `size_t` and `int` take 4 bytes. A libpg_query upgrade that changes those structs shifts the offsets
 * without any error from wasm, so each one must be checked against the new header.
 *
 * @param T what [read] extracts from a successful result.
 * @property function exported name of the entry point.
 * @property release exported name of the function that frees the contents of the result struct.
 * @property resultBytes size of the result struct in bytes.
 * @property errorOffset byte offset of the struct's `PgQueryError*` member, which is `NULL` on success.
 *   `PgQueryError` holds `message` at 0 and `cursorpos` at 16.
 */
internal sealed class LibPgQueryEntry<T : Any>(
  val function: String,
  val release: String,
  val resultBytes: Int,
  val errorOffset: Int,
) {
  /** Extracts the result from the struct at [result] in the memory of [instance]. Called only when no error is set. */
  abstract fun read(instance: Instance, result: Int): T

  /**
   * Reads the `PgQueryError` that the result struct at [result] points to in the memory of [instance].
   *
   * @return the error with its position mapped to a `String` index of [input], or `null` when no error is set.
   */
  fun readError(instance: Instance, result: Int, input: Utf8Input): LibPgQueryException? {
    val memory = instance.memory()
    val error = memory.readInt(result + errorOffset)
    if (error == 0) return null
    val message = memory.readCString(memory.readInt(error))
    val cursorPosition = memory.readInt(error + CURSOR_OFFSET)
    val cursorIndex = if (cursorPosition > 0) input.indexOfCodePointOffset(cursorPosition - 1) else null
    return LibPgQueryException(message, cursorIndex)
  }

  /** `PgQueryParseResult`: `parse_tree` at 0, `stderr_buffer` at 4 and `error` at 8. Reads the JSON parse tree. */
  object PARSE : LibPgQueryEntry<String>("pg_query_parse", "pg_query_free_parse_result", 12, 8) {
    override fun read(instance: Instance, result: Int): String =
      instance.memory().readCString(instance.memory().readInt(result))
  }

  /**
   * `PgQueryScanResult`: `pbuf` (`len` at 0, `data` at 4) at 0, `stderr_buffer` at 8 and `error` at 12. Reads the
   * protobuf bytes of `pbuf`.
   */
  object SCAN : LibPgQueryEntry<ByteArray>("pg_query_scan", "pg_query_free_scan_result", 16, 12) {
    override fun read(instance: Instance, result: Int): ByteArray {
      val memory = instance.memory()
      return memory.readBytes(memory.readInt(result + 4), memory.readInt(result))
    }
  }

  /**
   * `PgQuerySplitResult`: `stmts` at 0, `n_stmts` at 4, `stderr_buffer` at 8 and `error` at 12. `stmts` points to an
   * array of `PgQuerySplitStmt*`, each holding `stmt_location` at 0 and `stmt_len` at 4. Reads the UTF-8 byte
   * location and length of every statement as consecutive pairs.
   *
   * @param function exported name of the splitter that fills in the struct.
   */
  sealed class Split(function: String) : LibPgQueryEntry<IntArray>(function, "pg_query_free_split_result", 16, 12) {
    override fun read(instance: Instance, result: Int): IntArray {
      val memory = instance.memory()
      val statements = memory.readInt(result)
      return IntArray(memory.readInt(result + 4) * 2).also { pairs ->
        for (index in pairs.indices step 2) {
          val statement = memory.readInt(statements + index * 2)
          pairs[index] = memory.readInt(statement)
          pairs[index + 1] = memory.readInt(statement + 4)
        }
      }
    }
  }

  /** Splits with the lexer alone. */
  object SPLIT : Split("pg_query_split_with_scanner")

  /** Splits with the grammar, which fails on text that does not parse. */
  object SplitWithParser : Split("pg_query_split_with_parser")

  private companion object {
    /** Byte offset of `PgQueryError.cursorpos`, after `message`, `funcname`, `filename` and `lineno`. */
    const val CURSOR_OFFSET = 16
  }
}
