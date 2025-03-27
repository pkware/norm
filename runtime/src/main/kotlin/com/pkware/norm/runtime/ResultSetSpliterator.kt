package com.pkware.norm.runtime

import java.sql.ResultSet
import java.util.Spliterator
import java.util.function.Consumer
import java.util.stream.Stream

/**
 * [Spliterator] over a [ResultSet]
 *
 * SQL resources are held open by this [Spliterator]. Accordingly, register the [closeSqlResources] with
 * [Stream.onClose] to ensure resources are adequately closed. This instance will also close resources when it reaches
 * the end of the [ResultSet].
 *
 * @param resultSet from which to load rows.
 * @param closeSqlResources Action which closes all related SQL resources.
 * @param rowReader Action to read a row from the [resultSet] and turn it into a [RowType].
 */
internal class ResultSetSpliterator<RowType>(
  private val resultSet: ResultSet,
  private val closeSqlResources: () -> Unit,
  private val rowReader: ResultSet.() -> RowType,
) : Spliterator<RowType> {
  override fun tryAdvance(action: Consumer<in RowType>): Boolean = if (resultSet.next()) {
    action.accept(rowReader(resultSet))
    true
  } else {
    closeSqlResources()
    false
  }

  override fun forEachRemaining(action: Consumer<in RowType>) {
    while (resultSet.next()) {
      action.accept(rowReader(resultSet))
    }
    closeSqlResources()
  }

  override fun trySplit(): Spliterator<RowType>? = null

  override fun estimateSize(): Long = Long.MAX_VALUE

  override fun characteristics(): Int = Spliterator.SIZED or Spliterator.IMMUTABLE
}
