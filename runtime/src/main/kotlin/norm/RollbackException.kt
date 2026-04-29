package norm

/** Signals an explicit rollback from [TransactionScope.rollback]. Not an [Exception]. */
internal class RollbackException : Throwable()
