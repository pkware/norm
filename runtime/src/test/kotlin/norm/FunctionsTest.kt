package norm

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FunctionsTest {

  @Nested
  inner class CombineExecBatchResults {

    @Test
    fun `single result returns the same array`() {
      val singleResult = intArrayOf(1, 1, 1)

      val combined = combineExecBatchResults(
        results = listOf(singleResult),
        totalCount = 3,
        batchSize = 3
      )

      assertThat(combined).isSameInstanceAs(singleResult)
    }

    @Test
    fun `two equal batches are combined`() {
      val batch1 = intArrayOf(1, 1, 1)
      val batch2 = intArrayOf(2, 2, 2)

      val combined = combineExecBatchResults(
        results = listOf(batch1, batch2),
        totalCount = 6,
        batchSize = 3
      )

      assertThat(combined.toList()).containsExactly(1, 1, 1, 2, 2, 2)
    }

    @Test
    fun `three batches are combined in order`() {
      val batch1 = intArrayOf(1, 1)
      val batch2 = intArrayOf(2, 2)
      val batch3 = intArrayOf(3, 3)

      val combined = combineExecBatchResults(
        results = listOf(batch1, batch2, batch3),
        totalCount = 6,
        batchSize = 2
      )

      assertThat(combined.toList()).containsExactly(1, 1, 2, 2, 3, 3)
    }

    @Test
    fun `partial final batch is handled correctly`() {
      val batch1 = intArrayOf(1, 1, 1)
      val batch2 = intArrayOf(2, 2) // Partial batch

      val combined = combineExecBatchResults(
        results = listOf(batch1, batch2),
        totalCount = 5,
        batchSize = 3
      )

      assertThat(combined.toList()).containsExactly(1, 1, 1, 2, 2)
    }

    @Test
    fun `result array has correct size`() {
      val batch1 = intArrayOf(1, 1, 1, 1, 1)
      val batch2 = intArrayOf(2, 2, 2, 2, 2)
      val batch3 = intArrayOf(3, 3, 3)

      val combined = combineExecBatchResults(
        results = listOf(batch1, batch2, batch3),
        totalCount = 13,
        batchSize = 5
      )

      assertThat(combined.size).isEqualTo(13)
    }

    @Test
    fun `preserves actual affected row counts`() {
      // Simulates real batch results where some inserts might affect 0 rows
      val batch1 = intArrayOf(1, 0, 1) // Middle insert was a no-op
      val batch2 = intArrayOf(1, 1)

      val combined = combineExecBatchResults(
        results = listOf(batch1, batch2),
        totalCount = 5,
        batchSize = 3
      )

      assertThat(combined.toList()).containsExactly(1, 0, 1, 1, 1)
    }

    @Test
    fun `handles batch size of 1`() {
      val batch1 = intArrayOf(1)
      val batch2 = intArrayOf(2)
      val batch3 = intArrayOf(3)

      val combined = combineExecBatchResults(
        results = listOf(batch1, batch2, batch3),
        totalCount = 3,
        batchSize = 1
      )

      assertThat(combined.toList()).containsExactly(1, 2, 3)
    }
  }

  @Nested
  inner class InputValue {

    @Test
    fun `returns the input unchanged`() {
      val input = "test"

      val result = inputValue(input)

      assertThat(result).isSameInstanceAs(input)
    }

    @Test
    fun `works with nullable values`() {
      val input: String? = null

      val result = inputValue(input)

      assertThat(result).isEqualTo(null)
    }
  }
}