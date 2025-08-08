/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package norm

import kotlin.jvm.Throws

/**
 * A transaction-aware [NormDriver] wrapper which can begin a [Transaction] on the current connection.
 */
public interface Transacter {
  /**
   * Starts a [Transaction] and runs [bodyWithReturn] in that transaction.
   *
   * @throws IllegalStateException if [noEnclosing] is `true` and there is already an active
   *   [Transaction] on this thread.
   */
  @Throws(IllegalStateException::class)
  public fun <R> transactionWithResult(
    noEnclosing: Boolean = false,
    bodyWithReturn: TransactionWithReturn<R>.() -> R,
  ): R

  /**
   * Starts a [Transaction] and runs [body] in that transaction.
   *
   * @throws IllegalStateException if [noEnclosing] is `true` and there is already an active
   *   [Transaction] on this thread.
   */
  @Throws(IllegalStateException::class)
  public fun transaction(noEnclosing: Boolean = false, body: TransactionWithoutReturn.() -> Unit)
}
