package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT

/**
 * Command indicating how a SQL statement should be executed.
 */
internal enum class Command(private val value: String) {
  /**
   * The SQL statement must return at exactly 1 result.
   *
   * Examples include `SELECT` queries, PRAGMAs, and other DMLs that have `RETURNING` clauses.
   */
  ONE(":one"),

  /**
   * The query can return 0 or more results.
   *
   * Examples include `SELECT` queries, PRAGMAs, and other DMLs that have `RETURNING` clauses.
   */
  MANY(":many"),

  /**
   * Executes without a return.
   */
  EXEC(":exec"),

  /**
   * Returns the number of rows updated.
   */
  EXEC_ROWS(":execrows"),
  ;

  /**
   * Applies the Command to a [TypeName], thereby preparing it for consumption in a KotlinPoet builder.
   *
   * @param singleRowType Type of a single result row. `null` is valid as some Commands don't allow for or require a
   * return row.
   * @return The type
   */
  fun applyTo(singleRowType: TypeName?): TypeName = when (this) {
    ONE -> requireNotNull(singleRowType) { "A DML statement marked as $this doesn't return anything" }
    MANY -> {
      requireNotNull(singleRowType) { "A DML statement marked as $this doesn't return anything" }
      NORM_MANY.parameterizedBy(singleRowType)
    }
    // execute(), low value, probably don't use
    EXEC -> UNIT
    EXEC_ROWS -> INT
  }

  override fun toString(): String = value

  companion object {
    private val NORM_MANY = ClassName(RUNTIME_PACKAGE, "Many")
    internal val NORM_QUERY = ClassName(RUNTIME_PACKAGE, "Query")

    /**
     * Finds the [Command] matching the [value].
     */
    fun fromSql(value: String): Command = entries.firstOrNull {
      it.value == value
    } ?: throw UnsupportedOperationException("Unsupported sqlc query annotation '$value'.")
  }
}
