package norm.generator.libpgquery

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.wasm.types.ExternalType

/**
 * The texts passed to `pg_query_parse` and `pg_query_scan` while [RecordingMachine.record] ran, in call order.
 */
internal class RecordedCalls {
  val parseTexts = mutableListOf<String>()
  val scanTexts = mutableListOf<String>()
}

/**
 * Forwards every call to [delegate] and records the input text of `pg_query_parse` and `pg_query_scan` calls that the
 * calling thread makes inside [record].
 */
internal class RecordingMachine(private val instance: Instance, private val delegate: Machine) : Machine {
  private val functionNames: Map<Int, String> = instance.module().exportSection().let { exports ->
    (0 until exports.exportCount())
      .map(exports::getExport)
      .filter { it.exportType() == ExternalType.FUNCTION }
      .associate { it.index() to it.name() }
  }

  override fun call(funcId: Int, args: LongArray): LongArray? {
    val recorded = recording.get()
    if (recorded != null) {
      when (functionNames[funcId]) {
        LibPgQueryEntry.PARSE.function -> recorded.parseTexts += readInput(args)
        LibPgQueryEntry.SCAN.function -> recorded.scanTexts += readInput(args)
      }
    }
    return delegate.call(funcId, args)
  }

  private fun readInput(args: LongArray): String = instance.memory().readCString(args[INPUT_ARGUMENT].toInt())

  companion object {
    /** Index of the input pointer in the arguments of an entry point, after the result pointer. */
    private const val INPUT_ARGUMENT = 1

    private val recording = ThreadLocal<RecordedCalls?>()

    private val recoverable: RecoverableInstance<*> = LibPgQuery::class.java.getDeclaredField("instance")
      .apply { isAccessible = true }
      .get(null) as RecoverableInstance<*>

    private val instanceField = RecoverableInstance::class.java.getDeclaredField("instance")
      .apply { isAccessible = true }

    private val lock: Any = RecoverableInstance::class.java.getDeclaredField("lock")
      .apply { isAccessible = true }
      .get(recoverable)

    /**
     * Runs [block] on a [LibPgQuery] instance that uses a [RecordingMachine], and returns the texts that the calling
     * thread passed to the parser and the lexer, including calls that throw.
     *
     * The recording instance replaces the one that [LibPgQuery] holds and is swapped back when [block] ends. Reflection
     * does the swap because [LibPgQuery] offers no way to install another instance.
     *
     * @throws IllegalStateException if [LibPgQuery] discarded the recording instance during [block], which happens
     *   after a failure other than a [LibPgQueryException]. The recorded texts would then be incomplete.
     */
    fun record(block: () -> Unit): RecordedCalls {
      val calls = RecordedCalls()
      val recordingInstance = newInstance { RecordingMachine(it, LibPgQueryMachine(it)) }
      val original = synchronized(lock) {
        instanceField.get(recoverable).also { instanceField.set(recoverable, recordingInstance) }
      }
      var isStillInstalled = false
      recording.set(calls)
      try {
        block()
      } finally {
        recording.remove()
        synchronized(lock) {
          isStillInstalled = instanceField.get(recoverable) === recordingInstance
          instanceField.set(recoverable, original)
        }
      }
      check(isStillInstalled) { "LibPgQuery dropped the recording instance while the block ran" }
      return calls
    }
  }
}
