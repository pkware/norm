@file:JvmName("Functions")

package norm

import java.sql.ResultSet
import java.sql.SQLException

/**
 * Returns the [input].
 */
public fun <T> inputValue(input: T): T = input

/**
 * Combines the results of multiple [java.sql.Statement.executeBatch] calls.
 *
 * @param results The batch results. Empty when the batched input was empty, in which case an empty
 *   array is returned.
 * @param totalCount Total number of results.
 * @param batchSize Size of the query batch.
 */
public fun combineExecBatchResults(results: List<IntArray>, totalCount: Int, batchSize: Int): IntArray =
  when (results.size) {
    0 -> IntArray(0)
    1 -> results[0]
    else -> {
      val result = results[0].copyOf(totalCount)
      for (i in 1 until results.size) {
        results[i].copyInto(result, batchSize * i)
      }
      result
    }
  }

/**
 * Reads all rows from [resultSet] and appends them to [destination].
 *
 * Called by generated batch-with-returning code after each
 * [java.sql.PreparedStatement.executeBatch] flush. The [destination] list is mutated in place
 * so that results from multiple flushes accumulate into a single list.
 *
 * @param resultSet The result set to drain (typically from
 *   [java.sql.PreparedStatement.getGeneratedKeys]). Iterated until
 *   [java.sql.ResultSet.next] returns `false`.
 * @param rowReader Extracts a [RowType] from the current row of [resultSet].
 * @param destination The mutable list to append results to.
 */
@Throws(SQLException::class)
public fun <RowType> readGeneratedKeys(
  resultSet: ResultSet,
  rowReader: ResultSet.() -> RowType,
  destination: MutableList<RowType>,
) {
  while (resultSet.next()) {
    destination.add(rowReader(resultSet))
  }
}
