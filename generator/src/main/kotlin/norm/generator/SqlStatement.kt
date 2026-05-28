package norm.generator

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import plugin.Catalog
import plugin.Column
import plugin.Parameter
import plugin.Query
import plugin.Table
import java.sql.ResultSet

/**
 * Internal representation of a SQL statement.
 *
 * Aims to carry the information need to generate Kotlin code for a SQL statement.
 */
internal class SqlStatement(
  private val catalog: Catalog,
  private val query: Query,
  private val generator: TypeRepository,
) {

  /**
   * Unique input parameters for this statement's function signature.
   *
   * When a SQL query reuses the same named parameter (e.g., `:scannedAt` at two positions),
   * those positions are collapsed into a single entry here. For queries without named parameter
   * reuse, this contains one entry per `?` placeholder — identical to [Query.params].
   *
   * Use [parameterBindings] for JDBC `?`-position iteration in implementation bodies.
   */
  val parameters: List<Parameter>

  /**
   * Command applied to the SQL statement.
   *
   * Statements are annotated with a Command that defines how they'll behave at runtime. Where [resultRowShape]
   * contains information about the shape of a single row, [command] impacts what the Kotlin return type of a statement
   * is.
   *
   * See [Command].
   */
  val command get() = Command.fromSql(query.cmd)

  /**
   * Whether the SQL statement is batchable in JDBC.
   */
  val canBeBatched: Boolean
    get() {
      val command = command
      // SELECTs can't be batched
      if (command == Command.ONE || command == Command.MANY) return false

      // DML that isn't parameterized has no reason to run in batch
      if (parameters.isEmpty()) return false

      // DML statements that return (via SQL RETURNING, for example) can't be batched in JDBC
      return query.columns.isEmpty()
    }

  /**
   * Whether a batch variant returning generated values can be safely generated.
   *
   * JDBC's batch API ([java.sql.PreparedStatement.executeBatch]) does not support SQL `RETURNING`
   * clauses — batch results are only available via [java.sql.PreparedStatement.getGeneratedKeys].
   *
   * Only true for CRUD-synthesized `:one` INSERT queries that have both parameters (insertable
   * columns) and result columns (RETURNING columns). The RETURNING clause is stripped from [sql]
   * to produce [batchSql], and the column names are passed to
   * [java.sql.Connection.prepareStatement] for [java.sql.PreparedStatement.getGeneratedKeys]
   * retrieval.
   */
  val canBeBatchedWithReturn: Boolean
    get() = query.is_synthesized_insert &&
      command == Command.ONE &&
      parameters.isNotEmpty() &&
      query.columns.isNotEmpty()

  /**
   * SQL text for the batch variant, with the RETURNING clause stripped.
   *
   * Only valid when [canBeBatchedWithReturn] is `true`. The RETURNING clause is stripped by
   * finding the literal `" RETURNING "` separator, which is safe because
   * [norm.generator.CrudQuerySynthesizer] produces SQL in a known format.
   */
  val batchSql: String
    get() {
      val result = sql.substringBefore(" RETURNING ")
      check(result != sql) { "Expected RETURNING clause in synthesized INSERT: $sql" }
      return result
    }

  /**
   * Column names for [java.sql.Connection.prepareStatement]'s second argument.
   *
   * These are the column names that will be retrievable via
   * [java.sql.PreparedStatement.getGeneratedKeys] after [java.sql.PreparedStatement.executeBatch].
   */
  val returningColumnNames: List<String>
    get() = query.columns.map { it.name }

  /**
   * Whether this SQL statement can have a dynamic variant generated.
   *
   * Dynamic queries return [Query] instead of [Many], allowing callers to append SQL fragments
   * and bind parameters at runtime. Only parameterless `:many` queries are eligible, as they
   * serve as a base for dynamic composition.
   */
  val canBeDynamic: Boolean
    get() = command == Command.MANY && parameters.isEmpty()

  /**
   * Name assigned to the SQL statement by the developer.
   */
  val name get() = query.name

  /**
   * Comments applied to the SQL statement in the SQL script.
   */
  val comments get() = query.comments

  /**
   * Intermediate information about the shape of a row this SQL statement produces.
   *
   * While [command] impacts the Kotlin function return type, this property only concerns itself with what a single
   * returned row would look like when projected into Kotlin.
   *
   * Has more type resolution than the raw query, but is not-yet formatted for output.
   */
  val resultRowShape: ReturnType

  /**
   * JDBC-friendly SQL text for the statement.
   */
  val sql: String

  /**
   * If this SQL statement targets just a single table, will contain a reference to that [Table]. Otherwise, `null`.
   */
  private val starProjectionTable: Table? by lazy(::determineStarProjectionTable)

  /**
   * Returns `true` if this SQL statement covers only a single table and returns every column of that table.
   */
  private val isSingleTableStarProjection: Boolean
    get() {
      val resolvedTable = starProjectionTable ?: return false
      val tableColumnNames = resolvedTable.columns.asSequence().map(Column::name).toSet()
      val queryColumnNames = query.columns.asSequence().map(Column::name).toSet()
      return tableColumnNames == queryColumnNames
    }

  /**
   * How each JDBC `?` placeholder maps to a parameter in [parameters].
   *
   * One entry per `?` in the SQL. Each binding carries the 1-based JDBC position,
   * the index into [parameters] that provides the value, and the column metadata
   * for type resolution.
   *
   * When named parameters are reused (e.g., `:scannedAt` at positions 1 and 2), multiple
   * bindings share the same [ParameterBinding.parameterIndex]. When all parameters are
   * distinct, this is 1:1 with [parameters].
   */
  val parameterBindings: List<ParameterBinding>

  init {
    resultRowShape = computeReturnType()
    sql = query.text

    val allParams = query.params.sortedBy { it.number }
    for (param in allParams) {
      checkNotNull(param.column) { "Parameter at position ${param.number} in query '${query.name}' has no column" }
    }

    if (query.named_parameters.isEmpty()) {
      parameters = allParams
      parameterBindings = allParams.mapIndexed { index, param ->
        ParameterBinding(param.number, index, param.column!!)
      }
    } else {
      val seen = linkedMapOf<String, Int>()
      val unique = mutableListOf<Parameter>()
      val bindings = mutableListOf<ParameterBinding>()
      for (param in allParams) {
        val name = query.named_parameters[param.number]
        val parameterIndex = if (name != null && name in seen) {
          seen.getValue(name)
        } else {
          val index = unique.size
          unique.add(param)
          if (name != null) seen[name] = index
          index
        }
        bindings.add(ParameterBinding(param.number, parameterIndex, param.column!!))
      }
      parameters = unique
      parameterBindings = bindings
    }
  }

  /**
   * Deduplicated parameter names, one per entry in [parameters].
   *
   * When the analyzer infers the same name for multiple parameters (e.g., all parameters of
   * `crosstab(?, ?)` get named "crosstab"), suffixes ensure uniqueness: "crosstab", "crosstab2".
   *
   * Reused named parameters (`:scannedAt` at two positions) are already collapsed in [parameters],
   * so they don't produce suffixes here.
   */
  private val deduplicatedParameterNames: List<String> by lazy {
    val nameCount = mutableMapOf<String, Int>()
    parameters.map { parameter ->
      val baseName = parameter.column!!.name
      val count = nameCount.compute(baseName) { _, v -> (v ?: 0) + 1 }!!
      if (count == 1) baseName else "$baseName$count"
    }
  }

  /**
   * Returns the deduplicated parameter name at the given index in [parameters].
   */
  fun getParameterName(index: Int): String = deduplicatedParameterNames.getOrElse(index) { "param$index" }

  /**
   * Resolves the mappable type for a column with domain type support.
   */
  fun resolveMappableType(column: Column): SqlMappable = generator.resolveMappableType(column)

  /**
   * Resolves the Kotlin [TypeName] for a column with domain type support.
   */
  fun resolveColumnType(column: Column): TypeName = generator.resolveColumnType(column)

  private fun computeReturnType(): ReturnType {
    val queryResults = query.columns
    return if (queryResults.isEmpty()) {
      // The query doesn't return anything
      ReturnType(null, emptyList())
    } else if (queryResults.size == 1 && queryResults.first().embed_table == null) {
      // The query returns a single column, so no wrapper is needed
      val column = queryResults.first()
      val columnType = generator.resolveColumnType(column)
      ReturnType(
        columnType,
        listOf(generator.resolveMappableType(column).resultSetAction(1)),
        listOf(ParameterSpec(column.name, columnType)),
      )
    } else if (isSingleTableStarProjection) {
      // The query is a star projection (eg SELECT * ...). Return a model of the table.
      generator.getTypeProjectionForTable(starProjectionTable!!)
    } else {
      generator.buildTypeProjectionForQuery(query.name, queryResults, query.text)
    }
  }

  private fun determineStarProjectionTable(): Table? {
    val queryColumns = query.columns

    // If any of the columns reference different tables then we don't need to keep going
    if (queryColumns.asSequence().map(Column::table).toSet().size > 1) return null

    // Table is null when using sqlc.embed(). Just means it's not a star projection.
    val table = queryColumns.first().table ?: return null
    return catalog.resolveTable(table)
  }
}

