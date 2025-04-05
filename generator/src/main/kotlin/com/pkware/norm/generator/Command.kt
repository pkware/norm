package com.pkware.norm.generator

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

/**
 * sqlc command indicating how a SQL statement should be executed.
 *
 * See the [sqlc documentation](https://docs.sqlc.dev/en/latest/reference/query-annotations.html) for details.
 */
// Unsupported commands:
// - batchexec: Batching behavior is defined by caller convention, not in generated code
// - batchone: JDBC doesn't support returning values from batch executions
// - batchmany: JDBC doesn't support returning values from batch executions
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
  EXEC_RESULT(":execresult"),
  EXEC_ROWS(":execrows"),
  EXEC_LAST_ID(":execlastid"),
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
    // via executeUpdate() followed by retrieving the generated keys. See go code for details - this one is non-trivial.
    EXEC_RESULT -> NORM_MANY.parameterizedBy(LONG)
    EXEC_ROWS -> INT
    // executeUpdate followed by retrieving the generate key. See go code for details.
    EXEC_LAST_ID -> error("Use a RETURNING clause to get generated IDs instead of the 'execlastid' command")
  }

  override fun toString(): String = sqlcCmd

  companion object {
    private val NORM_MANY = ClassName(RUNTIME_PACKAGE, "Many")

    /**
     * Finds the [Command] matching the sqlc `cmd` string.
     *
     * See the [sqlc documentation](https://docs.sqlc.dev/en/latest/reference/query-annotations.html) for details.
     */
    fun fromSqlcCmd(cmd: String): Command = entries.first { it.sqlcCmd == cmd }
  }
}
