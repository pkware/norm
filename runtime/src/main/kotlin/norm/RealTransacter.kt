package norm

private class RollbackException(val value: Any? = null) : Throwable()

/**
 * DSL interface providing [rollback] and nested [transaction] calls.
 */
private class TransactionWrapper<R>(val transaction: Transaction) :
  TransactionWithoutReturn,
  TransactionWithReturn<R> {
  override fun rollback(): Nothing {
    transaction.checkThreadConfinement()
    throw RollbackException()
  }
  override fun rollback(returnValue: R): Nothing {
    transaction.checkThreadConfinement()
    throw RollbackException(returnValue)
  }
}

/**
 * A transaction-aware [NormDriver] wrapper which can begin a [Transaction] on the current connection.
 */
@Suppress("UnnecessaryAbstractClass") // This is abstract because generated code is meant to subclass it.
public abstract class RealTransacter(protected val driver: NormDriver) : Transacter {
  override fun transaction(noEnclosing: Boolean, body: TransactionWithoutReturn.() -> Unit) {
    transactionWithWrapper<Unit?>(noEnclosing, body)
  }

  override fun <R> transactionWithResult(noEnclosing: Boolean, bodyWithReturn: TransactionWithReturn<R>.() -> R): R =
    transactionWithWrapper(noEnclosing, bodyWithReturn)

  private fun <R> postTransactionCleanup(
    transaction: Transaction,
    enclosing: Transaction?,
    thrownException: Throwable?,
    returnValue: R?,
  ): R {
    if (enclosing != null && thrownException !is RollbackException) {
      // Only propagate failure to parent if it's a real exception.
      // Explicit rollbacks are isolated to their savepoint.
      enclosing.childrenSuccessful = transaction.successful && transaction.childrenSuccessful
    }

    if (thrownException is RollbackException) {
      // Return rollback value for both outermost and nested transactions.
      // The savepoint was already rolled back in Transaction.endTransaction().
      @Suppress("UNCHECKED_CAST")
      return thrownException.value as R
    } else if (thrownException != null) {
      throw thrownException
    } else {
      // We can safely cast to R here because any code path that led here will have set the
      // returnValue to the result of the block
      @Suppress("UNCHECKED_CAST")
      return returnValue as R
    }
  }

  private fun <R> transactionWithWrapper(noEnclosing: Boolean, wrapperBody: TransactionWrapper<R>.() -> R): R {
    val transaction = driver.newTransaction()
    val enclosing = transaction.enclosingTransaction

    check(enclosing == null || !noEnclosing) { "Already in a transaction" }

    var thrownException: Throwable? = null
    var returnValue: R? = null

    @Suppress("TooGenericExceptionCaught") // The throwable is re-thrown
    try {
      transaction.transacter = this
      returnValue = TransactionWrapper<R>(transaction).wrapperBody()
      transaction.successful = true
    } catch (e: Throwable) {
      thrownException = e
    } finally {
      transaction.endTransaction()
    }
    return postTransactionCleanup(transaction, enclosing, thrownException, returnValue)
  }
}
