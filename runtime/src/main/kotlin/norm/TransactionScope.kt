package norm

/**
 * Receiver for a [Transactable.transaction] block.
 *
 * Provides [rollback] for explicit, non-exceptional transaction cancellation.
 */
public interface TransactionScope {
  /**
   * Immediately rolls back the current transaction or savepoint and returns from the transaction
   * block. The enclosing transaction (if any) is unaffected.
   *
   * This function never returns normally.
   */
  public fun rollback(): Nothing
}
