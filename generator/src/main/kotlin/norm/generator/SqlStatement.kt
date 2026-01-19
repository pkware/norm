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
   * Inputs required by the SQL statement.
   */
  val parameters: List<Parameter>

  /**
   * sqlc Command applied to the SQL statement.
   *
   * In sqlc, statements are annotated with a Command that defines how they'll behave at runtime. Where [resultRowShape]
   * contains information about the shape of a single row, [command] impacts what the Kotlin return type of a statement
   * is.
   *
   * See [Command] and the [sqlc documentation](https://docs.sqlc.dev/en/latest/reference/query-annotations.html).
   */
  val command get() = Command.fromSqlcCmd(query.cmd)

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
   *
   * See [sqlc documentation](https://docs.sqlc.dev/en/latest/reference/query-annotations.html).
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

  init {
    resultRowShape = computeReturnType()

    // TODO SQLC doesn't preserve case on @named parameters. We should file a bug.
    // Convert $1 style query parameters to JDBC-compatible ? parameters.
    // First we find all the placeholders, and create a list of parameters where each index matches the position of a
    // future JDBC parameter. We populate the list by mapping the query parameters to placeholder indexes.
    // Finally we replace the placeholders in the query text.
    parameters = SQLC_ARGUMENT_REGEX.findAll(query.text)
      .map { match ->
        val parameterNumber = match.value.substring(1).toInt()
        query.params.first { it.number == parameterNumber }
      }
      .toList()
    sql = query.text.replace(SQLC_ARGUMENT_REGEX, "?")
  }

  /**
   * List of deduplicated parameter names, indexed by position in the parameters list.
   *
   * When sqlc cannot infer meaningful parameter names (e.g., for extension function parameters),
   * it may assign the same name to multiple parameters. This list ensures each parameter gets a unique name
   * by appending numeric suffixes (name, name2, name3, ...).
   */
  private val deduplicatedParameterNames: List<String> by lazy {
    val nameCount = mutableMapOf<String, Int>()
    parameters.mapNotNull(Parameter::column).map { column ->
      val baseName = column.name
      val count = nameCount.compute(baseName) { _, v -> (v ?: 0) + 1 }!!
      if (count == 1) baseName else "$baseName$count"
    }
  }

  /**
   * Returns the deduplicated parameter name at the given index.
   *
   * This handles cases where sqlc assigns duplicate names to parameters by appending numeric suffixes.
   * Returns a fallback name if the index is out of bounds.
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
      generator.buildTypeProjectionForQuery(query.name, queryResults)
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

  private companion object {
    private val SQLC_ARGUMENT_REGEX = Regex("""\$\d+""")
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
