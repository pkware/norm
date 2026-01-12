package norm

/**
 * Transaction that does not return a result.
 *
 * @see [TransactionWithReturn].
 */
public interface TransactionWithoutReturn {
  /**
   * Rolls back this transaction.
   */
  public fun rollback(): Nothing
}
