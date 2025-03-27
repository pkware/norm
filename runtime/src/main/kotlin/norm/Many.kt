package norm

import java.lang.reflect.Method
import java.sql.ResultSet
import java.util.Spliterator
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger
import java.util.stream.Stream
import java.util.stream.StreamSupport

public class Many<RowType>(
  private val resultSet: ResultSet,
  private val mapper: RowMapper<RowType>,
) : AutoCloseable {
  public fun stream(): Stream<RowType> {
    // StreamSupport doesn't close the underlying Spliterator! https://bugs.openjdk.org/browse/JDK-8318856
    // We make a good effort to close the ResultSet when all rows have been consumed, but it's easy for a Stream
    // consumer to not consume the whole ResultSet (findFirst(), for example). Accordingly, we use reflection to try
    // to ensure the ResultSet is closed.
    return StreamSupport.stream(ResultSetSpliterator<RowType>(resultSet, mapper), false).also {
      ON_CLOSE_ABSTRACT_PIPELINE?.invoke(it, resultSet::close)
    }
  }

  public fun list(): List<RowType> = stream().use { it.toList() }

  override fun close() {
    resultSet.close()
  }

  private companion object {
    private val LOGGER = Logger.getLogger(Many::class.java.canonicalName)
    private val ON_CLOSE_ABSTRACT_PIPELINE: Method?

    init {
      val onClose = try {
        val abstractPipeline = Class.forName("java.util.AbstractPipeline")
        abstractPipeline.getDeclaredMethod("onClose").apply {
          isAccessible = true
        }
      } catch (e: ClassNotFoundException) {
        LOGGER.log(Level.WARNING, "Class java.util.AbstractPipeline was not found. SQL ResultSets may leak.", e)
        null
      } catch (e: NoSuchMethodException) {
        LOGGER.log(
          Level.WARNING,
          "Method onClose on java.util.AbstractPipeline was not found. SQL ResultSets may leak.",
          e,
        )
        null
      }
      ON_CLOSE_ABSTRACT_PIPELINE = onClose
    }
  }
}

internal class ResultSetSpliterator<RowType>(
  private val resultSet: ResultSet,
  private val mapper: RowMapper<RowType>,
) : Spliterator<RowType>, AutoCloseable {
  override fun tryAdvance(action: Consumer<in RowType>) = if (resultSet.next()) {
    val row = mapper(resultSet)
    action.accept(row)
    true
  } else {
    resultSet.close()
    false
  }

  override fun forEachRemaining(action: Consumer<in RowType>) {
    while (resultSet.next()) {
      action.accept(mapper(resultSet))
    }
    resultSet.close()
  }

  override fun trySplit(): Spliterator<RowType>? = null

  override fun estimateSize(): Long = Long.MAX_VALUE

  override fun characteristics(): Int = Spliterator.SIZED or Spliterator.IMMUTABLE
  override fun close() {
    resultSet.close()
  }
}
