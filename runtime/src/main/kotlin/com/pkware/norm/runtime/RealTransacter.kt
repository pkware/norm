package com.pkware.norm.runtime

private class RollbackException(val value: Any? = null) : Throwable()
private class TransactionWrapper<R>(
  val transaction: Transaction,
) : TransactionWithoutReturn, TransactionWithReturn<R> {
  override fun rollback(): Nothing {
    transaction.checkThreadConfinement()
    throw RollbackException()
  }
  override fun rollback(returnValue: R): Nothing {
    transaction.checkThreadConfinement()
    throw RollbackException(returnValue)
  }

  override fun transaction(body: TransactionWithoutReturn.() -> Unit) =
    (transaction.transacter as Transacter).transaction(false, body)

  override fun <R> transaction(body: TransactionWithReturn<R>.() -> R): R =
    (transaction.transacter as Transacter).transactionWithResult(false, body)
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
    if (enclosing != null) {
      enclosing.childrenSuccessful = transaction.successful && transaction.childrenSuccessful
    }

    if (enclosing == null && thrownException is RollbackException) {
      // We can safely cast to R here because the rollback exception is always created with the
      // correct type.
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
