package norm.generator

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

/**
 * sqlc command indicating how a SQL statement should be executed.
 *
 * See the [sqlc documentation](https://docs.sqlc.dev/en/latest/reference/query-annotations.html) for details.
 */
internal enum class Command(private val sqlcCmd: String) {
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
  EXEC(":exec"),
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
    EXEC -> BOOLEAN
    EXEC_ROWS -> INT
  }

  override fun toString(): String = sqlcCmd

  companion object {
    private val NORM_MANY = ClassName(RUNTIME_PACKAGE, "Many")
    internal val NORM_QUERY = ClassName(RUNTIME_PACKAGE, "Query")

    /**
     * Finds the [Command] matching the sqlc `cmd` string.
     *
     * See the [sqlc documentation](https://docs.sqlc.dev/en/latest/reference/query-annotations.html) for details.
     */
    @Suppress("ThrowsCount") // The many throws are very intentional and the clearest way to accomplish this
    fun fromSqlcCmd(cmd: String): Command {
      when (cmd) {
        ":batchexec" -> throw UnsupportedOperationException(
          "Unsupported sqlc query annotation ':batchexec'." +
            " Norm performs batching via Java APIs, not in SQL. Use a regular :exec instead.",
        )
        ":batchone" -> throw UnsupportedOperationException(
          "Unsupported sqlc query annotation ':batchone'." +
            " JDBC doesn't support returning values from batch executions. Use a regular :one instead.",
        )
        ":batchmany" -> throw UnsupportedOperationException(
          "Unsupported sqlc query annotation ':batchmany'." +
            " JDBC doesn't support returning values from batch executions. Use a regular :many instead.",
        )
        // We technically could support execlastid, but the value of it is low.
        // It adds cognitive overhead compared to a simple RETURNING clause, and is less explicit.
        ":execlastid" -> throw UnsupportedOperationException(
          "Unsupported sqlc query annotation ':execlastid'." +
            " Use a RETURNING clause with a regular :one or :many instead.",
        )
        // We technically could support execresult, but the value of it is low.
        // It adds cognitive overhead compared to a simple RETURNING clause, and is less explicit.
        ":execresult" -> throw UnsupportedOperationException(
          "Unsupported sqlc query annotation ':execresult'." +
            " Use a RETURNING clause with a regular :one or :many instead.",
        )
      }
      return entries.firstOrNull {
        it.sqlcCmd == cmd
      } ?: throw UnsupportedOperationException("Unsupported sqlc query annotation '$cmd'.")
    }
  }
}