/**
 * Details about the return type of a Kotlin function representing a [SqlStatement].
 *
 * @param kotlinType The base Kotlin type. For example, `Person` or `String`. This does not take into account
 * the query's arity - for that, see [Command]. If `null`, the statement does not return any values.
 * @param builder Code to build the [kotlinType]. Assume a [ResultSet] is the receiver.
 * @param creationParameters Inputs to a function (constructor, mapper, etc.) able to create a [kotlinType].
 */
internal data class ReturnType(
  val kotlinType: TypeName?,
  val builder: List<CodeBlock>,
  val creationParameters: List<ParameterSpec> = emptyList(),
) {

  /**
   * The KotlinPoet type to use as the return value when defining a mapper function capable of producing this
   * [ReturnType].
   *
   * A mapper function is any that produces a type `T` from a [ResultSet].
   */
  val mapperReturnType = TypeVariableName("T", ANY.copy(kotlinType?.isNullable == true))

  /**
   * Indicates that the [ReturnType] will be represented by a Java `Object`.
   */
  val isComposedOfMultipleColumns get() = creationParameters.size > 1
}

/**
 * Maps a single JDBC `?` placeholder to the [SqlStatement] parameter that provides its value.
 *
 * @param jdbcPosition 1-based position of the `?` in the prepared statement.
 * @param parameterIndex Index into [SqlStatement.parameters] (and [SqlStatement.getParameterName]).
 * @param column Column metadata for type resolution (always non-null; validated in [SqlStatement.init]).
 */
internal data class ParameterBinding(val jdbcPosition: Int, val parameterIndex: Int, val column: Column)
