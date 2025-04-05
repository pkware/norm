package norm

/**
 * Transaction that returns a result.
 *
 * @see [TransactionWithoutReturn].
 */
public interface TransactionWithReturn<R> {
  /**
   * Rolls back this transaction.
   */
  public fun rollback(returnValue: R): Nothing

  /**
   * Begin an inner transaction.
   */
  public fun <R> transaction(body: TransactionWithReturn<R>.() -> R): R
}
