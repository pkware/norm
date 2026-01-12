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
}
