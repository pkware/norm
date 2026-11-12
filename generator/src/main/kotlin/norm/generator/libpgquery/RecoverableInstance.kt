package norm.generator.libpgquery

/**
 * Holds one lazily created instance of [T] and runs callers on it one at a time.
 *
 * The instance is kept after a block returns or throws a [LibPgQueryException]. After any other [Throwable] its state
 * may be inconsistent, so it is dropped and the next call creates a new one.
 *
 * @param create builds a fresh instance. Called while the lock is held.
 */
internal class RecoverableInstance<T : Any>(private val create: () -> T) {
  private val lock = Any()
  private var instance: T? = null

  /**
   * Runs [block] on the instance while holding the lock.
   *
   * @return the value [block] returns.
   * @throws Throwable whatever [create] or [block] throws.
   */
  fun <R> withInstance(block: (T) -> R): R = synchronized(lock) {
    val current = instance ?: create().also { instance = it }
    try {
      block(current)
    } catch (cause: Throwable) {
      if (cause !is LibPgQueryException) instance = null
      throw cause
    }
  }
}
