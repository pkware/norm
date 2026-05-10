package norm

import org.intellij.lang.annotations.Language
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Defines the shape of a method capable of processing a [Many] query.
 *
 * See [NormDriver.queryMany] and [NormDriver.dynamic] for implementations.
 *
 * @param RowType Type to extract the row into.
 */
public fun interface ManyProcessor<RowType, Return> {
  /**
   * @param sql to execute.
   * @param rowReader Expression to extract a [RowType] from the [ResultSet].
   * @param queryBinder Expression to populate and prepare the [PreparedStatement].
   * @return the deferred execution of the [sql] query.
   */
  public fun invoke(
    @Language("PostgreSQL") sql: String,
    rowReader: ResultSet.() -> RowType,
    queryBinder: (PreparedStatement.() -> Unit)?,
  ): Return
}
