@file:JvmName("Functions")

package norm

/**
 * Returns the [input].
 */
public fun <T> inputValue(input: T): T = input

/**
 * Combines the results of multiple [java.sql.Statement.executeBatch] calls.
 *
 * @param results The batch results
 * @param totalCount Total number of results.
 * @param batchSize Size of the query batch.
 */
public fun combineExecBatchResults(results: List<IntArray>, totalCount: Int, batchSize: Int): IntArray =
  if (results.size > 1) {
    val result = results[0].copyOf(totalCount)
    for (i in 1 until results.size) {
      results[i].copyInto(result, batchSize * i)
    }
    result
  } else {
    results[0]
  }
