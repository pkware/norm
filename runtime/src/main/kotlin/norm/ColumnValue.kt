package norm

/**
 * A value to bind for an `INSERT` column that has a database-side `DEFAULT` expression.
 *
 * A synthesized `insert*` function generates one `ColumnValue<T>` parameter per such column, so a
 * caller who wants the database's own `DEFAULT` for that column can simply omit the argument
 * (every such parameter defaults to [Default]), while a caller who wants to provide their own value
 * passes [Set].
 *
 * @param T The Kotlin type the column is normally bound as. `T` itself may be a nullable type when
 *   the column allows SQL `NULL` — in that case [Set.value] of `null` binds an explicit SQL `NULL`,
 *   distinct from [Default], which omits the column from the statement entirely so the database's
 *   own `DEFAULT` expression applies.
 */
public sealed class ColumnValue<out T> {

  /**
   * The column is omitted from the `INSERT` statement's column list, so the database evaluates its
   * own `DEFAULT` expression for it.
   */
  public data object Default : ColumnValue<Nothing>()

  /**
   * The column is bound explicitly to [value].
   *
   * @property value The value to bind. May itself be `null` when the column is nullable — that
   *   binds an explicit SQL `NULL`, which is not the same as [Default].
   */
  public data class Set<out T>(public val value: T) : ColumnValue<T>()
}
